package dev.kuch.termx.feature.servers.setup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.di.KnownHostsPath
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import dev.kuch.termx.feature.servers.TestResult
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshException
import dev.kuch.termx.libs.sshnative.SshTarget
import dev.kuch.termx.libs.sshnative.crypto.SshKeyPairGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Drives the 5-step Setup Wizard.
 *
 * The wizard mirrors the fields from [dev.kuch.termx.feature.servers.AddEditServerSheet]
 * but paces them over dedicated steps with forward validation. It also owns
 * two side effects:
 *
 *  - **Test connection** (step 2): reuses the same sshj handshake pattern as
 *    `AddEditServerViewModel`, but requires the test to succeed before `next()`
 *    advances to step 3.
 *  - **Inline key generation** (step 1): a convenience path so a first-time
 *    user never has to leave the wizard to create a fresh Ed25519 key. This
 *    is handled directly in the viewmodel (see [generateAndSelectKey]) rather
 *    than navigating to `keys/generate` and plumbing a `SavedStateHandle`
 *    return payload — keeping the state machine single-owner.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    @KnownHostsPath private val knownHostsPath: String,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val serverGroupRepository: ServerGroupRepository,
    private val secretVault: SecretVault,
    private val vaultLockState: VaultLockState,
    private val installCompanion: InstallCompanionUseCase,
    private val passwordCache: dev.kuch.termx.core.data.prefs.PasswordCache,
    private val sshClient: SshClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupWizardUiState())
    val state: StateFlow<SetupWizardUiState> = _state.asStateFlow()

    private val _installStep3State = MutableStateFlow<InstallStep3State>(InstallStep3State.Detecting)

    /**
     * State feed for step 3. Transitions through detect to ReadyToDownload
     * (or AlreadyInstalled) on step enter, then Preview/Install on user taps.
     */
    val installStep3State: StateFlow<InstallStep3State> = _installStep3State.asStateFlow()

    /** In-flight install job (detect/preview/install stages). Cancelled on retry. */
    private var installJob: Job? = null

    /** Cached from the ReadyToDownload transition so Preview can pick it up. */
    private var cachedDownloadUrl: String? = null

    init {
        // Stream the key and group lists so step 1's dropdowns stay live
        // with anything the user has pre-created outside the wizard.
        viewModelScope.launch {
            combine(
                keyPairRepository.observeAll(),
                serverGroupRepository.observeAll(),
            ) { keys, groups -> keys to groups }
                .collect { (keys, groups) ->
                    _state.update { it.copy(availableKeys = keys, availableGroups = groups) }
                }
        }
        viewModelScope.launch {
            vaultLockState.state.collect { s ->
                _state.update { it.copy(vaultLocked = s != VaultLockState.State.Unlocked) }
            }
        }
    }

    // --- Field changes --------------------------------------------------------

    fun onLabelChange(v: String) = mutate { copy(draft = draft.copy(label = v)) }
    fun onHostChange(v: String) = mutateResetTest { copy(draft = draft.copy(host = v.trim())) }
    fun onPortChange(v: String) = mutateResetTest {
        val digits = v.filter { it.isDigit() }.take(5)
        copy(draft = draft.copy(port = digits))
    }
    fun onUsernameChange(v: String) = mutateResetTest { copy(draft = draft.copy(username = v.trim())) }
    fun onAuthTypeChange(v: AuthType) = mutateResetTest { copy(draft = draft.copy(authType = v)) }
    fun onKeyPairSelected(id: UUID?) = mutateResetTest { copy(draft = draft.copy(keyPairId = id)) }
    fun onPasswordChange(v: String) = mutateResetTest { copy(draft = draft.copy(password = v)) }
    fun onUseMoshChange(v: Boolean) = mutate { copy(draft = draft.copy(useMosh = v)) }
    fun onAutoAttachTmuxChange(v: Boolean) = mutate { copy(draft = draft.copy(autoAttachTmux = v)) }
    fun onTmuxSessionNameChange(v: String) = mutate { copy(draft = draft.copy(tmuxSessionName = v)) }
    fun onGroupSelected(id: UUID?) = mutate { copy(draft = draft.copy(groupId = id)) }

    private inline fun mutate(block: SetupWizardUiState.() -> SetupWizardUiState) {
        _state.update { it.block() }
    }

    private inline fun mutateResetTest(block: SetupWizardUiState.() -> SetupWizardUiState) {
        _state.update { it.block().copy(testResult = TestResult.Idle) }
    }

    // --- Navigation -----------------------------------------------------------

    fun next() {
        val snapshot = _state.value
        when (snapshot.currentStep) {
            1 -> if (snapshot.canAdvanceFromStep1) _state.update { it.copy(currentStep = 2) }
            2 -> if (snapshot.testResult is TestResult.Success) {
                _state.update { it.copy(currentStep = 3) }
                // Persist a row now so the install flow has a real serverId to
                // open a session against. The row stays in Room even if the
                // wizard is abandoned — acceptable trade-off vs. plumbing a
                // synthetic id through InstallCompanionUseCase.
                //
                // Serialize the upsert with the detect call: [ensureDraftPersisted]
                // used to launch its own coroutine which let [runCompanionDetect]
                // fire a `serverRepository.getById` before Room had committed,
                // producing a one-shot "Server not found: <uuid>" on first entry
                // into Step 3.
                viewModelScope.launch {
                    ensureDraftPersisted()
                    runCompanionDetect()
                }
            }
            3 -> _state.update { it.copy(currentStep = 4) }
            4 -> Unit // handled by save()
            5 -> Unit // handled by onDone from the composable
            else -> Unit
        }
    }

    fun back() {
        _state.update { s ->
            if (s.currentStep > 1) s.copy(currentStep = s.currentStep - 1) else s
        }
    }

    /** Jump directly to step 1 from step 4's Edit row icons. */
    fun jumpToStep1() = _state.update { it.copy(currentStep = 1) }

    fun requestExit() = _state.update { it.copy(showExitConfirm = true) }

    fun dismissExit() = _state.update { it.copy(showExitConfirm = false) }

    // --- Step 2: test connection ---------------------------------------------

    fun runTest() {
        val snapshot = _state.value
        if (!snapshot.canTestConnection) return
        _state.update { it.copy(testResult = TestResult.Running) }

        viewModelScope.launch {
            val result = runTestConnection(snapshot)
            _state.update { it.copy(testResult = result) }
        }
    }

    private suspend fun runTestConnection(s: SetupWizardUiState): TestResult {
        val port = s.draft.port.toIntOrNull()
            ?: return TestResult.Error("Port must be a number between 1 and 65535.")
        if (port !in 1..65535) return TestResult.Error("Port must be between 1 and 65535.")

        val auth: SshAuth = when (s.draft.authType) {
            AuthType.PASSWORD -> SshAuth.Password(s.draft.password)
            AuthType.KEY -> {
                val keyId = s.draft.keyPairId
                    ?: return TestResult.Error("Pick a key before testing.")
                val keyPair = keyPairRepository.getById(keyId)
                    ?: return TestResult.Error("Selected key not found.")
                val privatePem = runCatching { secretVault.load(keyPair.keystoreAlias) }
                    .getOrElse { t ->
                        return if (t is VaultLockedException) {
                            TestResult.Error("Vault is locked — unlock the app and retry.")
                        } else {
                            TestResult.Error("Could not read key from vault: ${t.message ?: t.javaClass.simpleName}")
                        }
                    }
                if (privatePem == null) {
                    return TestResult.Error("Key vault entry is missing.")
                }
                SshAuth.PublicKey(privateKeyPem = privatePem, passphrase = null)
            }
        }

        val target = SshTarget(
            host = s.draft.host,
            port = port,
            username = s.draft.username,
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
            Log.w(LOG_TAG, "wizard test connection failed", t)
            TestResult.Error("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // --- Step 4: persist ------------------------------------------------------

    /**
     * Insert the row and transition the wizard.
     *
     * For key-auth rows we jump to step 5 so the user can copy the public key
     * into the VPS's `authorized_keys`. Password-auth skips directly to done —
     * there's no public key to share. The caller's `onDone(savedServerId)`
     * lambda is invoked in both cases once the state has settled.
     */
    fun save(onDoneIfPasswordAuth: (UUID) -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            val id = s.savedServerId ?: UUID.randomUUID()
            // Preserve any install-side updates (companionInstalled) already
            // persisted by [runCompanionInstall] — re-upserting the raw draft
            // would clobber that flag back to false.
            val existing = serverRepository.getById(id)

            // Persist the SSH password to the Keystore-backed vault under
            // alias `password-${id}` — mirrors `AddEditServerViewModel.save`.
            // The in-memory cache was already seeded in
            // `ensureDraftPersisted` but users who jump straight to step 4
            // without walking through the detect step still need it set.
            val shouldStorePassword = s.draft.authType == AuthType.PASSWORD &&
                s.draft.password.isNotBlank()
            val alias: String? = if (shouldStorePassword) "password-$id" else null
            if (shouldStorePassword) {
                try {
                    secretVault.store(alias!!, s.draft.password.toByteArray(Charsets.UTF_8))
                } catch (t: VaultLockedException) {
                    Log.w(LOG_TAG, "vault locked; password survives in-memory only", t)
                }
                passwordCache.put(id, s.draft.password)
            } else {
                val priorAlias = existing?.passwordAlias
                if (priorAlias != null) {
                    runCatching { secretVault.delete(priorAlias) }
                }
            }

            val server = s.draft.toServer(id).copy(
                companionInstalled = existing?.companionInstalled == true,
                passwordAlias = alias,
            )
            serverRepository.upsert(server)
            _state.update { it.copy(savedServerId = id) }

            if (s.draft.authType == AuthType.PASSWORD) {
                // Row + vault entry are live; hand control back to the list.
                onDoneIfPasswordAuth(id)
            } else {
                _state.update { it.copy(currentStep = 5) }
            }
        }
    }

    // --- Step 3: companion install -------------------------------------------

    /**
     * Save the draft as a [Server] row so [installCompanion] has a stable
     * `serverId` to resolve during the detect/preview/install stages. No-op
     * when a row already exists (e.g. the user stepped back and forward).
     *
     * Declared `suspend` so the caller can serialize it with the follow-up
     * `installCompanion.run(...)` — an earlier version launched the upsert
     * on its own coroutine and let Step 3's `runCompanionDetect` read the
     * row before Room committed, producing a one-shot "Server not found:
     * <uuid>" error on first entry into Step 3.
     */
    private suspend fun ensureDraftPersisted() {
        val current = _state.value
        if (current.savedServerId != null) return
        val id = UUID.randomUUID()
        val server = current.draft.toServer(id)
        _state.update { it.copy(savedServerId = id) }
        // Seed the in-memory password cache so the subsequent install +
        // terminal-connect flows don't demand the user re-type it.
        if (current.draft.authType == AuthType.PASSWORD && current.draft.password.isNotBlank()) {
            passwordCache.put(id, current.draft.password)
        }
        runCatching { serverRepository.upsert(server) }
    }

    /**
     * Pull the one-shot password the user typed in Step 1 when the draft
     * uses password auth. `null` otherwise — `Server` doesn't persist
     * passwords so the install use-case has no other source, and key-auth
     * servers don't need one.
     */
    private fun passwordOverrideForInstall(): String? {
        val draft = _state.value.draft
        return draft.password.takeIf { draft.authType == AuthType.PASSWORD && it.isNotBlank() }
    }

    /** Launch detect: probes `termx --version`, then hits GitHub for the asset. */
    fun runCompanionDetect() {
        val id = _state.value.savedServerId ?: return
        cachedDownloadUrl = null
        installJob?.cancel()
        installJob = viewModelScope.launch {
            installCompanion.run(
                id,
                InstallCompanionUseCase.Stage.Detect,
                InstallCompanionUseCase.Context(passwordOverride = passwordOverrideForInstall()),
            ).collect { state ->
                if (state is InstallStep3State.ReadyToDownload) {
                    cachedDownloadUrl = state.downloadUrl
                }
                _installStep3State.value = state
            }
        }
    }

    /** User tapped [Download + Preview]: download binary and parse dry-run JSON. */
    fun runCompanionPreview() {
        val id = _state.value.savedServerId ?: return
        val url = cachedDownloadUrl ?: run {
            _installStep3State.value = InstallStep3State.Error("No download URL cached; retry detection.")
            return
        }
        installJob?.cancel()
        installJob = viewModelScope.launch {
            installCompanion
                .run(
                    id,
                    InstallCompanionUseCase.Stage.Preview,
                    InstallCompanionUseCase.Context(
                        downloadUrl = url,
                        passwordOverride = passwordOverrideForInstall(),
                    ),
                )
                .collect { _installStep3State.value = it }
        }
    }

    /** User tapped [Install]: run the non-dry-run install, stream log lines. */
    fun runCompanionInstall() {
        val id = _state.value.savedServerId ?: return
        installJob?.cancel()
        installJob = viewModelScope.launch {
            installCompanion.run(
                id,
                InstallCompanionUseCase.Stage.Install,
                InstallCompanionUseCase.Context(passwordOverride = passwordOverrideForInstall()),
            ).collect {
                _installStep3State.value = it
            }
        }
    }

    /** [Next] on Success / [Skip] on any other state — advance to step 4. */
    fun advanceFromCompanion() {
        installJob?.cancel()
        _state.update { it.copy(currentStep = 4) }
    }

    // --- Inline key generation (chosen over navigation round-trip) ----------

    /**
     * Generate a fresh Ed25519 key and auto-select it on the draft.
     *
     * Chosen over a `keys/generate` navigation hop plus `SavedStateHandle`
     * return payload — embedding the generator directly here keeps the wizard
     * state single-owner and avoids losing the wizard back stack when Android
     * re-creates the destination. See [SetupWizardViewModel] class docs.
     */
    fun generateAndSelectKey(label: String, comment: String = "") {
        if (_state.value.isGeneratingKey) return
        if (_state.value.vaultLocked) {
            _state.update { it.copy(keyGenError = "Unlock the vault and try again.") }
            return
        }
        if (label.isBlank()) {
            _state.update { it.copy(keyGenError = "Label is required.") }
            return
        }
        _state.update { it.copy(isGeneratingKey = true, keyGenError = null) }

        viewModelScope.launch {
            val result = runCatching {
                val generated = withContext(Dispatchers.Default) {
                    SshKeyPairGenerator.ed25519(comment.ifBlank { "termx-$label" })
                }
                val id = UUID.randomUUID()
                val alias = "key-$id-private"
                secretVault.store(alias, generated.privatePem)
                generated.privatePem.fill(0)

                val row = KeyPair(
                    id = id,
                    label = label.trim(),
                    algorithm = KeyAlgorithm.ED25519,
                    publicKey = generated.publicOpenSsh,
                    keystoreAlias = alias,
                    createdAt = Instant.now(),
                )
                keyPairRepository.insert(row)
                id
            }

            result
                .onSuccess { id ->
                    _state.update {
                        it.copy(
                            isGeneratingKey = false,
                            keyGenError = null,
                            draft = it.draft.copy(keyPairId = id, authType = AuthType.KEY),
                            testResult = TestResult.Idle,
                        )
                    }
                }
                .onFailure { t ->
                    val message = when (t) {
                        is VaultLockedException -> "Vault is locked. Unlock and try again."
                        else -> t.message ?: "Key generation failed."
                    }
                    _state.update { it.copy(isGeneratingKey = false, keyGenError = message) }
                }
        }
    }

    fun dismissKeyGenError() = _state.update { it.copy(keyGenError = null) }

    private companion object {
        const val LOG_TAG = "SetupWizardVM"
        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * In-memory draft of the soon-to-be [Server] row.
 *
 * Port is a String so text-field editing is 1:1 — [toServer] coerces. Label
 * falls back to `"user@host"` at persist time so the empty-label placeholder
 * never leaks into Room.
 */
data class ServerDraft(
    val label: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.KEY,
    val keyPairId: UUID? = null,
    val password: String = "",
    val useMosh: Boolean = true,
    val autoAttachTmux: Boolean = true,
    val tmuxSessionName: String = "main",
    val groupId: UUID? = null,
) {
    fun toServer(id: UUID = UUID.randomUUID()): Server = Server(
        id = id,
        label = label.ifBlank { "$username@$host" },
        host = host,
        port = port.toIntOrNull() ?: 22,
        username = username,
        authType = authType,
        keyPairId = if (authType == AuthType.KEY) keyPairId else null,
        groupId = groupId,
        useMosh = useMosh,
        autoAttachTmux = autoAttachTmux,
        tmuxSessionName = tmuxSessionName.ifBlank { "main" },
        lastConnected = null,
        pingMs = null,
        sortOrder = Int.MAX_VALUE,
        companionInstalled = false,
    )
}

/**
 * Top-level state for the 5-step wizard.
 *
 * - [currentStep] is 1-based (1..5) so the TopAppBar can render "Step X of 5"
 *   without arithmetic.
 * - [savedServerId] is set on step-4 Save; step 5 uses it to key the
 *   public-key share affordance and the [onDone] return value.
 * - [showExitConfirm] toggled by the exit X; the screen renders an
 *   [androidx.compose.material3.AlertDialog] when true.
 */
data class SetupWizardUiState(
    val currentStep: Int = 1,
    val draft: ServerDraft = ServerDraft(),
    val testResult: TestResult = TestResult.Idle,
    val savedServerId: UUID? = null,
    val showExitConfirm: Boolean = false,
    val availableKeys: List<KeyPair> = emptyList(),
    val availableGroups: List<ServerGroup> = emptyList(),
    val vaultLocked: Boolean = false,
    val isGeneratingKey: Boolean = false,
    val keyGenError: String? = null,
) {
    /** Minimal fields required before step 1 will advance. */
    val canAdvanceFromStep1: Boolean
        get() {
            if (draft.host.isBlank() || draft.username.isBlank()) return false
            return when (draft.authType) {
                AuthType.KEY -> draft.keyPairId != null
                AuthType.PASSWORD -> draft.password.isNotEmpty()
            }
        }

    /** Enables step 2's Test button — same check as step-1 advance. */
    val canTestConnection: Boolean
        get() = canAdvanceFromStep1
}
