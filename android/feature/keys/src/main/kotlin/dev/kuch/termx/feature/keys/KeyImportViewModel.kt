package dev.kuch.termx.feature.keys

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.libs.sshnative.crypto.OpenSshKeyParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Drives [KeyImportScreen].
 *
 * Reads a user-picked SAF document URI, attempts to parse it (optionally
 * decrypting with the passphrase), and persists the result to the vault +
 * Room on confirm.
 */
@HiltViewModel
class KeyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val vaultLockState: VaultLockState,
) : ViewModel() {

    data class Preview(
        val algorithm: KeyAlgorithm,
        val fingerprint: String,
        val publicKey: String,
        val privatePem: ByteArray,
    )

    data class UiState(
        val sourceFileName: String? = null,
        val rawBytes: ByteArray? = null,
        val passphrase: String = "",
        val label: String = "",
        val isBusy: Boolean = false,
        val preview: Preview? = null,
        val error: String? = null,
        val vaultLocked: Boolean = false,
    ) {
        val needsPassphrasePrompt: Boolean
            get() = error != null && error.contains("passphrase", ignoreCase = true)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            vaultLockState.state.collect { s ->
                _state.update {
                    it.copy(vaultLocked = s != VaultLockState.State.Unlocked)
                }
            }
        }
    }

    /**
     * Store the raw bytes the user picked via SAF. Parsing is deferred
     * until [parse] so the passphrase can be requested interactively.
     */
    fun onFilePicked(uri: Uri) {
        _state.update { it.copy(isBusy = true, error = null, preview = null) }
        viewModelScope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("Could not open picked file.")
                val name = inferDisplayName(uri) ?: "key"
                bytes to name
            }
            result.onSuccess { (bytes, name) ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        sourceFileName = name,
                        rawBytes = bytes,
                        label = it.label.ifBlank { name },
                    )
                }
                // Attempt parse right away with no passphrase; if that fails
                // due to encryption, the UI will prompt for one.
                parse()
            }
            result.onFailure { t ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = t.message ?: "Failed to read file.",
                    )
                }
            }
        }
    }

    fun onPassphraseChange(v: String) = _state.update { it.copy(passphrase = v, error = null) }
    fun onLabelChange(v: String) = _state.update { it.copy(label = v) }

    /**
     * Re-parse the already-loaded bytes (post-passphrase-entry).
     */
    fun parse() {
        val bytes = _state.value.rawBytes ?: return
        val passphrase = _state.value.passphrase.ifBlank { null }

        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    OpenSshKeyParser.parsePrivatePem(bytes, passphrase)
                }
            }
            result.onSuccess { parsed ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        preview = Preview(
                            algorithm = parsed.algorithm,
                            fingerprint = OpenSshKeyParser.fingerprintSha256(parsed.publicOpenSsh),
                            publicKey = parsed.publicOpenSsh,
                            privatePem = parsed.privatePem,
                        ),
                    )
                }
            }
            result.onFailure { t ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = t.message ?: "Failed to parse key.",
                    )
                }
            }
        }
    }

    /**
     * Persist the successfully-parsed key and fire [onSuccess] with the
     * new row id. No-op when there is no preview yet.
     */
    fun save(onSuccess: (UUID) -> Unit) {
        val preview = _state.value.preview ?: return
        if (_state.value.vaultLocked) {
            _state.update { it.copy(error = "Unlock the vault and try again.") }
            return
        }

        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = runCatching {
                val id = UUID.randomUUID()
                val alias = "key-$id-private"
                secretVault.store(alias, preview.privatePem)
                preview.privatePem.fill(0)

                val row = KeyPair(
                    id = id,
                    label = _state.value.label.trim().ifBlank { "imported-key" },
                    algorithm = preview.algorithm,
                    publicKey = preview.publicKey,
                    keystoreAlias = alias,
                    createdAt = Instant.now(),
                )
                keyPairRepository.insert(row)
                id
            }

            result.onSuccess { id ->
                _state.update { it.copy(isBusy = false) }
                onSuccess(id)
            }
            result.onFailure { t ->
                val message = when (t) {
                    is VaultLockedException -> "Vault is locked. Unlock and try again."
                    else -> t.message ?: "Failed to save key."
                }
                _state.update { it.copy(isBusy = false, error = message) }
            }
        }
    }

    private fun inferDisplayName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return null
            return it.getString(idx)
        }
    }
}
