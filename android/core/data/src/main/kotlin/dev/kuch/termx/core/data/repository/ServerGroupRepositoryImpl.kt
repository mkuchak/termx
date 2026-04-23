package dev.kuch.termx.core.data.repository

import androidx.room.withTransaction
import dev.kuch.termx.core.data.db.AppDatabase
import dev.kuch.termx.core.data.db.dao.ServerGroupDao
import dev.kuch.termx.core.data.db.mapper.toDomain
import dev.kuch.termx.core.data.db.mapper.toEntity
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class ServerGroupRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: ServerGroupDao,
) : ServerGroupRepository {

    override fun observeAll(): Flow<List<ServerGroup>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(group: ServerGroup) {
        dao.upsert(group.toEntity())
    }

    override suspend fun delete(id: UUID) {
        dao.delete(id)
    }

    override suspend fun reorder(groupIdToOrder: Map<UUID, Int>) {
        db.withTransaction {
            groupIdToOrder.forEach { (id, order) -> dao.updateOrder(id, order) }
        }
    }
}
