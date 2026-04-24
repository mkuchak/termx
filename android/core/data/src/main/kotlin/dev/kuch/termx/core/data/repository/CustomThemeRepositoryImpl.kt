package dev.kuch.termx.core.data.repository

import dev.kuch.termx.core.data.db.dao.CustomThemeDao
import dev.kuch.termx.core.data.db.entity.CustomThemeEntity
import dev.kuch.termx.core.domain.repository.CustomThemeRepository
import dev.kuch.termx.core.domain.theme.TerminalTheme
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serializes [TerminalTheme] into the [CustomThemeEntity] blob shape and
 * back out again. Kept internal to `:core:data` so the JSON encoding
 * never leaks past the repository boundary — domain consumers just see
 * [TerminalTheme] instances.
 */
class CustomThemeRepositoryImpl @Inject constructor(
    private val dao: CustomThemeDao,
) : CustomThemeRepository {

    override fun observeAll(): Flow<List<TerminalTheme>> =
        dao.observeAll().map { rows -> rows.map(::entityToDomain) }

    override suspend fun upsert(theme: TerminalTheme) {
        dao.upsert(domainToEntity(theme))
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun domainToEntity(theme: TerminalTheme): CustomThemeEntity {
        val payload = CustomThemePayload(
            background = theme.background,
            foreground = theme.foreground,
            cursor = theme.cursor,
            ansi = theme.ansi,
        )
        return CustomThemeEntity(
            id = theme.id,
            displayName = theme.displayName,
            colorsJson = JSON.encodeToString(CustomThemePayload.serializer(), payload),
            createdAt = Instant.now(),
        )
    }

    private fun entityToDomain(entity: CustomThemeEntity): TerminalTheme {
        val payload = JSON.decodeFromString(
            CustomThemePayload.serializer(),
            entity.colorsJson,
        )
        return TerminalTheme(
            id = entity.id,
            displayName = entity.displayName,
            background = payload.background,
            foreground = payload.foreground,
            cursor = payload.cursor,
            ansi = payload.ansi,
        )
    }

    @Serializable
    private data class CustomThemePayload(
        val background: Long,
        val foreground: Long,
        val cursor: Long,
        val ansi: List<Long>,
    )

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
