package dev.kuch.termx.libs.companion.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes a [Flow] of NDJSON lines into a [Flow] of [TermxEvent].
 *
 * Each line is an independent JSON object; the sealed-class polymorphism is
 * driven by the top-level `type` field (see [Json.classDiscriminator]).
 *
 * Malformed lines or unknown event types surface as [TermxEvent.Unknown] so
 * a single bad line cannot break the stream. Blank lines are skipped.
 */
class EventParser(
    private val json: Json = DEFAULT_JSON,
) {

    fun parse(lines: Flow<String>): Flow<TermxEvent> = flow {
        lines.collect { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@collect
            val event = decode(trimmed)
            emit(event)
        }
    }

    /** Decode a single NDJSON line. Exposed for callers that already framed lines. */
    fun decodeLine(line: String): TermxEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return decode(trimmed)
    }

    private fun decode(trimmed: String): TermxEvent =
        runCatching { json.decodeFromString<TermxEvent>(trimmed) }
            .getOrElse {
                TermxEvent.Unknown(
                    ts = Clock.System.now(),
                    session = "",
                    type = extractType(trimmed) ?: "malformed",
                    raw = trimmed,
                )
            }

    private fun extractType(raw: String): String? = runCatching {
        json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content
    }.getOrNull()

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
    }
}
