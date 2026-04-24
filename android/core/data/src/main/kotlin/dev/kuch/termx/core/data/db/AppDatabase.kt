package dev.kuch.termx.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kuch.termx.core.data.db.converter.Converters
import dev.kuch.termx.core.data.db.dao.CustomThemeDao
import dev.kuch.termx.core.data.db.dao.KeyPairDao
import dev.kuch.termx.core.data.db.dao.ServerDao
import dev.kuch.termx.core.data.db.dao.ServerGroupDao
import dev.kuch.termx.core.data.db.entity.CustomThemeEntity
import dev.kuch.termx.core.data.db.entity.KeyPairEntity
import dev.kuch.termx.core.data.db.entity.ServerEntity
import dev.kuch.termx.core.data.db.entity.ServerGroupEntity

/**
 * Single Room database for the app — `termx.db`.
 *
 * Schema export is on so migrations from version 1 onward are reviewable
 * in source control. Version history:
 *
 * - v1: initial — [ServerEntity], [KeyPairEntity], [ServerGroupEntity].
 * - v2: adds [CustomThemeEntity] for the Task #48 custom theme editor.
 * - v3: adds `password_alias` column to `servers` so SSH passwords can
 *   round-trip via [dev.kuch.termx.core.data.vault.SecretVault].
 */
@Database(
    entities = [
        ServerEntity::class,
        KeyPairEntity::class,
        ServerGroupEntity::class,
        CustomThemeEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun keyPairDao(): KeyPairDao
    abstract fun serverGroupDao(): ServerGroupDao
    abstract fun customThemeDao(): CustomThemeDao
}
