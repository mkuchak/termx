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
 * One rendered section of the list: a header ([group] `null` = "Ungrouped")
 * followed by its [servers] in `sortOrder`. [isCollapsed] mirrors
 * [ServerGroup.isCollapsed] so the UI can collapse the server rows without
 * dropping the header row itself.
 */
data class GroupedServers(
    val group: ServerGroup?,
    val servers: List<Server>,
    val isCollapsed: Boolean = false,
)
