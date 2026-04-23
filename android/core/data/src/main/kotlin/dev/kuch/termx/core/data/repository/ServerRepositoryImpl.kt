package dev.kuch.termx.core.data.repository

import androidx.room.withTransaction
import dev.kuch.termx.core.data.db.AppDatabase
import dev.kuch.termx.core.data.db.dao.ServerDao
import dev.kuch.termx.core.data.db.mapper.toDomain
import dev.kuch.termx.core.data.db.mapper.toEntity
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.repository.ServerRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class ServerRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: ServerDao,
) : ServerRepository {

    override fun observeAll(): Flow<List<Server>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeByGroup(groupId: UUID?): Flow<List<Server>> =
        dao.observeByGroup(groupId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: UUID): Server? =
        dao.getById(id)?.toDomain()

    override suspend fun upsert(server: Server) {
        dao.upsert(server.toEntity())
    }

    override suspend fun delete(id: UUID) {
        dao.delete(id)
    }

    /**
     * Apply every order update inside one transaction so a drag-to-reorder
     * commit either fully succeeds or fully rolls back — half-reordered
     * lists are never observed.
     */
    override suspend fun reorder(serverIdToOrder: Map<UUID, Int>) {
        db.withTransaction {
            serverIdToOrder.forEach { (id, order) -> dao.updateOrder(id, order) }
        }
    }

    override suspend fun updateLastConnected(id: UUID, at: Instant) {
        dao.updateLastConnected(id, at)
    }

    override suspend fun updatePing(id: UUID, pingMs: Int?) {
        dao.updatePing(id, pingMs)
    }
}
