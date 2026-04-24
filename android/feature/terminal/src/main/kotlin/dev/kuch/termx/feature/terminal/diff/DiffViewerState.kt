package dev.kuch.termx.feature.terminal.diff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format mirror of the JSON termxd writes at
 * `~/.termx/diffs/<uuid>.json` via its PostToolUse hook.
 *
 * The field names match the Go struct in
 * `termxd/cmd/hook_posttooluse.go:diffRecord` exactly so a kotlinx-
 * serialization decode round-trips without custom mappers. `ignoreUnknownKeys`
 * at the Json site guards against future fields.
 */
@Serializable
data class DiffPayload(
    val id: String,
    val ts: String,
    val session: String,
    @SerialName("file_path") val filePath: String,
    val tool: String,
    val before: String = "",
    val after: String = "",
    @SerialName("unified_diff") val unifiedDiff: String = "",
)

/**
 * UI state for [DiffViewerScreen]. A small sealed family:
 *
 *  - [Loading]: fetch in flight.
 *  - [Loaded]: diff JSON fully parsed and ready to render.
 *  - [Error]: fetch or parse failed; message is safe to show verbatim.
 */
sealed class DiffViewerState {
    data object Loading : DiffViewerState()
    data class Loaded(val payload: DiffPayload) : DiffViewerState()
    data class Error(val message: String) : DiffViewerState()
}
