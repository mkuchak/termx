package dev.kuch.termx.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.libs.sshnative.crypto.OpenSshKeyParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Drives [KeyDetailScreen].
 *
 * Loads the single [KeyPair] by id. Also computes the SHA256 fingerprint
 * and tracks how many [Server] rows reference it (for the delete
 * confirmation). Deletion is routed the same way as in the list screen.
 */
@HiltViewModel
class KeyDetailViewModel @Inject constructor(
    private val keyPairRepository: KeyPairRepository,
    private val serverRepository: ServerRepository,
    private val secretVault: SecretVault,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val keyPair: KeyPair? = null,
        val fingerprint: String = "",
        val referencingServers: List<Server> = emptyList(),
        val otherKeys: List<KeyPair> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(id: UUID) {
        viewModelScope.launch {
            val keyPair = keyPairRepository.getById(id)
            if (keyPair == null) {
                _state.value = UiState(isLoading = false)
                return@launch
            }
            val fingerprint = runCatching {
                OpenSshKeyParser.fingerprintSha256(keyPair.publicKey)
            }.getOrDefault("SHA256:?")

            val servers = serverRepository.observeAll().first()
            val refs = servers.filter { it.keyPairId == id }

            val allKeys = keyPairRepository.observeAll().first()
            val others = allKeys.filter { it.id != id }

            _state.value = UiState(
                isLoading = false,
                keyPair = keyPair,
                fingerprint = fingerprint,
                referencingServers = refs,
                otherKeys = others,
            )
        }
    }

    /**
     * Delete with no reassignment (caller should only invoke when
     * `referencingServers.isEmpty()`).
     */
    fun delete(onDone: () -> Unit) {
        val keyPair = _state.value.keyPair ?: return
        viewModelScope.launch {
            keyPairRepository.delete(keyPair.id)
            if (secretVault.isUnlocked()) {
                runCatching { secretVault.delete(keyPair.keystoreAlias) }
            }
            onDone()
        }
    }

    /**
     * Reassign every referencing server to [toKeyId], then delete.
     */
    fun reassignAndDelete(toKeyId: UUID, onDone: () -> Unit) {
        val keyPair = _state.value.keyPair ?: return
        viewModelScope.launch {
            val refs = _state.value.referencingServers
            for (s in refs) {
                serverRepository.upsert(s.copy(keyPairId = toKeyId))
            }
            keyPairRepository.delete(keyPair.id)
            if (secretVault.isUnlocked()) {
                runCatching { secretVault.delete(keyPair.keystoreAlias) }
            }
            onDone()
        }
    }
}
