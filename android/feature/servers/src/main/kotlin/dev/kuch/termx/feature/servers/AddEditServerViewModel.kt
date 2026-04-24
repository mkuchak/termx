package dev.kuch.termx.feature.servers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.di.KnownHostsPath
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshException
import dev.kuch.termx.libs.sshnative.SshTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * Drives [AddEditServerSheet].
 *
 * Responsibilities:
 *  - Load the existing [Server] when the caller passes a non-null id (edit mode).
 *  - Stream the lists of [dev.kuch.termx.core.domain.model.KeyPair] and
 *    [dev.kuch.termx.core.domain.model.ServerGroup] for the two dropdowns.
 *  - Run a live SSH handshake on demand and map [SshException] subclasses to
 *    human strings for [TestResult.Error].
 *  - Build a [Server] from the form and upsert it via [ServerRepository].
 *
 * Password + key-auth test connection caveats:
 *
 *  - Password auth: the test handshake works end-to-end (password is held in
 *    memory only), but the password is NOT persisted on save — Task #23 lands
 *    that after [dev.kuch.termx.core.data.vault.SecretVault] is wired.
 *  - Key auth: the private bytes live behind
 *    [dev.kuch.termx.core.data.vault.SecretVault], which Task #20 wires into
 *    the graph. Until that lands, tapping "Test connection" with auth=KEY
 *    reports a clear deferred-feature message instead of silently passing or
 *    failing with a misleading auth-rejected error.
 */
