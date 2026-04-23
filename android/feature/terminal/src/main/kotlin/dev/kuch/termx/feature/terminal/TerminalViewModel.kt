package dev.kuch.termx.feature.terminal

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.RemoteTerminalSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * For Phase 1 the server picker doesn't exist yet, so a `null` `serverId`
 * means "use the test server configured via BuildConfig env vars and the
 * debug-build private key shipped at `assets/test-key.pem`". When the key
 * or env vars are missing we land in [TerminalUiState.Error] with a clear
 * message rather than a crash.
 *
 * Lifecycle:
 *  - `connect(serverId)` → `Connecting` → `Connected` | `Error`
 *  - PTY flow ends or throws → `Disconnected` | `Error`
 *  - `disconnect()` → `Disconnected`, closes channel + session
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
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
     * Start a connection. Idempotent: a second call while already connecting
     * or connected is a no-op. Use [disconnect] + [connect] to reconnect.
     */
    fun connect(serverId: UUID?) {
        if (_state.value is TerminalUiState.Connecting || _state.value is TerminalUiState.Connected) return
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.value = TerminalUiState.Connecting
            runCatching { openSession(serverId) }
                .onFailure { t ->
                    Log.e(LOG_TAG, "connect failed", t)
                    cleanupQuietly()
                    _state.value = TerminalUiState.Error(t.message ?: "connection failed")
                }
        }
    }

    private suspend fun openSession(serverId: UUID?) {
        require(serverId == null) {
            // Phase 2 (Task #21) will look the server up in Room; fail loudly
            // if anything wires a non-null ID into the Phase 1 path.
            "Phase 1 test-server path doesn't accept a serverId yet"
        }

        val host = BuildConfig.TEST_SERVER_HOST
        val user = BuildConfig.TEST_SERVER_USER
        val port = BuildConfig.TEST_SERVER_PORT
        if (host.isBlank() || user.isBlank()) {
            throw IllegalStateException("No test server configured")
        }

        val keyBytes = readTestKey()
            ?: throw IllegalStateException("No test server configured")

        val knownHostsPath = appContext.filesDir.absolutePath + "/known_hosts"
        val target = SshTarget(host = host, port = port, username = user, knownHostsPath = knownHostsPath)
        val auth = SshAuth.PublicKey(privateKeyPem = keyBytes, passphrase = null)

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

        // Pre-resize "defaults" so the initial openShell request has *some*
        // geometry. TerminalView's onSizeChanged triggers the real resize
        // within the first layout pass.
        const val INITIAL_COLS = 80
        const val INITIAL_ROWS = 24
    }
}

