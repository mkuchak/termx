package dev.kuch.termx.core.data.remote

import dev.kuch.termx.core.domain.model.PlannedAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts `termx install --dry-run` stdout (see termxd/cmd/install.go
 * `dryRunReport`) into a flat list of [PlannedAction]s the UI can render.
 *
 * Schema (per Task #30):
 *
 * ```
 * {"changes":[
 *   {"type":"mkdir","path":"~/.termx","mode":"0700","note":"already exists"},
 *   {"type":"write_file","path":"~/.termx/events.ndjson","mode":"0600"},
 *   {"type":"install_binary","path":"~/.local/bin/termx","mode":"0755"},
 *   {"type":"inject_block","path":"~/.bashrc","diff":"+ 2 lines ..."},
 *   {"type":"update_json","path":"~/.claude/settings.json","diff":"..."}
 * ]}
 * ```
 *
 *  - [PlannedAction.description] is derived from `note` or `diff` (note takes
 *    priority because it's the richer human signal — "already exists" beats a
 *    generic diff fragment).
 *  - Unknown per-type fields fall into [PlannedAction.extras] so we don't have
 *    to rev the parser when termxd grows a new field.
 *
 *  The parser is intentionally forgiving: a missing `changes` key yields an
 *  empty list, not an exception, so the UI has something to render even when
 *  termxd emits an odd version of the report.
 */
object DryRunParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawJson: String): List<PlannedAction> {
        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()
        val obj = (root as? JsonObject) ?: return emptyList()
        val changes = (obj["changes"] as? JsonArray) ?: return emptyList()
        return changes.mapNotNull { element ->
            val row = (element as? JsonObject) ?: return@mapNotNull null
            val type = row["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type.isBlank()) return@mapNotNull null

            val path = row["path"]?.jsonPrimitive?.contentOrNull
            val note = row["note"]?.jsonPrimitive?.contentOrNull
            val diff = row["diff"]?.jsonPrimitive?.contentOrNull
            val description = note ?: diff

            val extras = buildMap {
                for ((k, v) in row) {
                    if (k == "type" || k == "path") continue
                    val prim = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    if (prim != null) put(k, prim)
                }
            }
            PlannedAction(
                type = type,
                path = path,
                description = description,
                extras = extras,
            )
        }
    }
}
