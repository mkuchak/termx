package dev.kuch.termx.feature.keys

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.libs.sshnative.crypto.OpenSshKeyParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Drives [KeyListScreen].
 *
 * Streams every [KeyPair] row plus the list of servers that reference it
 * (needed for the "this key is used by N servers" warning before delete).
 * Deletion routes through [reassignAndDelete] once the user picks a
 * replacement; for unreferenced keys, [delete] does the straight thing.
 */
@HiltViewModel
class KeyListViewModel @Inject constructor(
    private val keyPairRepository: KeyPairRepository,
    private val serverRepository: ServerRepository,
    private val secretVault: SecretVault,
) : ViewModel() {

    data class KeyRow(
        val keyPair: KeyPair,
        val fingerprint: String,
        val usedByServerCount: Int,
    )

    data class UiState(
        val isLoading: Boolean = true,
        val rows: List<KeyRow> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                keyPairRepository.observeAll(),
                serverRepository.observeAll(),
            ) { keys, servers -> keys to servers }
                .map { (keys, servers) ->
                    val byKey = servers.groupBy { it.keyPairId }
                    keys.map { k ->
                        KeyRow(
                            keyPair = k,
                            fingerprint = runCatching {
                                OpenSshKeyParser.fingerprintSha256(k.publicKey)
                            }.getOrDefault("SHA256:?"),
                            usedByServerCount = byKey[k.id]?.size ?: 0,
                        )
                    }
                }
                .onEach { rows ->
                    _state.value = UiState(isLoading = false, rows = rows)
                }
                .collect()
        }
    }

    /**
     * Straight-line delete. Removes the Room row and the vault blob. Safe
     * to call only when [KeyRow.usedByServerCount] is zero — the screen
     * shows the reassign dialog in the referenced case.
     */
    fun delete(id: UUID, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val target = keyPairRepository.getById(id)
            keyPairRepository.delete(id)
            if (target != null && secretVault.isUnlocked()) {
                runCatching { secretVault.delete(target.keystoreAlias) }
            }
            onDone()
        }
    }

    /**
     * Point every server whose `keyPairId == fromKeyId` at [toKeyId],
     * then delete the original key (row + vault blob).
     *
     * Caller should ensure the vault is unlocked before invoking.
     */
    fun reassignAndDelete(fromKeyId: UUID, toKeyId: UUID, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val servers = serverRepository.observeAll().first()

            val toUpdate = servers.filter { it.keyPairId == fromKeyId }
            for (s in toUpdate) {
                serverRepository.upsert(s.copy(keyPairId = toKeyId))
            }

            val target = keyPairRepository.getById(fromKeyId)
            keyPairRepository.delete(fromKeyId)
            if (target != null && secretVault.isUnlocked()) {
                runCatching { secretVault.delete(target.keystoreAlias) }
            }
            onDone()
        }
    }
}
