package dev.kuch.termx.feature.servers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.di.KnownHostsPath
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
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
 * Password + key-auth save behaviour:
 *
 *  - Password auth: the test handshake works end-to-end, and [save] persists
 *    the password under alias `password-${id}` in [SecretVault] (UTF-8 bytes
 *    in the app's sandboxed, biometric-gated vault blob). The in-memory
 *    [PasswordCache] is seeded too so any live terminal ViewModel can reuse
 *    it without a fresh biometric prompt this session.
 *  - Key auth: the private bytes live behind [SecretVault]; pick-a-key here
 *    just stores the keypair id on the row. Test connection with auth=KEY
 *    reports a clear deferred-feature message until Task #23's key-auth test
 *    flow lands.
 */
@HiltViewModel
class AddEditServerViewModel @Inject constructor(
    @KnownHostsPath private val knownHostsPath: String,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val serverGroupRepository: ServerGroupRepository,
    private val secretVault: SecretVault,
    private val passwordCache: PasswordCache,
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
                    // Try to decrypt the stored password for password-auth
                    // rows. If the vault is locked we leave the field blank
                    // and flag the state so the UI can nudge the user to
                    // unlock before editing.
                    var password = ""
                    var vaultLocked = false
                    val alias = server.passwordAlias
                    if (server.authType == AuthType.PASSWORD && alias != null) {
                        try {
                            val bytes = secretVault.load(alias)
                            if (bytes != null) {
                                password = String(bytes, Charsets.UTF_8)
                            }
                        } catch (_: VaultLockedException) {
                            vaultLocked = true
                        } catch (t: Throwable) {
                            Log.w(LOG_TAG, "failed to load password from vault", t)
                        }
                    }
                    _state.update {
                        it.copy(
                            id = server.id,
                            label = server.label,
                            host = server.host,
                            port = server.port.toString(),
                            username = server.username,
                            authType = server.authType,
                            selectedKeyPairId = server.keyPairId,
                            password = password,
                            passwordVaultLocked = vaultLocked,
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
     * For password-auth rows with a non-blank password, writes the bytes
     * to [secretVault] under alias `password-${id}` and populates
     * [passwordCache] so any live terminal VM can reuse it without
     * re-prompting biometric. Switching back to key-auth (or clearing the
     * password field) removes the prior alias from the vault.
     *
     * Returns the row id so the caller can `onSaved(id)` and pop the sheet.
     */
    suspend fun save(): UUID {
        val s = _state.value
        val port = s.port.toIntOrNull() ?: 22
        val label = s.label.ifBlank { "${s.username}@${s.host}" }
        val id = s.id ?: UUID.randomUUID()

        // Match the domain's existing ordering (Task #19 exposes sortOrder at
        // the end of the model). Real reordering happens in Task #21.
        val existing = if (s.id != null) serverRepository.getById(s.id) else null

        val priorAlias = existing?.passwordAlias
        val shouldStorePassword = s.authType == AuthType.PASSWORD && s.password.isNotBlank()
        val authTypeFlippedAwayFromPassword = existing != null &&
            existing.authType == AuthType.PASSWORD &&
            s.authType != AuthType.PASSWORD

        // A blank password field on a PASSWORD-auth edit MUST preserve the
        // existing vault entry — the user is almost always editing an
        // unrelated field (label, port, useMosh flag) and did not retype
        // a password they already have stored. The old logic read "blank
        // + PASSWORD auth" as "clear the stored password", which nuked
        // the alias from the Room row AND deleted the vault entry. Every
        // subsequent cold start then fell back to the password prompt
        // because resolveConnection had nothing to load. Only the real
        // "forget my password" signal — flipping auth type to KEY —
        // scrubs the prior alias now.
        val alias: String? = when {
            shouldStorePassword -> "password-$id"
            s.authType == AuthType.PASSWORD -> priorAlias
            else -> null
        }

        if (shouldStorePassword) {
            try {
                secretVault.store(alias!!, s.password.toByteArray(Charsets.UTF_8))
                // Seed the in-memory cache so this session's already-live
                // terminal ViewModels can reuse the password without a
                // fresh biometric prompt on reconnect.
                passwordCache.put(id, s.password)
            } catch (t: VaultLockedException) {
                Log.w(LOG_TAG, "vault locked; falling back to in-memory cache", t)
                // Vault locked — fall back to the in-memory cache. The row
                // still persists; the user will re-prompt on next cold
                // start.
                passwordCache.put(id, s.password)
            } catch (t: Throwable) {
                // Any other Keystore / IO failure (legacy-key migration
                // edge cases, blob I/O, …) must not crash save(). Fall back
                // to the in-memory cache so the current session works; the
                // row persists and a subsequent save attempt will retry the
                // vault write.
                Log.e(LOG_TAG, "vault store failed; falling back to in-memory cache", t)
                passwordCache.put(id, s.password)
            }
        } else if (authTypeFlippedAwayFromPassword && priorAlias != null) {
            // Auth type actually flipped to KEY — scrub the orphaned
            // vault entry and the in-memory cache. A blank field on a
            // still-PASSWORD-auth row does NOT reach this branch.
            runCatching { secretVault.delete(priorAlias) }
            passwordCache.clear(id)
        }

        val server = Server(
            id = id,
            label = label,
            host = s.host,
            port = port,
            username = s.username,
            authType = s.authType,
            keyPairId = if (s.authType == AuthType.KEY) s.selectedKeyPairId else null,
            passwordAlias = alias,
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

        return id
    }

    /**
     * Remove the server row being edited plus any vault + cache state it
     * owned. No-op in add mode.
     *
     * The linked [dev.kuch.termx.core.domain.model.KeyPair] is intentionally
     * left alone — a key may be shared across several servers, so key
     * deletion is a separate operation on the Keys screen.
     */
    suspend fun delete() {
        val id = _state.value.id ?: return
        val existing = serverRepository.getById(id)
        val alias = existing?.passwordAlias
        serverRepository.delete(id)
        if (alias != null) {
            runCatching { secretVault.delete(alias) }
        }
        passwordCache.clear(id)
    }

    private companion object {
        const val LOG_TAG = "AddEditServerVM"
        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
