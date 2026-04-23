package dev.kuch.termx.feature.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.RemoteTerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject

/**
 * Owns one live SSH session + shell + emulator bridge.
 *
 * Two call paths:
 *  - `serverId != null` (Phase 2+): look up the [dev.kuch.termx.core.domain.model.Server]
 *    row via [ServerRepository], pull the private bytes from [SecretVault]
 *    using the linked [dev.kuch.termx.core.domain.model.KeyPair.keystoreAlias],
 *    and connect with those credentials. Password auth is parked until
 *    Task #23 wires vault-stored passwords.
 *  - `serverId == null` (Phase 1 test path): fall back to `BuildConfig.TEST_SERVER_*`
 *    env-vars + the debug-only test key shipped at `assets/test-key.pem`.
 *
 * Hilt resolves the `serverId` via [SavedStateHandle], populated by the
 * NavGraph composable's route arg. Passing `null` from the caller (e.g.
 * a direct composable usage in Phase 1 tests) still works — the handle
 * just has no `serverId` key.
 *
 * Lifecycle:
 *  - `connect(serverId)` → `Connecting` → `Connected` | `Error`
 *  - PTY flow ends or throws → `Disconnected` | `Error`
 *  - `disconnect()` → `Disconnected`, closes channel + session
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
) : ViewModel() {

    private val _state = MutableStateFlow<TerminalUiState>(TerminalUiState.Idle)
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var sshClient: SshClient? = null
    private var sshSession: SshSession? = null
    private var ptyChannel: PtyChannel? = null
    private var remoteSession: RemoteTerminalSession? = null

    private var connectJob: Job? = null
    private var outputJob: Job? = null

    /**
     * Start a connection. Idempotent: a second call while already
     * connecting or connected is a no-op. The [serverId] overrides
     * whatever was set via [SavedStateHandle] so callers that own the
     * value explicitly don't need to stuff it into the nav route too.
     */
    fun connect(serverId: UUID?) {
        if (_state.value is TerminalUiState.Connecting || _state.value is TerminalUiState.Connected) return

        val resolvedId = serverId ?: savedStateHandle.get<String>(ARG_SERVER_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.value = TerminalUiState.Connecting
            runCatching { openSession(resolvedId) }
                .onFailure { t ->
                    Log.e(LOG_TAG, "connect failed", t)
                    cleanupQuietly()
                    _state.value = TerminalUiState.Error(t.message ?: "connection failed")
                }
        }
    }

    private suspend fun openSession(serverId: UUID?) {
        val (target, auth) = resolveConnection(serverId)

        val client = SshClient().also { sshClient = it }
        val session = client.connect(target, auth)
        sshSession = session

        val channel = session.openShell(cols = INITIAL_COLS, rows = INITIAL_ROWS)
        ptyChannel = channel

        val client2 = SshSessionClient(
            context = appContext,
            onSessionFinished = {
                // Called on the main thread (RemoteTerminalSession posts there).
                _state.value = TerminalUiState.Disconnected
                cleanupQuietly()
            },
        )
        val terminal = RemoteTerminalSession(
            client = client2,
            onInputBytes = { bytes ->
                // Keypresses + emulator-originated sequences → remote PTY stdin.
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
        remoteSession = terminal

        _state.value = TerminalUiState.Connected(terminal)

        // Collect remote bytes → emulator. Runs as long as the channel's
        // Flow is alive; completes when the shell exits or we close.
        outputJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                channel.output.collect { chunk ->
                    terminal.feedRemoteBytes(chunk)
                }
            }.onFailure { t ->
                Log.w(LOG_TAG, "pty output collector ended", t)
            }
            // Flow completion (normal or error) means the remote shell went
            // away. Let the session client route to Disconnected.
            terminal.onRemoteSessionClosed()
        }
    }

    /**
     * Decide which host + credentials to use.
     *
     * For a non-null [serverId] we resolve the Server row and (for
     * key auth) unlock the private bytes from the vault. Missing rows,
     * missing keys, or a locked vault throw — the outer
     * `runCatching` in [connect] turns them into a user-facing
     * [TerminalUiState.Error].
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
                    // Task #23 will fetch this from the vault. For now we
                    // surface a clean error instead of guessing an empty
                    // string and letting sshj report auth-failed.
                    throw IllegalStateException(
                        "Password auth isn't wired yet (Task #23).",
                    )
                }
            }
            return target to auth
        }

        // Phase 1 fallback: env-var test server + asset key.
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
     * Forward raw bytes to the current PTY. Used by the extra-keys
     * toolbar and Volume-Down=Ctrl binding to push keypresses that
     * don't flow through the Termux view's key handler. No-op if the
     * channel isn't open.
     */
    fun writeToPty(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val channel = ptyChannel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { channel.write(bytes) }
                .onFailure { Log.w(LOG_TAG, "pty write failed", it) }
        }
    }

    /** Tear everything down. Idempotent. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        outputJob?.cancel()
        outputJob = null
        remoteSession?.onRemoteSessionClosed()
        cleanupQuietly()
        if (_state.value !is TerminalUiState.Error) {
            _state.value = TerminalUiState.Disconnected
        }
    }

    override fun onCleared() {
        // viewModelScope is cancelled for us by AndroidX; close the
        // transport synchronously so sshj releases the TCP socket.
        cleanupQuietly()
        super.onCleared()
    }

    private fun cleanupQuietly() {
        runCatching { ptyChannel?.close() }
        runCatching { sshSession?.close() }
        ptyChannel = null
        sshSession = null
        sshClient = null
        remoteSession = null
    }

    private fun readTestKey(): ByteArray? = runCatching {
        appContext.assets.open(TEST_KEY_ASSET).use { it.readBytes() }
    }.getOrElse { t ->
        if (t is FileNotFoundException) null else throw t
    }

    private companion object {
        const val LOG_TAG = "TerminalViewModel"
        const val TEST_KEY_ASSET = "test-key.pem"

        /** Matches the route arg name declared by `TermxNavHost`. */
        const val ARG_SERVER_ID = "serverId"

        // Pre-resize "defaults" so the initial openShell request has *some*
        // geometry. TerminalView's onSizeChanged triggers the real resize
        // within the first layout pass.
        const val INITIAL_COLS = 80
        const val INITIAL_ROWS = 24
    }
}
