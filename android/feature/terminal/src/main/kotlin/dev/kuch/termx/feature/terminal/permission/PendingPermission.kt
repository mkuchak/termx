package dev.kuch.termx.feature.terminal.permission

import java.util.UUID
import kotlinx.serialization.json.JsonElement

/**
 * UI-facing snapshot of one blocked [dev.kuch.termx.libs.companion.events.TermxEvent.PermissionRequested].
 *
 * The broker keeps a flat list of these keyed by [approvalId] in its
 * [kotlinx.coroutines.flow.StateFlow]; the dialog renders the first one
 * and the user's Approve/Deny tap removes it from the list.
 *
 * [toolArgs] is the raw JSON element from the event payload. The dialog
 * pretty-prints it with a kotlinx-serialization pretty encoder so the
 * user sees the actual shape Claude asked Claude-Code to perform — no
 * UI-specific copy that could drift from the real tool invocation.
 */
data class PendingPermission(
    val serverId: UUID,
    val serverLabel: String,
    val sessionName: String,
    val approvalId: String,
    val toolName: String,
    val toolArgs: JsonElement,
)
