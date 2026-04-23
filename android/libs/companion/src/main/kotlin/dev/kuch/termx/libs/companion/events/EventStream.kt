package dev.kuch.termx.libs.companion.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * End-to-end helpers for turning a raw byte / string stream from the VPS
 * into a [Flow] of [TermxEvent]. Phase 4.6 will attach the SSH tail source
 * to these functions; tests can feed synthetic chunks.
 */

/**
 * Turn a chunked byte flow (e.g. an SSH channel tail) into decoded events.
 * Bytes are assumed to be UTF-8. Partial lines are buffered across chunks.
 */
fun byteFlowToEvents(
    bytes: Flow<ByteArray>,
    json: Json? = null,
): Flow<TermxEvent> {
    val parser = if (json != null) EventParser(json) else EventParser()
    val buffer = NdjsonBuffer()
    return flow {
        bytes.collect { chunk ->
            val text = chunk.toString(Charsets.UTF_8)
            for (line in buffer.append(text)) {
                parser.decodeLine(line)?.let { emit(it) }
            }
        }
        buffer.flushRemaining()?.let { tail ->
            parser.decodeLine(tail)?.let { emit(it) }
        }
    }
}

/**
 * Turn a chunked String flow into decoded events. Same behavior as
 * [byteFlowToEvents] but skips the UTF-8 decode step.
 */
fun stringChunkFlowToEvents(
    chunks: Flow<String>,
    json: Json? = null,
): Flow<TermxEvent> {
    val parser = if (json != null) EventParser(json) else EventParser()
    val buffer = NdjsonBuffer()
    return flow {
        chunks.collect { chunk ->
            for (line in buffer.append(chunk)) {
                parser.decodeLine(line)?.let { emit(it) }
            }
        }
        buffer.flushRemaining()?.let { tail ->
            parser.decodeLine(tail)?.let { emit(it) }
        }
    }
}
