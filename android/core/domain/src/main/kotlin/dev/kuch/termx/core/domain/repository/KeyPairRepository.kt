package dev.kuch.termx.core.domain.repository

import dev.kuch.termx.core.domain.model.KeyPair
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Persistence surface for [KeyPair] rows.
 *
 * Implementations live in `:core:data`. Only metadata + public keys are
 * persisted here; private bytes live in the Keystore-encrypted vault.
 */
interface KeyPairRepository {
    fun observeAll(): Flow<List<KeyPair>>

    suspend fun getById(id: UUID): KeyPair?

    suspend fun insert(keyPair: KeyPair)

    suspend fun delete(id: UUID)
}
