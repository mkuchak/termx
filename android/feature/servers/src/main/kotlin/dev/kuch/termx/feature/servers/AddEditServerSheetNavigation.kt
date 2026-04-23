package dev.kuch.termx.feature.servers

import java.util.UUID

/**
 * Navigation helpers for [AddEditServerSheet].
 *
 * The sheet is intentionally *not* a first-class destination in
 * `TermxNavHost` — it's modal and overlays whatever screen launched it (the
 * server list, the setup wizard, deep links, etc.). Task #21 wires the
 * actual presentation; this file only holds the route name constants so
 * every caller agrees on the same string.
 *
 * Route patterns:
 *  - [ADD_SERVER_ROUTE]: `servers/new` — launches the sheet in add mode.
 *  - [EDIT_SERVER_ROUTE]: `servers/edit/{serverId}` — launches in edit mode.
 */
object AddEditServerSheetRoute {
    const val ADD_SERVER_ROUTE: String = "servers/new"
    const val EDIT_SERVER_ROUTE_PATTERN: String = "servers/edit/{serverId}"

    const val ARG_SERVER_ID: String = "serverId"

    fun editRoute(id: UUID): String = "servers/edit/$id"
}
