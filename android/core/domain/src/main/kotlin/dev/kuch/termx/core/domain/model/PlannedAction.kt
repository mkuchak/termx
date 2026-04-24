package dev.kuch.termx.core.domain.model

/**
 * One row of the `termx install --dry-run` preview the phone renders before the
 * user confirms the real install.
 *
 * The structure is intentionally loose — [type] mirrors whatever tag termxd
 * emits (`mkdir`, `write_file`, `inject_block`, `install_binary`,
 * `update_json`, ...). [extras] captures the rest of the fields (mode, diff,
 * note) without coupling the UI layer to any particular schema version: if
 * termxd adds a new per-type field tomorrow, the parser stuffs it into
 * [extras] and the composable can render it without a model bump.
 *
 * [description] is a pre-computed, human-readable summary the UI shows inline
 * ("~/.termx/ — already exists"). The phone does not attempt to re-pretty-print
 * diffs; it just surfaces whatever termxd computed.
 */
data class PlannedAction(
    val type: String,
    val path: String?,
    val description: String?,
    val extras: Map<String, String> = emptyMap(),
)
