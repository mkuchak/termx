package dev.kuch.termx.feature.servers

import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup

/**
 * UI state for [ServerListScreen].
 *
 * The screen transitions from [Loading] → either [Empty] or [Loaded] the
 * moment the combined servers + groups flow produces its first emission.
 * [Error] is reserved for catastrophic Room failures — in practice the
 * happy path never leaves [Loaded] once populated.
 */
sealed interface ServerListUiState {
    data object Loading : ServerListUiState

    data object Empty : ServerListUiState

    data class Loaded(val groupsWithServers: List<GroupedServers>) : ServerListUiState

    data class Error(val message: String) : ServerListUiState
}

/**
 * One rendered section of the list: an uppercase section label ([group]
 * `null` = "Ungrouped", rendered last) followed by its [servers] in
 * `sortOrder`. Sections are flat and always expanded since the Task #46
 * Moshi-style pivot — [ServerGroup.isCollapsed] persists in Room but no
 * longer drives this screen.
 */
data class GroupedServers(
    val group: ServerGroup?,
    val servers: List<Server>,
)
