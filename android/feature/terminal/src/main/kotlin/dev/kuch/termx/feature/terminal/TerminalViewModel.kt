package dev.kuch.termx.feature.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.RemoteTerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.feature.terminal.BuildConfig
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import dev.kuch.termx.libs.sshnative.tmuxAutoAttachCommand
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    /**
     * One open PTY tab. [outputJob] collects the sshj byte stream into
     * [emulator] until the channel ends or we cancel it; dropping the
     * tab cancels the job and closes the channel.
     */
    private data class SessionPty(
        val name: String,
        val pty: PtyChannel,
        val emulator: RemoteTerminalSession,
        val outputJob: Job,
    )

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

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
     * password in-memory for this process's lifetime and retries
     * [connect] for the same server.
     */
    fun submitPassword(serverId: UUID, password: String) {
        passwordCache.put(serverId, password)
        _state.value = _state.value.copy(awaitingPassword = null)
        connect(serverId)
    }

    /** User cancelled the password prompt — just clear the flag. */
    fun cancelPasswordPrompt() {
        _state.value = _state.value.copy(awaitingPassword = null)
    }

    private suspend fun openSession(serverId: UUID?) {
        currentServerId = serverId
        val (target, auth) = resolveConnection(serverId)

        val client = SshClient().also { sshClient = it }
        val session = client.connect(target, auth)
        sshSession = session

        val server = serverId?.let { serverRepository.getById(it) }
        val wantsTmux = server?.autoAttachTmux == true
        val tmuxAvailable = if (wantsTmux) probeTmux(session) else true
        val initialTabName = if (wantsTmux && tmuxAvailable) server!!.tmuxSessionName else DEFAULT_TAB
        val attachCommand = if (wantsTmux && tmuxAvailable) {
            tmuxAutoAttachCommand(server!!.tmuxSessionName)
        } else null
        val tmuxMissing = wantsTmux && !tmuxAvailable

        val tab = openTab(initialTabName, attachCommand)
        _state.value = _state.value.copy(
            status = TerminalUiState.Status.Connected,
            activeSession = tab.emulator,
            activeTabName = tab.name,
            openTabs = tabs.keys.toSet(),
            tmuxMissing = tmuxMissing,
            error = null,
        )
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
            Log.w(LOG_TAG, "selectTab without live ssh session")
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
        runCatching { tab.pty.close() }
        tab.emulator.onRemoteSessionClosed()

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

        val tab = SessionPty(tabName, channel, emulator, outputJob)
        tabs[tabName] = tab
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
                    val cached = passwordCache.get(server.id)
                        ?: throw PasswordRequiredException(server.id, server.label)
                    SshAuth.Password(cached)
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
     * Forward raw bytes to the currently-active PTY. Used by the
     * extra-keys toolbar and the Volume-Down=Ctrl binding.
     */
    fun writeToPty(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val active = _state.value.activeTabName ?: return
        val channel = tabs[active]?.pty ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { channel.write(bytes) }
                .onFailure { Log.w(LOG_TAG, "pty write failed", it) }
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
            runCatching { tab.pty.close() }
            tab.emulator.onRemoteSessionClosed()
        }
        tabs.clear()
        runCatching { sshSession?.close() }
        sshSession = null
        sshClient = null
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
    }
}
