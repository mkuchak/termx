package dev.kuch.termx.feature.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.MoshRemoteTerminalSession
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.remote.EventStreamRepository
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.core.data.session.ReconnectBroker
import dev.kuch.termx.core.data.session.SessionRegistry
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.feature.terminal.BuildConfig
import dev.kuch.termx.feature.terminal.gestures.TerminalGestureHandler
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import dev.kuch.termx.libs.sshnative.tmuxAutoAttachCommand
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the live SSH transport plus one [PtyChannel] per open tmux tab.
 *
 * Task #15 shipped a single-PTY VM; Task #26 extends it to a
 * `Map<String, SessionPty>` keyed by tmux session name so tab swaps
 * don't tear down the remote shell. Closed tabs (user-initiated
 * detach or kill) drop their entry; switching back to a detached tab
 * opens a fresh `tmux attach-session -t '<name>'`.
 *
 * Important invariants:
 *  - One [SshSession] is shared across every tab on this server.
 *    `cleanupQuietly()` closes the session, which implicitly closes
 *    every [PtyChannel] under it.
 *  - Each [SessionPty] holds its own [RemoteTerminalSession] +
 *    [TerminalEmulator] so scrollback / cursor state survives a tab
 *    swap (memory-cap is a follow-up — see Task #54 / the roadmap
 *    Phase 3.2 notes).
 *  - Only the tab named by [uiState.activeTabName] is bound to the
 *    Android `TerminalView`; the others keep feeding their emulators
 *    in the background so re-binding on swap shows up-to-date output.
 */
/**
 * Thrown by `resolveConnection` when the selected server uses password auth
 * but no password is cached yet. The `connect()` flow catches this
 * specifically and surfaces a prompt dialog instead of an error banner.
 */
