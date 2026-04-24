package dev.kuch.termx.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.db.entity.KeyPairEntity
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

/** CRUD tests for [dev.kuch.termx.core.data.db.dao.KeyPairDao]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class KeyPairDaoTest {

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
    fun insert_and_getById_round_trip() = runTest {
        val key = sample(label = "primary")
        db.keyPairDao().insert(key)

        val loaded = db.keyPairDao().getById(key.id)

        assertNotNull(loaded)
        assertEquals(key, loaded)
    }

    @Test
    fun observeAll_orders_by_createdAt_desc() = runTest {
        val older = sample(label = "older", createdAt = Instant.ofEpochMilli(1_000L))
        val newer = sample(label = "newer", createdAt = Instant.ofEpochMilli(2_000L))
        db.keyPairDao().insert(older)
        db.keyPairDao().insert(newer)

        val observed = db.keyPairDao().observeAll().first()

        assertEquals(listOf("newer", "older"), observed.map { it.label })
    }

    @Test
    fun delete_removes_row() = runTest {
        val key = sample()
        db.keyPairDao().insert(key)

        db.keyPairDao().delete(key.id)

        assertNull(db.keyPairDao().getById(key.id))
    }

    private fun sample(
        id: UUID = UUID.randomUUID(),
        label: String = "k",
        createdAt: Instant = Instant.ofEpochMilli(1_700_000_000_000L),
    ) = KeyPairEntity(
        id = id,
        label = label,
        algorithm = "ED25519",
        publicKey = "AAAA",
        keystoreAlias = "alias-$label",
        createdAt = createdAt,
    )
}
