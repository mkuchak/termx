package dev.kuch.termx.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.db.entity.CustomThemeEntity
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CRUD tests for [dev.kuch.termx.core.data.db.dao.CustomThemeDao] — the
 * Task #48 palette editor's persistence surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CustomThemeDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsert_and_getById_round_trip() = runTest {
        val t = sample(id = "custom:one", displayName = "One")
        db.customThemeDao().upsert(t)

        val loaded = db.customThemeDao().getById("custom:one")

        assertNotNull(loaded)
        assertEquals(t, loaded)
    }

    @Test
    fun upsert_updates_existing_row_by_primary_key() = runTest {
        val t = sample(id = "custom:one", displayName = "One")
        db.customThemeDao().upsert(t)
        db.customThemeDao().upsert(t.copy(displayName = "One Renamed", colorsJson = "{\"a\":1}"))

        val loaded = db.customThemeDao().getById("custom:one")!!

        assertEquals("One Renamed", loaded.displayName)
        assertEquals("{\"a\":1}", loaded.colorsJson)
        // Still exactly one row.
        assertEquals(1, db.customThemeDao().observeAll().first().size)
    }

    @Test
    fun observeAll_orders_by_createdAt_desc() = runTest {
        db.customThemeDao().upsert(sample("a", "a", Instant.ofEpochMilli(1_000L)))
        db.customThemeDao().upsert(sample("b", "b", Instant.ofEpochMilli(3_000L)))
        db.customThemeDao().upsert(sample("c", "c", Instant.ofEpochMilli(2_000L)))

        val observed = db.customThemeDao().observeAll().first()

        assertEquals(listOf("b", "c", "a"), observed.map { it.id })
    }

    @Test
    fun delete_removes_row() = runTest {
        val t = sample("custom:one")
        db.customThemeDao().upsert(t)

        db.customThemeDao().delete("custom:one")

        assertNull(db.customThemeDao().getById("custom:one"))
        assertEquals(emptyList<CustomThemeEntity>(), db.customThemeDao().observeAll().first())
    }

    private fun sample(
        id: String,
        displayName: String = id,
        createdAt: Instant = Instant.ofEpochMilli(1_700_000_000_000L),
    ) = CustomThemeEntity(
        id = id,
        displayName = displayName,
        colorsJson = "{}",
        createdAt = createdAt,
    )
}
