package dev.kuch.termx.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.kuch.termx.core.data.db.entity.CustomThemeEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Task #48 custom terminal themes. Simple CRUD — the feature
 * layer merges these rows with [
 * dev.kuch.termx.core.domain.theme.BuiltInThemes.all] before rendering
 * the Settings > Theme list.
 */
@Dao
interface CustomThemeDao {
    @Query("SELECT * FROM custom_themes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CustomThemeEntity>>

    @Query("SELECT * FROM custom_themes WHERE id = :id")
    suspend fun getById(id: String): CustomThemeEntity?

    @Upsert
    suspend fun upsert(entity: CustomThemeEntity)

    @Query("DELETE FROM custom_themes WHERE id = :id")
    suspend fun delete(id: String)
}
