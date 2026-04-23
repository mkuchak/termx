package dev.kuch.termx.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.libs.sshnative.crypto.SshKeyPairGenerator
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
 * Drives [KeyGenerateScreen].
 *
 * Generates the crypto on [Dispatchers.Default] (RSA-4096 is CPU-heavy),
 * stashes the private bytes in the [SecretVault], and inserts a [KeyPair]
 * row referencing the vault alias. On success the caller navigates to the
 * new key's detail screen.
 */
@HiltViewModel
class KeyGenerateViewModel @Inject constructor(
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val vaultLockState: VaultLockState,
) : ViewModel() {

    data class UiState(
        val algorithm: KeyAlgorithm = KeyAlgorithm.ED25519,
        val label: String = "",
        val comment: String = "",
        val isGenerating: Boolean = false,
        val vaultLocked: Boolean = false,
        val error: String? = null,
    ) {
        val canGenerate: Boolean
            get() = label.isNotBlank() && !isGenerating
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Surface vault-lock status so the screen can show the proper banner
        // and block the action until an unlock round-trip completes.
        viewModelScope.launch {
            vaultLockState.state.collect { s ->
                _state.update {
                    it.copy(vaultLocked = s != VaultLockState.State.Unlocked)
                }
            }
        }
    }

    fun onAlgorithmChange(algorithm: KeyAlgorithm) = _state.update { it.copy(algorithm = algorithm) }
    fun onLabelChange(value: String) = _state.update {
        it.copy(label = value, error = null)
    }
    fun onCommentChange(value: String) = _state.update { it.copy(comment = value) }

    /**
     * Kicks off generation. The [onSuccess] callback receives the freshly
     * inserted [KeyPair.id] so the caller can navigate.
     */
    fun generate(onSuccess: (UUID) -> Unit) {
        val snapshot = _state.value
        if (!snapshot.canGenerate) return
        if (snapshot.vaultLocked) {
            _state.update { it.copy(error = "Unlock the vault and try again.") }
            return
        }

        _state.update { it.copy(isGenerating = true, error = null) }

        viewModelScope.launch {
            val comment = snapshot.comment.ifBlank { "termx-${snapshot.label}" }
            val result = runCatching {
                val generated = withContext(Dispatchers.Default) {
                    when (snapshot.algorithm) {
                        KeyAlgorithm.ED25519 -> SshKeyPairGenerator.ed25519(comment)
                        KeyAlgorithm.RSA_4096 -> SshKeyPairGenerator.rsa4096(comment)
                    }
                }

                val id = UUID.randomUUID()
                val alias = "key-$id-private"
                secretVault.store(alias, generated.privatePem)

                // Wipe the in-memory copy of private bytes as soon as the
                // vault has committed them — this object is eligible for GC
                // but the zeroing short-circuits any heap-dump window.
                generated.privatePem.fill(0)

                val row = KeyPair(
                    id = id,
                    label = snapshot.label.trim(),
                    algorithm = snapshot.algorithm,
                    publicKey = generated.publicOpenSsh,
                    keystoreAlias = alias,
                    createdAt = Instant.now(),
                )
                keyPairRepository.insert(row)
                id
            }

            result
                .onSuccess { id ->
                    _state.update { it.copy(isGenerating = false) }
                    onSuccess(id)
                }
                .onFailure { t ->
                    val message = when (t) {
                        is VaultLockedException ->
                            "Vault is locked. Unlock and try again."
                        else -> t.message ?: "Key generation failed."
                    }
                    _state.update {
                        it.copy(isGenerating = false, error = message)
                    }
                }
        }
    }
}