class PasswordRequiredException(
    val serverId: UUID,
    val serverLabel: String,
) : Exception("Password required for $serverLabel")

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val passwordCache: PasswordCache,
    private val appPreferences: AppPreferences,
    private val sessionRegistry: SessionRegistry,
    private val eventStreamHub: EventStreamHub,
    private val eventStreamRepository: EventStreamRepository,
    private val reconnectBroker: ReconnectBroker,
    private val moshClient: MoshClient,
) : ViewModel() {

    init {
        // Notification "Disconnect all" action posts into the registry;
        // every live VM collects it and tears its own session down.
        viewModelScope.launch {
            sessionRegistry.disconnectAllRequest.collect {
                disconnect()
            }
        }
        // Notification "Reconnect" action posts into the broker. We act
        // on a request only if the matching server id is ours — multiple
        // TerminalViewModels listen simultaneously so the keyed check is
        // how each one distinguishes its own request.
        viewModelScope.launch {
            reconnectBroker.requests.collect { serverId ->
                if (serverId == currentServerRegistryId) {
                    disconnect()
                    connect(currentServerId)
                }
            }
        }
    }

    /**
     * One open tab. [outputJob] collects the transport's byte stream
     * into [emulator] until the channel ends or we cancel it; dropping
     * the tab cancels the job and closes the transport handle.
     *
     * Either [pty] (sshj PTY) or [moshSession] (local mosh-client
     * process) is non-null — never both, never neither. The mosh path
     * is single-channel, so a mosh-backed connection has exactly one
     * tab for now; multi-tab multiplexing still happens via tmux
     * running inside the mosh session.
     *
     * [serverIdForRegistry] + [serverLabel] mirror what we pushed into
     * [SessionRegistry] on open so the matching unregister call can be
     * made from [detachTab] / [cleanupQuietly] without another repo hit.
     */
    private data class SessionPty(
        val name: String,
        val pty: PtyChannel?,
        val moshSession: MoshSession?,
        val emulator: TerminalSession,
        val outputJob: Job,
        val serverIdForRegistry: UUID,
        val serverLabel: String,
    ) {
        init {
            require((pty == null) xor (moshSession == null)) {
                "SessionPty needs exactly one of pty or moshSession"
            }
        }
    }

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    /**
     * Transient PTY-write failures surfaced to the UI as a snackbar.
     * Pre-v1.1.13 these were swallowed by `runCatching` in
     * [writeToPty] — the user's keystrokes vanished into a stale SSH
     * connection with no feedback. Now we emit a one-line message
     * here; TerminalScreen collects + shows snackbar with a
     * "Reconnect" action.
     *
     * `extraBufferCapacity = 1` + DROP_OLDEST so a flurry of failures
     * coalesces into one snackbar instead of stacking, and we never
     * suspend the IO coroutine just to deliver an error.
     */
    private val _writeErrors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val writeErrors: SharedFlow<String> = _writeErrors.asSharedFlow()

    /**
     * Task #28 — true between the two-finger scrollback gesture entering
     * tmux `copy-mode` (prefix `[`) and the `q` keystroke that exits it.
     * The gesture layer flips this on pointerDown, forwards drag deltas
     * as arrow keys, then flips it off on pointerUp. Kept as a StateFlow
     * so future UI (e.g. a "copy-mode" chip) can observe it without
     * coupling to the gesture handler.
     */
    private val _inCopyMode = MutableStateFlow(false)
    val inCopyMode: StateFlow<Boolean> = _inCopyMode.asStateFlow()

    /**
     * Currently-selected terminal font size in sp. Read eagerly so the
     * first composition of [dev.kuch.termx.feature.terminal.TerminalScreen]
     * doesn't flicker from the Room/DataStore default of 14.
     */
    val fontSizeSp: StateFlow<Int> = appPreferences.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_SIZE_SP)

    /**
     * Currently-selected theme id. Task #18 theme picker writes; this
     * flow drives the live repaint in `TerminalScreen`.
     */
    val activeThemeId: StateFlow<String> = appPreferences.activeThemeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_THEME_ID)

    private var currentServerId: UUID? = null
    /**
     * Cached display label + stable UUID for the active connection, used
     * to tag every tab pushed into [SessionRegistry]. The stable UUID is
     * the persisted server id when we have one, or [FALLBACK_SERVER_ID]
     * for the BuildConfig test-server path — either way the registry
     * (and the service's notification) sees a consistent key.
     */
    private var currentServerLabel: String = "termx"
    private var currentServerRegistryId: UUID = FALLBACK_SERVER_ID
    private var sshClient: SshClient? = null
    private var sshSession: SshSession? = null

    private val tabs = ConcurrentHashMap<String, SessionPty>()

    private var connectJob: Job? = null

    /**
     * Start a connection. Idempotent across Connecting/Connected. Sets
     * up the shared [SshSession] and opens the first PTY against the
     * tmux session declared on the server row (creating-if-needed via
     * `tmux new-session -A`).
     */
    fun connect(serverId: UUID?) {
        val s = _state.value.status
        if (s == TerminalUiState.Status.Connecting || s == TerminalUiState.Status.Connected) return

        val resolvedId = serverId ?: savedStateHandle.get<String>(ARG_SERVER_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                status = TerminalUiState.Status.Connecting,
                error = null,
            )
            runCatching { openSession(resolvedId) }
                .onFailure { t ->
                    if (t is PasswordRequiredException) {
                        _state.value = _state.value.copy(
                            status = TerminalUiState.Status.Disconnected,
                            awaitingPassword = AwaitingPasswordInfo(t.serverId, t.serverLabel),
                            error = null,
                        )
                        return@onFailure
                    }
                    Log.e(LOG_TAG, "connect failed", t)
                    cleanupQuietly()
                    _state.value = _state.value.copy(
                        status = TerminalUiState.Status.Disconnected,
                        error = t.message ?: "connection failed",
                        activeSession = null,
                        activeTabName = null,
                        openTabs = emptySet(),
                    )
                }
        }
    }

    /**
     * Invoked by the terminal's password prompt dialog. Caches the
     * password in-memory for this process's lifetime, persists it into
     * the vault (self-healing — see below), and retries [connect] for
     * the same server.
     *
     * Persisting here — not only in the Add/Edit sheet — is what stops
     * the "app asks for the password on every restart" case. Without
     * this call the password lives only in [PasswordCache], which dies
     * with the process; the prompt then fires again on every cold
     * start even though the user already answered it yesterday.
     *
     * Self-heal: older versions of [AddEditServerViewModel.save]
     * incorrectly nuked `server.passwordAlias` on any edit with a
     * blank password field (fixed in the same commit as this
     * function). For users whose Room rows still carry that damage,
     * we mint a fresh `"password-$serverId"` alias on the fly, write
     * the password under it, AND upsert the server row so future
     * cold-start `resolveConnection` paths pick it up. The prompt
     * never reappears after a single successful submission.
     */
    fun submitPassword(serverId: UUID, password: String) {
        passwordCache.put(serverId, password)
        _state.value = _state.value.copy(awaitingPassword = null)
        viewModelScope.launch(Dispatchers.IO) {
            val server = runCatching { serverRepository.getById(serverId) }.getOrNull()
                ?: return@launch
            val alias = server.passwordAlias ?: "password-$serverId"
            val storeResult = runCatching {
                secretVault.store(alias, password.toByteArray(Charsets.UTF_8))
            }
            storeResult.onFailure {
                Log.w(LOG_TAG, "persisting prompted password failed", it)
            }
            if (storeResult.isSuccess && server.passwordAlias != alias) {
                runCatching {
                    serverRepository.upsert(server.copy(passwordAlias = alias))
                }.onFailure {
                    Log.w(LOG_TAG, "heal-updating server.passwordAlias failed", it)
                }
            }
        }
        connect(serverId)
    }

    /** User cancelled the password prompt — just clear the flag. */
    fun cancelPasswordPrompt() {
        _state.value = _state.value.copy(awaitingPassword = null)
    }

    private suspend fun openSession(serverId: UUID?) {
        currentServerId = serverId
        val (target, auth) = resolveConnection(serverId)
        val server = serverId?.let { serverRepository.getById(it) }
        currentServerRegistryId = server?.id ?: FALLBACK_SERVER_ID
        currentServerLabel = server?.label ?: "Test server"

        // Phase 3 mosh race. If the Server row opts into mosh, try the
        // mosh-server handshake first with an 8s cap; if that returns
        // null (server missing, UDP blocked, timeout), fall through to
        // the sshj PTY path below. Mosh is single-channel so it opens
        // exactly one tab; multi-session needs are handled by tmux
        // running inside the mosh session on the remote.
        if (server?.useMosh == true) {
            val mosh = runCatching {
                moshClient.tryConnect(
                    target = target,
                    auth = auth,
                    handshakeTimeoutMs = MOSH_HANDSHAKE_TIMEOUT_MS,
                )
            }.onFailure { Log.w(LOG_TAG, "mosh tryConnect threw", it) }
                .getOrNull()

            if (mosh != null) {
                val tab = openMoshTab(mosh, server.tmuxSessionName)
                touchLastConnected(server.id)
                _state.value = _state.value.copy(
                    status = TerminalUiState.Status.Connected,
                    activeSession = tab.emulator,
                    activeTabName = tab.name,
                    openTabs = tabs.keys.toSet(),
                    tmuxMissing = false,
                    tmuxBacked = server.autoAttachTmux,
                    moshBacked = true,
                    error = null,
                )
                return
            }
            Log.i(LOG_TAG, "mosh handshake did not complete in ${MOSH_HANDSHAKE_TIMEOUT_MS}ms; falling back to ssh")
            // Tell the user we silently downgraded — otherwise tapping
            // a mosh-flagged server, getting an SSH session, and seeing
            // disconnects on flaky networks looks identical to "mosh
            // works fine". The snackbar is one-shot via _writeErrors;
            // the SSH session still proceeds below.
            _writeErrors.tryEmit(
                "Mosh handshake didn't complete in ${MOSH_HANDSHAKE_TIMEOUT_MS / 1_000}s — using SSH instead.",
            )
        }

        val client = SshClient().also { sshClient = it }
        val session = client.connect(target, auth)
        sshSession = session

        // Publish the live session so `EventNotificationRouter` (and any
        // future consumer) can tail `events.ndjson` without coordinating
        // with this VM directly. Re-publishing is idempotent — hub state
        // is keyed by server id so reconnecting replaces the prior entry.
        eventStreamHub.publish(
            serverId = currentServerRegistryId,
            serverLabel = currentServerLabel,
            client = eventStreamRepository.clientFor(session),
        )

        val wantsTmux = server?.autoAttachTmux == true
        val tmuxAvailable = if (wantsTmux) probeTmux(session) else true
        val initialTabName = if (wantsTmux && tmuxAvailable) server!!.tmuxSessionName else DEFAULT_TAB
        val attachCommand = if (wantsTmux && tmuxAvailable) {
            tmuxAutoAttachCommand(server!!.tmuxSessionName)
        } else null
        val tmuxMissing = wantsTmux && !tmuxAvailable

        val tab = openTab(initialTabName, attachCommand)
        server?.id?.let { touchLastConnected(it) }
        _state.value = _state.value.copy(
            status = TerminalUiState.Status.Connected,
            activeSession = tab.emulator,
            activeTabName = tab.name,
            openTabs = tabs.keys.toSet(),
            tmuxMissing = tmuxMissing,
            tmuxBacked = wantsTmux && tmuxAvailable,
            moshBacked = false,
            error = null,
        )
    }

    /**
     * Fire-and-forget write of `lastConnected = now()` for [serverId].
     * Runs off the UI-state-update path so a slow/failing Room write can't
     * block surfacing "Connected" — the server card on the list just keeps
     * its prior stamp (or "never connected") if the write drops.
     */
    private fun touchLastConnected(serverId: UUID) {
        viewModelScope.launch {
            runCatching { serverRepository.updateLastConnected(serverId, Instant.now()) }
                .onFailure { Log.w(LOG_TAG, "updateLastConnected failed for $serverId", it) }
        }
    }

    /**
     * Switch to an already-open tab or open a fresh `tmux attach-session`
     * for [name].
     *
     * Open-tab case: just rebind the emulator. The background output
     * job is still running, so the emulator already has up-to-date
     * scrollback — the view's `update` callback picks up the new
     * session reference and re-attaches on the next frame.
     *
     * Missing-tab case: we assume the session actually exists on the
     * remote (the caller is reacting to a tab-bar row that came from
     * the poller) and open a new PTY attached to it. Failing that,
     * the PTY open will throw and we surface the error via a snackbar
     * at the call site.
     */
    fun selectTab(name: String) {
        val existing = tabs[name]
        if (existing != null) {
            _state.value = _state.value.copy(
                activeSession = existing.emulator,
                activeTabName = existing.name,
                openTabs = tabs.keys.toSet(),
            )
            return
        }
        val serverId = currentServerId
        if (sshSession == null) {
            // Mosh transport is single-channel — no way to open a
            // second remote shell from the phone once mosh has the
            // only UDP tuple. Users open more tabs via tmux running
            // inside the mosh session on the remote.
            Log.w(LOG_TAG, "selectTab without live ssh session (mosh transport?)")
            return
        }
        viewModelScope.launch {
            runCatching {
                val command = "tmux attach-session -t '${name.replace("'", "")}'"
                openTab(name, command)
            }.onSuccess { tab ->
                _state.value = _state.value.copy(
                    activeSession = tab.emulator,
                    activeTabName = tab.name,
                    openTabs = tabs.keys.toSet(),
                )
            }.onFailure { t ->
                Log.w(LOG_TAG, "selectTab failed for $name on $serverId", t)
                _state.value = _state.value.copy(error = "Couldn't open '$name': ${t.message}")
            }
        }
    }

    /**
     * Drop the PtyChannel for [name] without killing the remote tmux
     * session. If the dropped tab was active, we fall back to any
     * other open tab; otherwise we transition to Disconnected so the
     * UI can prompt a reconnect.
     */
    fun detachTab(name: String) {
        val tab = tabs.remove(name) ?: return
        tab.outputJob.cancel()
        runCatching { tab.pty?.close() }
        runCatching { tab.moshSession?.close() }
        notifyTabEmulatorFinished(tab.emulator)
        sessionRegistry.unregister(tab.serverIdForRegistry, tab.name)

        val active = _state.value.activeTabName
        if (active == name) {
            val fallback = tabs.values.firstOrNull()
            if (fallback != null) {
                _state.value = _state.value.copy(
                    activeSession = fallback.emulator,
                    activeTabName = fallback.name,
                    openTabs = tabs.keys.toSet(),
                )
            } else {
                _state.value = _state.value.copy(
                    activeSession = null,
                    activeTabName = null,
                    openTabs = tabs.keys.toSet(),
                    status = TerminalUiState.Status.Disconnected,
                )
            }
        } else {
            _state.value = _state.value.copy(openTabs = tabs.keys.toSet())
        }
    }

    /** Clear a one-shot error once the UI has rendered it. */
    fun clearError() {
        if (_state.value.error != null) {
            _state.value = _state.value.copy(error = null)
        }
    }

    /**
     * Task #17 pinch-to-zoom persistence. Called from the TerminalView
     * scale-gesture `onScaleEnd` callback so a single gesture produces
     * exactly one DataStore write.
     */
    fun onFontSizeChanged(sp: Int) {
        viewModelScope.launch {
            runCatching { appPreferences.setFontSizeSp(sp) }
                .onFailure { Log.w(LOG_TAG, "persist font size failed", it) }
        }
    }

    /** Queue a URL confirmation dialog for the next recomposition. */
    fun onUrlDoubleTap(url: String) {
        _state.value = _state.value.copy(pendingUrlTap = url)
    }

    /** Dismiss the URL dialog (user hit Cancel or the system scrim). */
    fun onUrlTapDismissed() {
        if (_state.value.pendingUrlTap != null) {
            _state.value = _state.value.copy(pendingUrlTap = null)
        }
    }

    /**
     * Clear the pending-URL state once the composable has launched the
     * browser intent. The intent firing itself is done in
     * [dev.kuch.termx.feature.terminal.gestures.UrlTapConfirmDialog] —
     * the VM just owns the one-shot "open tap" state.
     */
    fun onUrlTapConfirmed() {
        if (_state.value.pendingUrlTap != null) {
            _state.value = _state.value.copy(pendingUrlTap = null)
        }
    }

    /**
     * Open a PTY for [tabName], wiring bytes in both directions into
     * a fresh [RemoteTerminalSession], and register the tab in
     * [tabs]. Caller updates UI state.
     */
    private suspend fun openTab(tabName: String, command: String?): SessionPty {
        val session = sshSession ?: throw IllegalStateException("No live ssh session")
        val channel = session.openShell(
            cols = INITIAL_COLS,
            rows = INITIAL_ROWS,
            command = command,
        )

        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = {
                // Remote shell exited (user typed `exit`, tmux was
                // killed, transport dropped). Clean just this tab; the
                // rest of the transport may still be healthy.
                detachTab(tabName)
            },
        )
        val emulator = RemoteTerminalSession(
            client = sessionClient,
            onInputBytes = { bytes ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { channel.write(bytes) }
                        .onFailure { Log.w(LOG_TAG, "pty write failed", it) }
                }
            },
            onResize = { cols, rows ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { channel.resize(cols, rows) }
                        .onFailure { Log.w(LOG_TAG, "pty resize failed", it) }
                }
            },
        )

        val outputJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                channel.output.collect { chunk ->
                    emulator.feedRemoteBytes(chunk)
                }
            }.onFailure { t -> Log.w(LOG_TAG, "pty output ended for $tabName", t) }
            emulator.onRemoteSessionClosed()
        }

        val tab = SessionPty(
            name = tabName,
            pty = channel,
            moshSession = null,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = currentServerRegistryId,
            serverLabel = currentServerLabel,
        )
        tabs[tabName] = tab
        sessionRegistry.register(
            serverId = tab.serverIdForRegistry,
            serverLabel = tab.serverLabel,
            tabName = tab.name,
        )
        return tab
    }

    /**
     * Wire a live [MoshSession] into a single tab. The mosh transport
     * is single-channel; any further tabs rely on tmux running inside
     * the mosh session on the remote.
     *
     * Resize + keypress bytes go straight into mosh-client's stdin /
     * SIGWINCH path. Output is piped from the child process stdout
     * into the emulator by [outputJob].
     */
    private fun openMoshTab(session: MoshSession, tabName: String): SessionPty {
        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = {
                detachTab(tabName)
            },
        )
        val emulator = MoshRemoteTerminalSession(
            client = sessionClient,
            onInputBytes = { bytes ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { session.write(bytes) }
                        .onFailure { Log.w(LOG_TAG, "mosh write failed", it) }
                }
            },
            onResize = { cols, rows ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { session.resize(cols, rows) }
                        .onFailure { Log.w(LOG_TAG, "mosh resize failed", it) }
                }
            },
        )

        val outputJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                session.output.collect { chunk ->
                    emulator.feedRemoteBytes(chunk)
                }
            }.onFailure { t -> Log.w(LOG_TAG, "mosh output ended for $tabName", t) }
            emulator.onRemoteSessionClosed()
            // mosh-client stdout EOF ⇒ the child process either exited
            // cleanly (user typed `exit` on the remote) or died during
            // startup / runtime. Decide what to surface via the pure
            // helper [MoshExitMessage] — covers early non-zero exits
            // (linker / static-init crashes), late non-zero exits
            // (runtime segfaults, OOM-kill), and the suspicious
            // "clean but fast" path that almost always means UDP is
            // blocked between phone and VPS.
            //
            // The `isActive` guard is what stops a user-initiated
            // disconnect (detachTab → outputJob.cancel + close →
            // SIGKILL) from surfacing a spurious "exit 137" snackbar
            // on the user's own action: when the coroutine has been
            // cancelled, we drop the diagnostic on the floor and let
            // [disconnect] / [detachTab] own the UI transition.
            val diag = session.diagnostic.value
            if (currentCoroutineContext().isActive && diag.exitCode != null) {
                val message = MoshExitMessage.forDiagnostic(
                    exitCode = diag.exitCode!!,
                    elapsedMs = diag.elapsedMs,
                    head = diag.head,
                )
                if (message != null) {
                    _state.value = _state.value.copy(
                        status = TerminalUiState.Status.Disconnected,
                        error = message,
                        activeSession = null,
                        activeTabName = null,
                        openTabs = emptySet(),
                    )
                }
            }
        }

        val tab = SessionPty(
            name = tabName,
            pty = null,
            moshSession = session,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = currentServerRegistryId,
            serverLabel = currentServerLabel,
        )
        tabs[tabName] = tab
        sessionRegistry.register(
            serverId = tab.serverIdForRegistry,
            serverLabel = tab.serverLabel,
            tabName = tab.name,
        )
        return tab
    }

    /**
     * Decide which host + credentials to use.
     *
     * For a non-null [serverId] we resolve the Server row and (for
     * key auth) unlock the private bytes from the vault. Missing rows,
     * missing keys, or a locked vault throw — the outer
     * `runCatching` in [connect] turns them into a user-facing error.
     *
     * For a null [serverId] we fall back to the Phase 1 env-var path.
     */
    private suspend fun resolveConnection(serverId: UUID?): Pair<SshTarget, SshAuth> {
        val knownHostsPath = appContext.filesDir.absolutePath + "/known_hosts"

        if (serverId != null) {
            val server = serverRepository.getById(serverId)
                ?: throw IllegalStateException("Server not found")

            val target = SshTarget(
                host = server.host,
                port = server.port,
                username = server.username,
                knownHostsPath = knownHostsPath,
            )

            val auth: SshAuth = when (server.authType) {
                AuthType.KEY -> {
                    val keyPairId = server.keyPairId
                        ?: throw IllegalStateException("Server has no key assigned")
                    val keyPair = keyPairRepository.getById(keyPairId)
                        ?: throw IllegalStateException("Linked key not found")
                    val bytes = try {
                        secretVault.load(keyPair.keystoreAlias)
                    } catch (t: VaultLockedException) {
                        throw IllegalStateException("Vault is locked — unlock to connect", t)
                    } ?: throw IllegalStateException("Private key missing from vault")
                    SshAuth.PublicKey(privateKeyPem = bytes, passphrase = null)
                }
                AuthType.PASSWORD -> {
                    // Vault first, in-memory cache second. Vault wins so a
                    // cold-start reconnect uses the persisted password; the
                    // cache only matters for unsaved wizard drafts or
                    // password-entered-but-not-yet-saved flows.
                    // VaultLockedException is treated as "missing" — the
                    // PasswordRequiredException path re-prompts, and the
                    // app's biometric unlock flow handles the rest.
                    val fromVault = server.passwordAlias?.let { alias ->
                        runCatching { secretVault.load(alias) }
                            .getOrNull()
                            ?.let { bytes -> String(bytes, Charsets.UTF_8) }
                    }
                    val password = fromVault
                        ?: passwordCache.get(server.id)
                        ?: throw PasswordRequiredException(server.id, server.label)
                    SshAuth.Password(password)
                }
            }
            return target to auth
        }

        val host = BuildConfig.TEST_SERVER_HOST
        val user = BuildConfig.TEST_SERVER_USER
        val port = BuildConfig.TEST_SERVER_PORT
        if (host.isBlank() || user.isBlank()) {
            throw IllegalStateException("No test server configured")
        }
        val keyBytes = readTestKey()
            ?: throw IllegalStateException("No test server configured")
        val target = SshTarget(host = host, port = port, username = user, knownHostsPath = knownHostsPath)
        val auth = SshAuth.PublicKey(privateKeyPem = keyBytes, passphrase = null)
        return target to auth
    }

    /**
     * Task #28 — invoked by the two-finger scroll gesture on the first
     * drag delta. Enters tmux `copy-mode` on the active PTY and flips
     * [inCopyMode] so repeated drags don't re-enter. No-op if there's
     * no active tab or we're already in copy-mode.
     */
    fun startCopyMode() {
        if (_inCopyMode.value) return
        val active = _state.value.activeTabName ?: return
        // Only the sshj PTY path has a structural way to send the
        // tmux copy-mode prefix today. The mosh transport relies on
        // tmux running inside the mosh session — it already has a
        // native binding for copy-mode, so the gesture becomes a
        // no-op rather than double-dispatching the prefix.
        val channel = tabs[active]?.pty ?: return
        _inCopyMode.value = true
        viewModelScope.launch(Dispatchers.IO) {
            TerminalGestureHandler.enterTmuxCopyMode(channel)
        }
    }

    /**
     * Task #28 — invoked by the gesture's drag-end callback. Sends `q`
     * to leave tmux `copy-mode` and clears [inCopyMode]. No-op if we
     * weren't in copy-mode.
     */
    fun endCopyMode() {
        if (!_inCopyMode.value) return
        _inCopyMode.value = false
        val active = _state.value.activeTabName ?: return
        val channel = tabs[active]?.pty ?: return
        viewModelScope.launch(Dispatchers.IO) {
            TerminalGestureHandler.exitTmuxCopyMode(channel)
        }
    }

    /**
     * Forward raw bytes to the currently-active PTY. Used by the
     * extra-keys toolbar and the Volume-Down=Ctrl binding.
     */
    fun writeToPty(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val active = _state.value.activeTabName ?: return
        val tab = tabs[active] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                tab.pty?.write(bytes)
                tab.moshSession?.write(bytes)
            }.onFailure { t ->
                Log.w(LOG_TAG, "pty write failed", t)
                // Best-effort UI signal: don't suspend here, just emit.
                _writeErrors.tryEmit(
                    "Send failed — connection may have dropped.",
                )
            }
        }
    }

    /** Tear everything down. Idempotent. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        cleanupQuietly()
        _state.value = _state.value.copy(
            status = TerminalUiState.Status.Disconnected,
            activeSession = null,
            activeTabName = null,
            openTabs = emptySet(),
        )
    }

    override fun onCleared() {
        cleanupQuietly()
        super.onCleared()
    }

    private fun cleanupQuietly() {
        tabs.values.forEach { tab ->
            tab.outputJob.cancel()
            runCatching { tab.pty?.close() }
            runCatching { tab.moshSession?.close() }
            notifyTabEmulatorFinished(tab.emulator)
            sessionRegistry.unregister(tab.serverIdForRegistry, tab.name)
        }
        tabs.clear()
        // Drop the hub entry before closing the session so any router
        // subscriber cancels its collection first and doesn't get a
        // transient exec failure on the way out.
        eventStreamHub.unpublish(currentServerRegistryId)
        runCatching { sshSession?.close() }
        sshSession = null
        sshClient = null
    }

    /**
     * Both [RemoteTerminalSession] and [MoshRemoteTerminalSession]
     * expose [onRemoteSessionClosed] but don't share a common base
     * method for it — the parent [TerminalSession] doesn't declare the
     * hook. A tiny when-branch keeps the call site in [detachTab] /
     * [cleanupQuietly] readable without forcing a shared mini-interface.
     */
    private fun notifyTabEmulatorFinished(emulator: TerminalSession) {
        when (emulator) {
            is RemoteTerminalSession -> emulator.onRemoteSessionClosed()
            is MoshRemoteTerminalSession -> emulator.onRemoteSessionClosed()
        }
    }

    private fun readTestKey(): ByteArray? = runCatching {
        appContext.assets.open(TEST_KEY_ASSET).use { it.readBytes() }
    }.getOrElse { t ->
        if (t is FileNotFoundException) null else throw t
    }

    /**
     * Pre-flight check: does `tmux` exist on the remote $PATH?
     */
    private suspend fun probeTmux(session: SshSession): Boolean =
        runCatching {
            session.openExec("command -v tmux").use { exec ->
                exec.stdout.collect { /* drain */ }
                exec.exitCode.await() == 0
            }
        }.getOrElse { t ->
            Log.w(LOG_TAG, "tmux probe failed; falling back to plain shell", t)
            false
        }

    private companion object {
        const val LOG_TAG = "TerminalViewModel"
        const val TEST_KEY_ASSET = "test-key.pem"
        const val ARG_SERVER_ID = "serverId"
        const val DEFAULT_TAB = "shell"
        const val INITIAL_COLS = 80
        const val INITIAL_ROWS = 24
        const val DEFAULT_FONT_SIZE_SP = 14
        const val DEFAULT_THEME_ID = "dracula"
        /**
         * Phase 3 mosh race window. If `mosh-server new` on the VPS
         * doesn't print `MOSH CONNECT <port> <key>` within this budget,
         * we abandon the UDP path and fall back to sshj. 8 seconds
         * covers a fresh VPS cold-start plus firewall pinhole-probe.
         */
        const val MOSH_HANDSHAKE_TIMEOUT_MS: Long = 8_000L

        /**
         * Stable sentinel UUID for BuildConfig test-server connections
         * that have no persisted [dev.kuch.termx.core.domain.model.Server]
         * row. Keeps the [SessionRegistry] key shape uniform.
         */
        val FALLBACK_SERVER_ID: UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
