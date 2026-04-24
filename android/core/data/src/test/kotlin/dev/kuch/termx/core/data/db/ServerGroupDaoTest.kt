package dev.kuch.termx.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.db.entity.ServerGroupEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CRUD tests for [dev.kuch.termx.core.data.db.dao.ServerGroupDao]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ServerGroupDaoTest {

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
    fun upsert_inserts_then_updates_same_row() = runTest {
        val g = ServerGroupEntity(UUID.randomUUID(), "prod", 0, false)
        db.serverGroupDao().upsert(g)
        db.serverGroupDao().upsert(g.copy(name = "prod-renamed"))

        val observed = db.serverGroupDao().observeAll().first()

        assertEquals(1, observed.size)
        assertEquals("prod-renamed", observed[0].name)
    }

    @Test
    fun observeAll_orders_by_sortOrder() = runTest {
        db.serverGroupDao().upsert(ServerGroupEntity(UUID.randomUUID(), "c", 2, false))
        db.serverGroupDao().upsert(ServerGroupEntity(UUID.randomUUID(), "a", 0, false))
        db.serverGroupDao().upsert(ServerGroupEntity(UUID.randomUUID(), "b", 1, false))

        val names = db.serverGroupDao().observeAll().first().map { it.name }

        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun collapsed_flag_persists_across_upsert() = runTest {
        val g = ServerGroupEntity(UUID.randomUUID(), "prod", 0, false)
        db.serverGroupDao().upsert(g)
        db.serverGroupDao().upsert(g.copy(isCollapsed = true))

        val reloaded = db.serverGroupDao().observeAll().first().single()

        assertTrue(reloaded.isCollapsed)
    }

    @Test
    fun updateOrder_mutates_sort_column() = runTest {
        val g = ServerGroupEntity(UUID.randomUUID(), "prod", 0, false)
        db.serverGroupDao().upsert(g)

        db.serverGroupDao().updateOrder(g.id, 42)

        assertEquals(42, db.serverGroupDao().observeAll().first().single().sortOrder)
    }

    @Test
    fun delete_removes_row() = runTest {
        val g = ServerGroupEntity(UUID.randomUUID(), "prod", 0, false)
        db.serverGroupDao().upsert(g)

        db.serverGroupDao().delete(g.id)

        assertEquals(emptyList<ServerGroupEntity>(), db.serverGroupDao().observeAll().first())
    }
}
