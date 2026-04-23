package dev.kuch.termx.core.domain.model

import java.util.UUID

/**
 * A user-defined folder that collects related [Server]s in the list UI.
 *
 * [isCollapsed] is persisted so the list preserves expand/collapse state
 * across launches.
 */
data class ServerGroup(
    val id: UUID,
    val name: String,
    val sortOrder: Int = 0,
    val isCollapsed: Boolean = false,
)
