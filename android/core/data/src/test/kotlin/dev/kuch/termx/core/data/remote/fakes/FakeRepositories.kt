package dev.kuch.termx.core.data.remote.fakes

import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory fake of [ServerRepository]. Tests use [put] to pre-seed and
 * inspect [upserts] / [deletes] to assert write-side behavior.
 *
 * Hand-rolled (no MockK) so the seams stay readable and survive library
 * churn — see the QA.2 task notes.
 */
class FakeServerRepository : ServerRepository {
    private val store = ConcurrentHashMap<UUID, Server>()
    private val flow = MutableStateFlow<List<Server>>(emptyList())
    val upserts = mutableListOf<Server>()
    val deletes = mutableListOf<UUID>()

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
        deletes += id
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
        store[id]?.let { store[id] = it.copy(lastConnected = at) }
    }

    override suspend fun updatePing(id: UUID, pingMs: Int?) {
        store[id]?.let { store[id] = it.copy(pingMs = pingMs) }
    }
}

class FakeKeyPairRepository : KeyPairRepository {
    private val store = ConcurrentHashMap<UUID, KeyPair>()
    private val flow = MutableStateFlow<List<KeyPair>>(emptyList())
    val inserts = mutableListOf<KeyPair>()

    fun put(keyPair: KeyPair) {
        store[keyPair.id] = keyPair
        flow.value = store.values.toList()
    }

    override fun observeAll(): Flow<List<KeyPair>> = flow.asStateFlow()

    override suspend fun getById(id: UUID): KeyPair? = store[id]

    override suspend fun insert(keyPair: KeyPair) {
        inserts += keyPair
        put(keyPair)
    }

    override suspend fun delete(id: UUID) {
        store.remove(id)
        flow.value = store.values.toList()
    }
}

class FakeServerGroupRepository : ServerGroupRepository {
    private val flow = MutableStateFlow<List<ServerGroup>>(emptyList())

    override fun observeAll(): Flow<List<ServerGroup>> = flow.asStateFlow()

    override suspend fun upsert(group: ServerGroup) {
        flow.value = (flow.value.filter { it.id != group.id } + group)
    }

    override suspend fun delete(id: UUID) {
        flow.value = flow.value.filter { it.id != id }
    }

    override suspend fun reorder(groupIdToOrder: Map<UUID, Int>) { /* unused */ }
}

/**
 * In-memory secret vault. Set [locked] = true to make [load] throw
 * [VaultLockedException] — the exact failure mode that tripped the
 * install flow before commit `bfa4364` surfaced "Vault is locked" cleanly.
 */
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

    override suspend fun delete(alias: String) {
        store.remove(alias)
    }

    override suspend fun exists(alias: String): Boolean = store.containsKey(alias)
}
