package dev.kuch.termx.feature.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.feature.terminal.connection.ConnectionManager
import dev.kuch.termx.feature.terminal.connection.TermxConnection
import dev.kuch.termx.feature.terminal.connection.TransportState
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Screen-side BINDER between `TerminalScreen` and the process-wide
 * [ConnectionManager] (the Server-ownership refactor, Task #42).
 *
 * The transport — [dev.kuch.termx.libs.sshnative.SshSession] /
 * [dev.kuch.termx.libs.sshnative.MoshSession], the PTY, the byte pump,
 * the mosh side channel — is owned by [ConnectionManager], whose scope
 * is process-lifetime. This VM only:
 *
 *  - delegates connect/disconnect/writeToPty/submitPassword,
 *  - maps the connection's [TransportState] + VM-local UI state
 *    (pending URL tap) into the screen-facing [TerminalUiState],
 *  - bridges the per-connection [TermxConnection.writeErrors] /
 *    [TermxConnection.transportNotices] one-shot flows.
 *
 * LIFECYCLE (Task #43 flip): this VM dying does NOT end the session.
 * Leaving the terminal screen (back, home, vault re-lock navigation)
 * clears the VM, but the manager-owned transport keeps running;
 * re-entering the screen rebinds to the live slot via the manager's
 * bind-if-alive [connect]. Disconnecting is an explicit user action —
 * the screen's Disconnect button → [disconnect] → manager teardown.
 * The notification-driven "Disconnect all" / Reconnect collectors live
 * in [ConnectionManager] (they must work with no screen alive), so
 * this VM holds NO connection-lifecycle jobs at all; `onCleared` has
 * nothing transport-shaped to do and is deliberately not overridden.
 *
 * SERVER IDENTITY (Task #47): the terminal is a sheet overlay, not a
 * nav destination, so there are no nav args and no SavedStateHandle
 * read anymore. The server id flows in explicitly: `TerminalSheetState`
 * holds the maximized id, the sheet host resolves this VM with
 * `hiltViewModel(key = "terminal-$serverId")` (one binder per server on
 * the Activity's store) and the screen passes the id into [connect].
 * SavedStateHandle was dropped from the constructor rather than left as
 * a dead fallback — with no route there is nothing it could ever carry.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    /**
     * The manager-side connection slot this screen is bound to. Assigned
     * synchronously by [connect] (the manager has already flipped the
     * slot to Connecting by the time it hands the slot back, so the
     * mapping below never renders a previous visit's stale
     * Disconnected/Error state). Null until the first [connect] —
     * mapped to the Idle UI state, exactly like the old VM's initial
     * `TerminalUiState()`.
     */
    private val connection = MutableStateFlow<TermxConnection?>(null)

    /**
     * URL pending a confirmation dialog (Task #17 double-tap). Pure
     * view-layer state — it stays in the VM, not the manager.
     */
    private val pendingUrlTap = MutableStateFlow<String?>(null)

    /**
     * Screen-facing state: the bound connection's [TransportState]
     * folded together with VM-local UI state. See [mapToUiState] for the
     * field-by-field correspondence with the old flat-VM behavior.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<TerminalUiState> = combine(
        connection.flatMapLatest { conn ->
            conn?.state ?: flowOf<TransportState?>(null)
        },
        pendingUrlTap,
    ) { transport, url -> mapToUiState(transport, url) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalUiState())

    /**
     * Bridge of the bound connection's one-shot PTY-write-failure flow
     * (snackbar with "Reconnect"; see [TermxConnection.writeErrors] for
     * the v1.1.13 history). Stable across reconnects to the same server
     * because the manager reuses the slot.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val writeErrors: Flow<String> = connection.flatMapLatest {
        it?.writeErrors ?: emptyFlow()
    }

    /**
     * Bridge of the bound connection's one-shot transport notices
     * ("Connected via SSH — mosh unavailable: <reason>"); see
     * [TermxConnection.transportNotices].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val transportNotices: Flow<String> = connection.flatMapLatest {
        it?.transportNotices ?: emptyFlow()
    }

    /**
     * Currently-selected terminal font size in sp. Read eagerly so the
     * first composition of [dev.kuch.termx.feature.terminal.TerminalScreen]
     * doesn't flicker from the Room/DataStore default of 14.
     */
    val fontSizeSp: StateFlow<Int> = appPreferences.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_SIZE_SP)

    /**
     * Start (or rebind to) a connection. The manager's [connect] is
     * bind-if-alive: a live slot (Connecting/AwaitingPassword/Connected)
     * is returned untouched — same emulator, no redial — so re-maximizing
     * the sheet for a live server shows the existing terminal content
     * instantly. A null [serverId] is the BuildConfig test-server path
     * (the manager keys it under its all-zeros fallback sentinel).
     */
    fun connect(serverId: UUID?) {
        connection.value = connectionManager.connect(serverId)
    }

    /**
     * Invoked by the terminal's password prompt dialog. Delegates to
     * [ConnectionManager.submitPassword] (in-memory cache + vault
     * persist with the password-alias self-heal) which retries the
     * connect; we just (re)bind the returned slot.
     */
    fun submitPassword(serverId: UUID, password: String) {
        connection.value = connectionManager.submitPassword(serverId, password)
    }

    /** User cancelled the password prompt — just clear the prompt state. */
    fun cancelPasswordPrompt() {
        connection.value?.let { connectionManager.cancelPasswordPrompt(it.serverId) }
    }

    /** Clear a one-shot error once the UI has rendered it. */
    fun clearError() {
        connection.value?.let { connectionManager.clearError(it.serverId) }
    }

    /**
     * Task #17 pinch-to-zoom persistence. Called from the TerminalView
     * scale-gesture `onScaleEnd` callback so a single gesture produces
     * exactly one DataStore write.
     */
    fun onFontSizeChanged(sp: Int) {
        viewModelScope.launch {
            runCatching { appPreferences.setFontSizeSp(sp) }
                .onFailure { android.util.Log.w(LOG_TAG, "persist font size failed", it) }
        }
    }

    /** Queue a URL confirmation dialog for the next recomposition. */
    fun onUrlDoubleTap(url: String) {
        pendingUrlTap.value = url
    }

    /** Dismiss the URL dialog (user hit Cancel or the system scrim). */
    fun onUrlTapDismissed() {
        pendingUrlTap.value = null
    }

    /**
     * Clear the pending-URL state once the composable has launched the
     * browser intent. The intent firing itself is done in
     * [dev.kuch.termx.feature.terminal.gestures.UrlTapConfirmDialog] —
     * the VM just owns the one-shot "open tap" state.
     */
    fun onUrlTapConfirmed() {
        pendingUrlTap.value = null
    }

    /**
     * Forward raw bytes to the shell PTY. Used by the extra-keys
     * toolbar and the Volume-Down=Ctrl binding.
     */
    fun writeToPty(bytes: ByteArray) {
        val conn = connection.value ?: return
        connectionManager.writeToPty(conn.serverId, bytes)
    }

    /**
     * Two-phase "type this line and press Enter" — the PTT Send path
     * (Task #53). Thin delegate so the screen keeps talking only to
     * this VM; the mechanics (bracketed-paste wrap, transport-sized
     * delay before the lone CR, atomicity on the per-shell write
     * queue) live in [ConnectionManager.submitLine] and its
     * `buildSubmitSequence` KDoc. NOT a convenience alias for
     * [writeToPty] with a trailing CR — that single-buffer shape is
     * the Claude Code submit bug this path exists to fix.
     */
    fun submitLine(text: String) {
        val conn = connection.value ?: return
        connectionManager.submitLine(conn.serverId, text)
    }

    /**
     * EXPLICIT user-driven teardown — the screen's Disconnect action.
     * Idempotent; delegates to the manager. This is the ONLY way a VM
     * ends a session: `onCleared` deliberately does not disconnect
     * (leaving the screen keeps the session alive — see class KDoc).
     */
    fun disconnect() {
        connection.value?.let { connectionManager.disconnect(it.serverId) }
    }

    /**
     * Field-by-field projection of the manager's [TransportState] onto
     * the screen's flat [TerminalUiState], preserving the pre-refactor
     * semantics:
     *
     *  - no bound connection → Idle (the old VM's initial state),
     *  - AwaitingPassword → status Disconnected + prompt info, no error
     *    (the old PasswordRequiredException branch),
     *  - Error → status Disconnected + error message (ErrorPane),
     *  - Connected carries the emulator + truthful-transport fields.
     */
    private fun mapToUiState(
        transport: TransportState?,
        pendingUrl: String?,
    ): TerminalUiState = when (transport) {
        null -> TerminalUiState(
            status = TerminalUiState.Status.Idle,
            pendingUrlTap = pendingUrl,
        )
        TransportState.Connecting -> TerminalUiState(
            status = TerminalUiState.Status.Connecting,
            pendingUrlTap = pendingUrl,
        )
        is TransportState.AwaitingPassword -> TerminalUiState(
            status = TerminalUiState.Status.Disconnected,
            awaitingPassword = AwaitingPasswordInfo(
                serverId = transport.serverId,
                serverLabel = transport.serverLabel,
            ),
            pendingUrlTap = pendingUrl,
        )
        is TransportState.Connected -> TerminalUiState(
            status = TerminalUiState.Status.Connected,
            activeSession = transport.session,
            moshBacked = transport.moshBacked,
            transportFallbackReason = transport.transportFallbackReason,
            moshDiagnostic = transport.transportFallbackDetail,
            pendingUrlTap = pendingUrl,
        )
        is TransportState.Error -> TerminalUiState(
            status = TerminalUiState.Status.Disconnected,
            error = transport.message,
            moshDiagnostic = transport.detail,
            pendingUrlTap = pendingUrl,
        )
        TransportState.Disconnected -> TerminalUiState(
            status = TerminalUiState.Status.Disconnected,
            pendingUrlTap = pendingUrl,
        )
    }

    private companion object {
        const val LOG_TAG = "TerminalViewModel"
        const val DEFAULT_FONT_SIZE_SP = 14
    }
}
