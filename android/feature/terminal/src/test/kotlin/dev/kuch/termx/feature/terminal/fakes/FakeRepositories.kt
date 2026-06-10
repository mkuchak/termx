package dev.kuch.termx.feature.terminal.fakes

import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Module-local in-memory repository + vault fakes for
 * [dev.kuch.termx.feature.terminal.TerminalViewModel] tests. Duplicated
 * (not shared with `:core:data`/`:feature:servers` test classpaths) so
 * this module's tests stay self-contained.
 */
class FakeServerRepository : ServerRepository {
    private val store = ConcurrentHashMap<UUID, Server>()
    private val flow = MutableStateFlow<List<Server>>(emptyList())
    val lastConnectedUpdates = mutableListOf<UUID>()

    /**
     * Every row that arrived via [upsert] (NOT test-seeded [put]s), in
     * order. CopyOnWrite because `ConnectionManager.submitPassword`
     * upserts from a Dispatchers.IO coroutine while the test thread
     * polls — lets tests pin the passwordAlias self-heal write.
     */
    val upserts = CopyOnWriteArrayList<Server>()

    fun put(server: Server) {
        store[server.id] = server
        flow.value = store.values.sortedBy { it.sortOrder }
    }

    override fun observeAll(): Flow<List<Server>> = flow.asStateFlow()
    override fun observeByGroup(groupId: UUID?): Flow<List<Server>> =
        flow.map { list -> list.filter { it.groupId == groupId } }

    override suspend fun getById(id: UUID): Server? = store[id]
    override suspend fun upsert(server: Server) {
        upserts += server
        put(server)
    }
    override suspend fun delete(id: UUID) {
        store.remove(id)
        flow.value = store.values.sortedBy { it.sortOrder }
    }

    override suspend fun reorder(serverIdToOrder: Map<UUID, Int>) {
        serverIdToOrder.forEach { (id, order) ->
            store[id]?.let { store[id] = it.copy(sortOrder = order) }
        }
        flow.value = store.values.sortedBy { it.sortOrder }
    }

    override suspend fun updateLastConnected(id: UUID, at: Instant) {
        lastConnectedUpdates += id
        store[id]?.let { store[id] = it.copy(lastConnected = at) }
    }

    override suspend fun updatePing(id: UUID, pingMs: Int?) {
        store[id]?.let { store[id] = it.copy(pingMs = pingMs) }
    }
}

class FakeKeyPairRepository : KeyPairRepository {
    private val store = ConcurrentHashMap<UUID, KeyPair>()
    private val flow = MutableStateFlow<List<KeyPair>>(emptyList())

    override fun observeAll(): Flow<List<KeyPair>> = flow.asStateFlow()
    override suspend fun getById(id: UUID): KeyPair? = store[id]
    override suspend fun insert(keyPair: KeyPair) {
        store[keyPair.id] = keyPair
        flow.value = store.values.toList()
    }

    override suspend fun delete(id: UUID) {
        store.remove(id)
        flow.value = store.values.toList()
    }
}

class FakeSecretVault : SecretVault {
    private val store = ConcurrentHashMap<String, ByteArray>()
    var locked: Boolean = false

    override suspend fun isUnlocked(): Boolean = !locked
    override suspend fun store(alias: String, secret: ByteArray) {
        if (locked) throw VaultLockedException()
        store[alias] = secret
    }

    override suspend fun load(alias: String): ByteArray? {
        if (locked) throw VaultLockedException()
        return store[alias]
    }

    override suspend fun delete(alias: String) { store.remove(alias) }
    override suspend fun exists(alias: String): Boolean = store.containsKey(alias)
}
