package dev.kuch.termx.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-rolled Room migrations. Each entry captures one schema step; the
 * [AppDatabase] version constant must always equal the highest `to` value
 * in this list. Schema JSON is emitted to `:core:data`'s `schemas/`
 * directory so the diffs are reviewable.
 */

/**
 * v1 to v2: adds `custom_themes` table backing Task #48's user-authored
 * terminal palettes. No existing rows are touched.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_themes` (" +
                "`id` TEXT NOT NULL, " +
                "`displayName` TEXT NOT NULL, " +
                "`colorsJson` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`)" +
                ")",
        )
    }
}

/**
 * v2 to v3: adds `password_alias` column to `servers`. The alias is a
 * key into [dev.kuch.termx.core.data.vault.SecretVault] — actual bytes
 * live in the Keystore-encrypted blob, never in Room. Existing rows are
 * left with NULL; password-auth servers that pre-date this column get
 * prompted for the password on next connect (same fallback as before).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE servers ADD COLUMN password_alias TEXT")
    }
}

/**
 * v3 to v4: drops the `custom_themes` table. Termx ships Sorcerer as
 * the only theme from v1.3.0 onward; the editor + repository + DAO
 * were never actually wired into navigation, so the table held no
 * user data on any real install. Drop is unconditional and
 * irreversible — but since nothing ever wrote to it, this is loss-
 * free.
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS custom_themes")
    }
}

/**
 * v4 to v5: drops the `autoAttachTmux` and `tmuxSessionName` columns from
 * `servers` now that the tmux integration is gone — termx is a plain-shell
 * terminal — and, in the same recreate, introduces the generic
 * `startupCommandEnabled` / `startup_command` pair that replaces it: an
 * optional free-text command sent to the shell on connect (the user can
 * point it at tmux/zellij/screen or anything else). `useMosh` stays: mosh is
 * an orthogonal transport, not a multiplexer.
 *
 * minSdk 28 ships SQLite 3.22, which lacks `ALTER TABLE ... DROP COLUMN`, so
 * this follows Room's 12-step recreate-table pattern: create `servers_new`
 * with the v4 schema minus the two tmux columns plus the two new ones
 * (foreign keys and column types copied verbatim from `schemas/.../5.json`),
 * copy the surviving columns across — the new columns take their `DEFAULT`s
 * for existing rows — swap the tables, then recreate the three indices Room
 * expects.
 */
val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `servers_new` (" +
                "`id` TEXT NOT NULL, " +
                "`label` TEXT NOT NULL, " +
                "`host` TEXT NOT NULL, " +
                "`port` INTEGER NOT NULL, " +
                "`username` TEXT NOT NULL, " +
                "`authType` TEXT NOT NULL, " +
                "`keyPairId` TEXT, " +
                "`groupId` TEXT, " +
                "`useMosh` INTEGER NOT NULL, " +
                "`startupCommandEnabled` INTEGER NOT NULL DEFAULT 0, " +
                "`lastConnected` INTEGER, " +
                "`pingMs` INTEGER, " +
                "`sortOrder` INTEGER NOT NULL, " +
                "`companionInstalled` INTEGER NOT NULL, " +
                "`password_alias` TEXT, " +
                "`startup_command` TEXT NOT NULL DEFAULT '', " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`keyPairId`) REFERENCES `key_pairs`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL , " +
                "FOREIGN KEY(`groupId`) REFERENCES `server_groups`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "INSERT INTO `servers_new` (" +
                "`id`, `label`, `host`, `port`, `username`, `authType`, `keyPairId`, " +
                "`groupId`, `useMosh`, `lastConnected`, `pingMs`, `sortOrder`, " +
                "`companionInstalled`, `password_alias`) " +
                "SELECT `id`, `label`, `host`, `port`, `username`, `authType`, `keyPairId`, " +
                "`groupId`, `useMosh`, `lastConnected`, `pingMs`, `sortOrder`, " +
                "`companionInstalled`, `password_alias` FROM `servers`",
        )
        db.execSQL("DROP TABLE `servers`")
        db.execSQL("ALTER TABLE `servers_new` RENAME TO `servers`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_servers_keyPairId` ON `servers` (`keyPairId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_servers_groupId` ON `servers` (`groupId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_servers_sortOrder` ON `servers` (`sortOrder`)")
    }
}

/** Ordered list of every migration the database understands. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
)
