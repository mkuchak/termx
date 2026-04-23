package dev.kuch.termx.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kuch.termx.core.data.db.entity.ServerEntity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortOrder")
    fun observeAll(): Flow<List<ServerEntity>>

    /**
     * Observe servers for a given group, or "ungrouped" servers when
     * [groupId] is null. Room binds `null` literally so the `OR` branch
     * handles both cases.
     */
    @Query(
        "SELECT * FROM servers " +
            "WHERE groupId = :groupId OR (:groupId IS NULL AND groupId IS NULL) " +
            "ORDER BY sortOrder",
    )
    fun observeByGroup(groupId: UUID?): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun getById(id: UUID): ServerEntity?

    @Upsert
    suspend fun upsert(entity: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("UPDATE servers SET sortOrder = :order WHERE id = :id")
    suspend fun updateOrder(id: UUID, order: Int)

    @Query("UPDATE servers SET lastConnected = :at WHERE id = :id")
    suspend fun updateLastConnected(id: UUID, at: Instant)

    @Query("UPDATE servers SET pingMs = :pingMs WHERE id = :id")
    suspend fun updatePing(id: UUID, pingMs: Int?)
}
