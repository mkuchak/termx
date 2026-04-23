package dev.kuch.termx.core.domain.repository

import dev.kuch.termx.core.domain.model.Server
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Persistence surface for [Server] rows.
 *
 * Implementations live in `:core:data`. All read APIs return cold [Flow]s so
 * UI can subscribe and be driven by Room's invalidation tracker.
 */
interface ServerRepository {
    fun observeAll(): Flow<List<Server>>

    fun observeByGroup(groupId: UUID?): Flow<List<Server>>

    suspend fun getById(id: UUID): Server?

    suspend fun upsert(server: Server)

    suspend fun delete(id: UUID)

    /** Atomically rewrite `sortOrder` for every id in [serverIdToOrder]. */
    suspend fun reorder(serverIdToOrder: Map<UUID, Int>)

    suspend fun updateLastConnected(id: UUID, at: Instant)

    suspend fun updatePing(id: UUID, pingMs: Int?)
}
