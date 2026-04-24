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

/** Ordered list of every migration the database understands. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
