package dev.kuch.termx.core.domain.repository

import dev.kuch.termx.core.domain.model.ServerGroup
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Persistence surface for [ServerGroup] rows.
 *
 * Implementations live in `:core:data`.
 */
interface ServerGroupRepository {
    fun observeAll(): Flow<List<ServerGroup>>

    suspend fun upsert(group: ServerGroup)

    suspend fun delete(id: UUID)

    /** Atomically rewrite `sortOrder` for every id in [groupIdToOrder]. */
    suspend fun reorder(groupIdToOrder: Map<UUID, Int>)
}
