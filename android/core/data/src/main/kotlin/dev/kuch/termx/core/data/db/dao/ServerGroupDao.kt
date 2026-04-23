package dev.kuch.termx.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kuch.termx.core.data.db.entity.ServerGroupEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerGroupDao {
    @Query("SELECT * FROM server_groups ORDER BY sortOrder")
    fun observeAll(): Flow<List<ServerGroupEntity>>

    @Upsert
    suspend fun upsert(entity: ServerGroupEntity)

    @Query("DELETE FROM server_groups WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("UPDATE server_groups SET sortOrder = :order WHERE id = :id")
    suspend fun updateOrder(id: UUID, order: Int)
}
