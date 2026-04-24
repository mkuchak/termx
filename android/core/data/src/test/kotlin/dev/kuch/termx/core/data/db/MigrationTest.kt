package dev.kuch.termx.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-powered unit tests for [AppDatabase]'s hand-rolled migrations.
 *
 * v1 to v2 adds the `custom_themes` table for Task #48. A silent regression
 * here during a user upgrade from v0.3.x to v1.0.0 would wipe their entire
 * Room DB — servers, key pairs, groups and themes — which is the worst
 * failure mode a mobile client can ship. These tests guard against it.
 *
 * [MigrationTestHelper] rehydrates the schema JSON snapshots emitted by KSP
 * into `:core:data/schemas/`; we expose that directory to the test source
 * set's `assets` in `build.gradle.kts` so Robolectric can find it on the
 * JVM without needing an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MigrationTest {

    private val dbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate_1_to_2_preserves_existing_rows() {
        val keyId = "00000000-0000-0000-0000-000000000001"
        val groupId = "00000000-0000-0000-0000-000000000002"
        val serverId = "00000000-0000-0000-0000-000000000003"

        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO key_pairs (id, label, algorithm, publicKey, keystoreAlias, createdAt) " +
                    "VALUES ('$keyId', 'primary', 'ED25519', 'AAAA', 'alias-1', 1700000000000)",
            )
            execSQL(
                "INSERT INTO server_groups (id, name, sortOrder, isCollapsed) " +
                    "VALUES ('$groupId', 'prod', 0, 0)",
            )
            execSQL(
                "INSERT INTO servers (id, label, host, port, username, authType, keyPairId, groupId, " +
                    "useMosh, autoAttachTmux, tmuxSessionName, lastConnected, pingMs, sortOrder, companionInstalled) " +
                    "VALUES ('$serverId', 'prod-web', 'example.com', 22, 'root', 'KEY', " +
                    "'$keyId', '$groupId', 1, 1, 'main', 1700000000000, 42, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        val serverCount = db.query("SELECT COUNT(*) FROM servers").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        val keyCount = db.query("SELECT COUNT(*) FROM key_pairs").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        val groupCount = db.query("SELECT COUNT(*) FROM server_groups").use { c ->
            c.moveToFirst(); c.getInt(0)
        }
        val serverLabel = db.query("SELECT label FROM servers WHERE id = '$serverId'").use { c ->
            c.moveToFirst(); c.getString(0)
        }

        assertEquals(1, serverCount)
        assertEquals(1, keyCount)
        assertEquals(1, groupCount)
        assertEquals("prod-web", serverLabel)
    }

    @Test
    fun migrate_1_to_2_creates_empty_custom_themes_table() {
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        val tableExists = db.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='custom_themes'",
        ).use { c -> c.moveToFirst(); c.getInt(0) }
        val rowCount = db.query("SELECT COUNT(*) FROM custom_themes").use { c ->
            c.moveToFirst(); c.getInt(0)
        }

        assertEquals(1, tableExists)
        assertEquals(0, rowCount)
    }

    @Test
    fun migrate_1_to_2_custom_themes_schema_matches_entity() {
        helper.createDatabase(dbName, 1).close()

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        val columns = mutableMapOf<String, Pair<String, Int>>()
        db.query("PRAGMA table_info(custom_themes)").use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val type = cursor.getString(2)
                val notNull = cursor.getInt(3)
                columns[name] = type to notNull
            }
        }

        assertEquals(
            setOf("id", "displayName", "colorsJson", "createdAt"),
            columns.keys,
        )
        // id/displayName/colorsJson are TEXT NOT NULL; createdAt is INTEGER NOT NULL
        // because Room's Instant converter encodes as epoch millis.
        assertEquals("TEXT" to 1, columns["id"])
        assertEquals("TEXT" to 1, columns["displayName"])
        assertEquals("TEXT" to 1, columns["colorsJson"])
        assertEquals("INTEGER" to 1, columns["createdAt"])
    }

    @Test
    fun migrate_1_to_2_preserves_unrelated_columns_after_rewrite() {
        // Guards against an accidental CREATE TABLE on an existing table —
        // if the migration ever adds columns to `servers`, this ensures the
        // original row contents survive intact.
        val serverId = "00000000-0000-0000-0000-000000000099"
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO servers (id, label, host, port, username, authType, keyPairId, groupId, " +
                    "useMosh, autoAttachTmux, tmuxSessionName, lastConnected, pingMs, sortOrder, companionInstalled) " +
                    "VALUES ('$serverId', 'only', 'h.example', 2222, 'user', 'PASSWORD', " +
                    "NULL, NULL, 0, 0, 'main', NULL, NULL, 7, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2)

        val cursor = db.query("SELECT host, port, sortOrder, authType FROM servers WHERE id = '$serverId'")
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals("h.example", it.getString(0))
            assertEquals(2222, it.getInt(1))
            assertEquals(7, it.getInt(2))
            assertEquals("PASSWORD", it.getString(3))
        }
    }
}
