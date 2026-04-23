package dev.kuch.termx.core.data.repository

import dev.kuch.termx.core.data.db.dao.KeyPairDao
import dev.kuch.termx.core.data.db.mapper.toDomain
import dev.kuch.termx.core.data.db.mapper.toEntity
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class KeyPairRepositoryImpl @Inject constructor(
    private val dao: KeyPairDao,
) : KeyPairRepository {

    override fun observeAll(): Flow<List<KeyPair>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: UUID): KeyPair? =
        dao.getById(id)?.toDomain()

    override suspend fun insert(keyPair: KeyPair) {
        dao.insert(keyPair.toEntity())
    }

    override suspend fun delete(id: UUID) {
        dao.delete(id)
    }
}
