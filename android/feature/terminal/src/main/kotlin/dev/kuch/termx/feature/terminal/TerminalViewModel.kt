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
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the live SSH (or mosh) transport plus the single [PtyChannel]
 * backing this connection's plain login shell.
 *
 * Each connection is one [SshSession] feeding one [SessionPty]. Closing
 * the session via `cleanupQuietly()` implicitly closes the [PtyChannel]
 * under it; the [SessionPty] holds the [RemoteTerminalSession] +
 * emulator bound to the on-screen `TerminalView`.
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
     * The single open shell. [outputJob] collects the transport's byte
     * stream into [emulator] until the channel ends or we cancel it;
     * tearing the session down cancels the job and closes the transport
     * handle.
     *
     * Either [pty] (sshj PTY) or [moshSession] (local mosh-client
     * process) is non-null — never both, never neither.
     *
     * [serverIdForRegistry] + [serverLabel] mirror what we pushed into
     * [SessionRegistry] on open so the matching unregister call can be
     * made from [cleanupQuietly] without another repo hit.
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
     * Currently-selected terminal font size in sp. Read eagerly so the
     * first composition of [dev.kuch.termx.feature.terminal.TerminalScreen]
     * doesn't flicker from the Room/DataStore default of 14.
     */
    val fontSizeSp: StateFlow<Int> = appPreferences.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_SIZE_SP)

    private var currentServerId: UUID? = null
    /**
     * Cached display label + stable UUID for the active connection, used
     * to tag the session pushed into [SessionRegistry]. The stable UUID is
     * the persisted server id when we have one, or [FALLBACK_SERVER_ID]
     * for the BuildConfig test-server path — either way the registry
     * (and the service's notification) sees a consistent key.
     */
    private var currentServerLabel: String = "termx"
    private var currentServerRegistryId: UUID = FALLBACK_SERVER_ID
    private var sshClient: SshClient? = null
    private var sshSession: SshSession? = null

    /** The single open shell for this connection, or null when down. */
    private var activeShell: SessionPty? = null

    private var connectJob: Job? = null

    /**
     * Start a connection. Idempotent across Connecting/Connected. Sets
     * up the [SshSession] and opens a single plain login shell.
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

        // Optional "run a command on connect" — same wire string for both
        // transports (SSH execs it with a PTY; the mosh path appends it
        // after `mosh-server new … --`). null when disabled/blank, which
        // falls back to a plain login shell on either path.
        val startup = buildStartupCommand(
            server?.startupCommandEnabled == true,
            server?.startupCommand ?: "",
        )

        // Phase 3 mosh race. If the Server row opts into mosh, try the
        // mosh-server handshake first with an 8s cap; if that returns
        // null (server missing, UDP blocked, timeout), fall through to
        // the sshj PTY path below.
        if (server?.useMosh == true) {
            val mosh = runCatching {
                moshClient.tryConnect(
                    target = target,
                    auth = auth,
                    handshakeTimeoutMs = MOSH_HANDSHAKE_TIMEOUT_MS,
                    startupCommand = startup,
                )
            }.onFailure { Log.w(LOG_TAG, "mosh tryConnect threw", it) }
                .getOrNull()

            if (mosh != null) {
                val shell = openMoshTab(mosh)
                touchLastConnected(server.id)
                _state.value = _state.value.copy(
                    status = TerminalUiState.Status.Connected,
                    activeSession = shell.emulator,
                    moshBacked = true,
                    error = null,
                )
                return
            }
            Log.i(LOG_TAG, "mosh handshake did not complete in ${MOSH_HANDSHAKE_TIMEOUT_MS}ms; falling back to ssh")
        }

        val client = SshClient().also { sshClient = it }
        val sshSession = client.connect(target, auth)
        this.sshSession = sshSession

        // Publish the live session so `EventNotificationRouter` (and any
        // future consumer) can tail `events.ndjson` without coordinating
        // with this VM directly. Re-publishing is idempotent — hub state
        // is keyed by server id so reconnecting replaces the prior entry.
        eventStreamHub.publish(
            serverId = currentServerRegistryId,
            serverLabel = currentServerLabel,
            client = eventStreamRepository.clientFor(sshSession),
        )

        val shell = openTab(attachCommand = startup)
        server?.id?.let { touchLastConnected(it) }
        _state.value = _state.value.copy(
            status = TerminalUiState.Status.Connected,
            activeSession = shell.emulator,
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
     * The remote shell exited (user typed `exit`, transport dropped).
     * Tear down the single session and transition to Disconnected so the
     * UI can prompt a reconnect. Idempotent — a late EOF after an
     * explicit [disconnect] finds no [activeShell] and no-ops.
     */
    private fun onShellFinished() {
        val shell = activeShell ?: return
        activeShell = null
        shell.outputJob.cancel()
        runCatching { shell.pty?.close() }
        runCatching { shell.moshSession?.close() }
        notifyTabEmulatorFinished(shell.emulator)
        sessionRegistry.unregister(shell.serverIdForRegistry, shell.name)
        _state.value = _state.value.copy(
            activeSession = null,
            status = TerminalUiState.Status.Disconnected,
        )
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
     * Open the shell PTY, wiring bytes in both directions into a fresh
     * [RemoteTerminalSession], and stash it as [activeShell]. Caller
     * updates UI state. [attachCommand] is the optional remote command
     * to exec instead of the login shell — always null on the plain
     * shell path today.
     */
    private suspend fun openTab(attachCommand: String?): SessionPty {
        val sshSession = sshSession ?: throw IllegalStateException("No live ssh session")
        val channel = sshSession.openShell(
            cols = INITIAL_COLS,
            rows = INITIAL_ROWS,
            command = attachCommand,
        )

        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = {
                // Remote shell exited (user typed `exit`, transport
                // dropped). Tear the session down.
                onShellFinished()
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
            }.onFailure { t -> Log.w(LOG_TAG, "pty output ended", t) }
            emulator.onRemoteSessionClosed()
        }

        val shell = SessionPty(
            name = DEFAULT_TAB,
            pty = channel,
            moshSession = null,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = currentServerRegistryId,
            serverLabel = currentServerLabel,
        )
        activeShell = shell
        sessionRegistry.register(
            serverId = shell.serverIdForRegistry,
            serverLabel = shell.serverLabel,
            tabName = shell.name,
        )
        return shell
    }

    /**
     * Wire a live [MoshSession] into the single shell.
     *
     * Resize + keypress bytes go straight into mosh-client's stdin /
     * SIGWINCH path. Output is piped from the child process stdout
     * into the emulator by [outputJob].
     */
    private fun openMoshTab(session: MoshSession): SessionPty {
        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = {
                onShellFinished()
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
            }.onFailure { t -> Log.w(LOG_TAG, "mosh output ended", t) }
            emulator.onRemoteSessionClosed()
            // mosh-client stdout EOF ⇒ the child process either exited
            // cleanly (user typed `exit` on the remote) or died during
            // startup. Distinguish via the diagnostic snapshot: a
            // non-zero code within the early-exit window means we
            // should tell the user *why* rather than silently dropping
            // them on a useless Reconnect button.
            val diag = session.diagnostic.value
            if (diag.exitCode != null && diag.exitCode != 0 && diag.elapsedMs < MOSH_EARLY_EXIT_WINDOW_MS) {
                val reason = extractReadableReason(diag.head)
                _state.value = _state.value.copy(
                    status = TerminalUiState.Status.Disconnected,
                    error = "mosh-client exited after ${diag.elapsedMs}ms (exit ${diag.exitCode}): $reason",
                    activeSession = null,
                )
            }
        }

        val shell = SessionPty(
            name = DEFAULT_TAB,
            pty = null,
            moshSession = session,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = currentServerRegistryId,
            serverLabel = currentServerLabel,
        )
        activeShell = shell
        sessionRegistry.register(
            serverId = shell.serverIdForRegistry,
            serverLabel = shell.serverLabel,
            tabName = shell.name,
        )
        return shell
    }

    /**
     * Pull the most useful single line out of mosh-client's captured
     * stderr head. ncurses prints `Error opening terminal: ...` or the
     * classic `Cannot find termcap entry for ...`; mosh itself prints
     * things like `mosh-client: ...`. We prefer a line matching any of
     * those markers, falling back to the first non-empty line, then to
     * a size-capped flattened form.
     */
    private fun extractReadableReason(head: String): String {
        if (head.isBlank()) return "no output captured"
        val lines = head.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val preferred = lines.firstOrNull { line ->
            line.contains("termcap", ignoreCase = true) ||
                line.contains("terminal", ignoreCase = true) ||
                line.startsWith("mosh", ignoreCase = true) ||
                line.contains("error", ignoreCase = true)
        }
        val best = preferred ?: lines.firstOrNull() ?: head.replace('\n', ' ').trim()
        return best.take(200)
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
     * Forward raw bytes to the shell PTY. Used by the extra-keys
     * toolbar and the Volume-Down=Ctrl binding.
     */
    fun writeToPty(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val shell = activeShell ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                shell.pty?.write(bytes)
                shell.moshSession?.write(bytes)
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
        )
    }

    override fun onCleared() {
        cleanupQuietly()
        super.onCleared()
    }

    private fun cleanupQuietly() {
        activeShell?.let { shell ->
            shell.outputJob.cancel()
            runCatching { shell.pty?.close() }
            runCatching { shell.moshSession?.close() }
            notifyTabEmulatorFinished(shell.emulator)
            sessionRegistry.unregister(shell.serverIdForRegistry, shell.name)
        }
        activeShell = null
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
     * hook. A tiny when-branch keeps the call site in [onShellFinished] /
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

    private companion object {
        const val LOG_TAG = "TerminalViewModel"
        const val TEST_KEY_ASSET = "test-key.pem"
        const val ARG_SERVER_ID = "serverId"
        const val DEFAULT_TAB = "shell"
        const val INITIAL_COLS = 80
        const val INITIAL_ROWS = 24
        const val DEFAULT_FONT_SIZE_SP = 14
        /**
         * Phase 3 mosh race window. If `mosh-server new` on the VPS
         * doesn't print `MOSH CONNECT <port> <key>` within this budget,
         * we abandon the UDP path and fall back to sshj. 8 seconds
         * covers a fresh VPS cold-start plus firewall pinhole-probe.
         */
        const val MOSH_HANDSHAKE_TIMEOUT_MS: Long = 8_000L

        /**
         * Window within which a mosh-client exit is treated as a
         * startup failure rather than a normal user-initiated exit.
         * Matches the companion in `MoshSessionImpl` so the log and
         * the UI post-mortem agree on what counts as "immediate".
         */
        const val MOSH_EARLY_EXIT_WINDOW_MS: Long = 2_000L

        /**
         * Stable sentinel UUID for BuildConfig test-server connections
         * that have no persisted [dev.kuch.termx.core.domain.model.Server]
         * row. Keeps the [SessionRegistry] key shape uniform.
         */
        val FALLBACK_SERVER_ID: UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
