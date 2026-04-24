package dev.kuch.termx.core.domain.repository

import dev.kuch.termx.core.domain.theme.TerminalTheme
import kotlinx.coroutines.flow.Flow

/**
 * Persistence surface for user-authored [TerminalTheme]s (Task #48).
 *
 * Implementations live in `:core:data` and serialize the 19 ARGB
 * long values into a JSON blob in the `custom_themes` table.
 */
interface CustomThemeRepository {
    fun observeAll(): Flow<List<TerminalTheme>>

    suspend fun upsert(theme: TerminalTheme)

    suspend fun delete(id: String)
}
