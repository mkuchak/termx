package dev.kuch.termx.libs.companion.events

/**
 * Accumulates chunks of text (typically arriving from a chunked byte
 * source like an SSH channel) and splits them into complete newline-terminated
 * lines. Partial trailing content is retained until the next chunk completes
 * it.
 *
 * Not thread-safe: callers must serialize access (a single collector coroutine
 * is the expected usage).
 */
class NdjsonBuffer {
    private val buf = StringBuilder()

    /**
     * Append [chunk] and return any complete lines newly available. The
     * returned strings do NOT include the trailing `\n`. A standalone `\r`
     * before the `\n` is stripped so that CRLF-terminated lines work too.
     */
    fun append(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        buf.append(chunk)
        val result = mutableListOf<String>()
        while (true) {
            val idx = buf.indexOf('\n')
            if (idx < 0) break
            val endExclusive = if (idx > 0 && buf[idx - 1] == '\r') idx - 1 else idx
            result.add(buf.substring(0, endExclusive))
            buf.delete(0, idx + 1)
        }
        return result
    }

    /**
     * Return whatever is currently buffered and clear the buffer. Call this
     * when the upstream source closes without a trailing newline and you
     * still want the last line delivered.
     */
    fun flushRemaining(): String? =
        if (buf.isEmpty()) null else buf.toString().also { buf.clear() }
}
