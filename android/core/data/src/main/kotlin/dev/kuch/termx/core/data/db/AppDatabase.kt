package dev.kuch.termx.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kuch.termx.core.data.db.converter.Converters
import dev.kuch.termx.core.data.db.dao.KeyPairDao
import dev.kuch.termx.core.data.db.dao.ServerDao
import dev.kuch.termx.core.data.db.dao.ServerGroupDao
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
 * - v2: adds `custom_themes` for the Task #48 custom theme editor.
 *   Editor + repository never reached a navigation entry point and were
 *   dropped wholesale in v4 along with the orphan table.
 * - v3: adds `password_alias` column to `servers` so SSH passwords can
 *   round-trip via [dev.kuch.termx.core.data.vault.SecretVault].
 * - v4: drops the (always-orphaned) `custom_themes` table when shipping
 *   Sorcerer as the only theme.
 * - v5: drops the `autoAttachTmux` and `tmuxSessionName` columns from
 *   `servers` after tmux integration was removed; termx is now a plain-
 *   shell terminal. The same recreate adds the generic
 *   `startupCommandEnabled` / `startup_command` pair — an optional command
 *   sent to the shell on connect. Recreate-table migration since minSdk
 *   28's SQLite predates `ALTER TABLE ... DROP COLUMN`.
 */
@Database(
    entities = [
        ServerEntity::class,
        KeyPairEntity::class,
        ServerGroupEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun keyPairDao(): KeyPairDao
    abstract fun serverGroupDao(): ServerGroupDao
}
