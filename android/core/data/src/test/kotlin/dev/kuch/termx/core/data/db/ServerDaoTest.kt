package dev.kuch.termx.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.db.entity.KeyPairEntity
import dev.kuch.termx.core.data.db.entity.ServerEntity
import dev.kuch.termx.core.data.db.entity.ServerGroupEntity
import java.time.Instant
import java.util.UUID
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
 * CRUD tests for [dev.kuch.termx.core.data.db.dao.ServerDao] backed by an
 * in-memory Room database. Exercises insert/get/delete plus the sort-order
 * stream that the server list UI consumes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ServerDaoTest {

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
        val server = sampleServer(label = "edge")
        db.serverDao().upsert(server)

        val loaded = db.serverDao().getById(server.id)

        assertNotNull(loaded)
        assertEquals(server, loaded)
    }

    @Test
    fun observeAll_orders_by_sortOrder() = runTest {
        val a = sampleServer(label = "a", sortOrder = 2)
        val b = sampleServer(label = "b", sortOrder = 0)
        val c = sampleServer(label = "c", sortOrder = 1)
        db.serverDao().upsert(a)
        db.serverDao().upsert(b)
        db.serverDao().upsert(c)

        val observed = db.serverDao().observeAll().first()

        assertEquals(listOf("b", "c", "a"), observed.map { it.label })
    }

    @Test
    fun observeByGroup_partitions_grouped_and_ungrouped() = runTest {
        val group = ServerGroupEntity(UUID.randomUUID(), "prod", 0, false)
        db.serverGroupDao().upsert(group)
        val grouped = sampleServer(label = "g", groupId = group.id, sortOrder = 0)
        val ungrouped = sampleServer(label = "u", groupId = null, sortOrder = 0)
        db.serverDao().upsert(grouped)
        db.serverDao().upsert(ungrouped)

        val inGroup = db.serverDao().observeByGroup(group.id).first()
        val orphaned = db.serverDao().observeByGroup(null).first()

        assertEquals(listOf("g"), inGroup.map { it.label })
        assertEquals(listOf("u"), orphaned.map { it.label })
    }

    @Test
    fun delete_removes_row() = runTest {
        val s = sampleServer()
        db.serverDao().upsert(s)
        db.serverDao().delete(s.id)

        assertNull(db.serverDao().getById(s.id))
    }

    @Test
    fun updatePing_and_updateLastConnected_mutate_only_target_columns() = runTest {
        val s = sampleServer()
        db.serverDao().upsert(s)
        val ts = Instant.ofEpochMilli(1_700_000_999_000L)
        db.serverDao().updatePing(s.id, 77)
        db.serverDao().updateLastConnected(s.id, ts)

        val loaded = db.serverDao().getById(s.id)!!
        assertEquals(77, loaded.pingMs)
        assertEquals(ts, loaded.lastConnected)
        assertEquals(s.label, loaded.label)
        assertEquals(s.sortOrder, loaded.sortOrder)
    }

    @Test
    fun foreign_key_on_delete_nullifies_keyPairId() = runTest {
        val key = KeyPairEntity(
            id = UUID.randomUUID(),
            label = "k",
            algorithm = "ED25519",
            publicKey = "AAA",
            keystoreAlias = "alias",
            createdAt = Instant.ofEpochMilli(1_700_000_000_000L),
        )
        db.keyPairDao().insert(key)
        val server = sampleServer(keyPairId = key.id)
        db.serverDao().upsert(server)

        db.keyPairDao().delete(key.id)

        val reloaded = db.serverDao().getById(server.id)
        assertNotNull(reloaded)
        assertNull(reloaded!!.keyPairId)
    }

    private fun sampleServer(
        id: UUID = UUID.randomUUID(),
        label: String = "server",
        keyPairId: UUID? = null,
        groupId: UUID? = null,
        sortOrder: Int = 0,
    ) = ServerEntity(
        id = id,
        label = label,
        host = "host.example",
        port = 22,
        username = "root",
        authType = if (keyPairId == null) "PASSWORD" else "KEY",
        keyPairId = keyPairId,
        groupId = groupId,
        useMosh = false,
        autoAttachTmux = false,
        tmuxSessionName = "main",
        lastConnected = null,
        pingMs = null,
        sortOrder = sortOrder,
        companionInstalled = false,
    )
}
