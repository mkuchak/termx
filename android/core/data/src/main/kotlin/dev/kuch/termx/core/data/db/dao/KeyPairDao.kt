package dev.kuch.termx.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.kuch.termx.core.data.db.entity.KeyPairEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyPairDao {
    @Query("SELECT * FROM key_pairs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<KeyPairEntity>>

    @Query("SELECT * FROM key_pairs WHERE id = :id")
    suspend fun getById(id: UUID): KeyPairEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KeyPairEntity)

    @Query("DELETE FROM key_pairs WHERE id = :id")
    suspend fun delete(id: UUID)
}