@HiltViewModel
class AddEditServerViewModel @Inject constructor(
    @KnownHostsPath private val knownHostsPath: String,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val serverGroupRepository: ServerGroupRepository,
    private val sshClient: SshClient,
) : ViewModel() {

    private val _state = MutableStateFlow(AddEditServerUiState())
    val state: StateFlow<AddEditServerUiState> = _state.asStateFlow()

    /**
     * Populate the form. Call once from the composable's `LaunchedEffect(id)`.
     *
     * [serverId] == null → add mode (form stays at defaults).
     * [serverId] != null → edit mode (repository lookup, fields pre-filled).
     */
    fun initialize(serverId: UUID?) {
        _state.update { it.copy(id = serverId, isLoading = serverId != null) }

        // Stream the dropdown sources.
        viewModelScope.launch {
            combine(
                keyPairRepository.observeAll(),
                serverGroupRepository.observeAll(),
            ) { keys, groups -> keys to groups }
                .collect { (keys, groups) ->
                    _state.update {
                        it.copy(availableKeys = keys, availableGroups = groups)
                    }
                }
        }

        if (serverId != null) {
            viewModelScope.launch {
                val server = serverRepository.getById(serverId)
                if (server != null) {
                    _state.update {
                        it.copy(
                            id = server.id,
                            label = server.label,
                            host = server.host,
                            port = server.port.toString(),
                            username = server.username,
                            authType = server.authType,
                            selectedKeyPairId = server.keyPairId,
                            selectedGroupId = server.groupId,
                            useMosh = server.useMosh,
                            autoAttachTmux = server.autoAttachTmux,
                            tmuxSessionName = server.tmuxSessionName,
                            isLoading = false,
                        )
                    }
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    // --- Field change handlers -------------------------------------------------

    fun onLabelChange(v: String) = mutate { copy(label = v, testResult = TestResult.Idle) }
    fun onHostChange(v: String) = mutate { copy(host = v.trim(), testResult = TestResult.Idle) }
    fun onPortChange(v: String) = mutate {
        // Only accept digits so we don't have to re-validate at save time.
        val digits = v.filter { it.isDigit() }.take(5)
        copy(port = digits, testResult = TestResult.Idle)
    }
    fun onUsernameChange(v: String) = mutate { copy(username = v.trim(), testResult = TestResult.Idle) }
    fun onAuthTypeChange(v: AuthType) = mutate { copy(authType = v, testResult = TestResult.Idle) }
    fun onKeyPairSelected(id: UUID?) = mutate { copy(selectedKeyPairId = id, testResult = TestResult.Idle) }
    fun onPasswordChange(v: String) = mutate { copy(password = v, testResult = TestResult.Idle) }
    fun onPasswordVisibilityToggle() = mutate { copy(passwordVisible = !passwordVisible) }
    fun onUseMoshChange(v: Boolean) = mutate { copy(useMosh = v) }
    fun onAutoAttachTmuxChange(v: Boolean) = mutate { copy(autoAttachTmux = v) }
    fun onTmuxSessionNameChange(v: String) = mutate { copy(tmuxSessionName = v) }
    fun onGroupSelected(id: UUID?) = mutate { copy(selectedGroupId = id) }

    private inline fun mutate(block: AddEditServerUiState.() -> AddEditServerUiState) {
        _state.update { it.block() }
    }

    // --- Actions ---------------------------------------------------------------

    /**
     * Fire an SSH handshake with the current form values.
     *
     * Emits [TestResult.Running] → [TestResult.Success] or [TestResult.Error].
     * Never throws. Wraps the whole connect in a 10 s [withTimeoutOrNull].
     */
    fun testConnection() {
        val snapshot = _state.value
        if (!snapshot.canTestConnection) return

        _state.update { it.copy(testResult = TestResult.Running) }

        viewModelScope.launch {
            val result = runTestConnection(snapshot)
            _state.update { it.copy(testResult = result) }
        }
    }

    private suspend fun runTestConnection(s: AddEditServerUiState): TestResult {
        val port = s.port.toIntOrNull() ?: return TestResult.Error("Port must be a number between 1 and 65535.")
        if (port !in 1..65535) return TestResult.Error("Port must be between 1 and 65535.")

        val auth: SshAuth = when (s.authType) {
            AuthType.PASSWORD -> SshAuth.Password(s.password)
            AuthType.KEY -> {
                // Loading the private bytes requires SecretVault from Task #20
                // plus the key-import/generation flow from Task #23. Neither
                // is guaranteed to be wired when this task ships, so surface
                // a clear deferred-feature message instead of handing sshj a
                // zero-byte key (which would report "authentication failed"
                // and mislead the user).
                return TestResult.Error(
                    "Key-auth test is coming in Task #23 once the vault is wired. " +
                        "The server row will still save — test with password auth for now.",
                )
            }
        }

        val target = SshTarget(
            host = s.host,
            port = port,
            username = s.username,
            knownHostsPath = knownHostsPath,
        )

        return try {
            val session = withTimeoutOrNull(TEST_TIMEOUT_MILLIS) {
                withContext(Dispatchers.IO) {
                    sshClient.connect(target, auth, timeoutMillis = TEST_TIMEOUT_MILLIS)
                }
            }
            if (session == null) {
                TestResult.Error("Timed out after 10s.")
            } else {
                runCatching { withContext(Dispatchers.IO) { session.close() } }
                TestResult.Success
            }
        } catch (t: SshException.AuthFailed) {
            TestResult.Error("Authentication failed. Wrong key or username?")
        } catch (t: SshException.HostUnreachable) {
            TestResult.Error("Host not reachable. Check the address or your network.")
        } catch (t: SshException.HostKeyMismatch) {
            TestResult.Error("Host key changed. Possible MITM. Review manually.")
        } catch (t: SshException.TimedOut) {
            TestResult.Error("Timed out after 10s.")
        } catch (t: SshException.ChannelClosed) {
            TestResult.Error("Server closed the connection.")
        } catch (t: SshException.Unknown) {
            TestResult.Error("Unexpected error: ${t.message ?: "unknown"}")
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "test connection failed", t)
            TestResult.Error("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Persist the current form as a new or updated [Server] row.
     *
     * Secrets are NOT written here — password storage is deferred to Task #23
     * (needs [dev.kuch.termx.core.data.vault.SecretVault]). Returns the row id
     * so the caller can `onSaved(id)` and pop the sheet.
     */
    suspend fun save(): UUID {
        val s = _state.value
        val port = s.port.toIntOrNull() ?: 22
        val label = s.label.ifBlank { "${s.username}@${s.host}" }
        val id = s.id ?: UUID.randomUUID()

        // Match the domain's existing ordering (Task #19 exposes sortOrder at
        // the end of the model). Real reordering happens in Task #21.
        val existing = if (s.id != null) serverRepository.getById(s.id) else null

        val server = Server(
            id = id,
            label = label,
            host = s.host,
            port = port,
            username = s.username,
            authType = s.authType,
            keyPairId = if (s.authType == AuthType.KEY) s.selectedKeyPairId else null,
            groupId = s.selectedGroupId,
            useMosh = s.useMosh,
            autoAttachTmux = s.autoAttachTmux,
            tmuxSessionName = s.tmuxSessionName.ifBlank { "main" },
            lastConnected = existing?.lastConnected,
            pingMs = existing?.pingMs,
            sortOrder = existing?.sortOrder ?: 0,
            companionInstalled = existing?.companionInstalled ?: false,
        )

        serverRepository.upsert(server)

        // TODO(#23): when SecretVault lands, persist s.password here for
        //   password-auth rows. For now, the password only survives as long
        //   as the form is open — see the notice in AddEditServerSheet.

        return id
    }

    /** Remove the server row being edited. No-op in add mode. */
    suspend fun delete() {
        val id = _state.value.id ?: return
        serverRepository.delete(id)
    }

    private companion object {
        const val LOG_TAG = "AddEditServerVM"
        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
