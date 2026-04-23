package dev.kuch.termx.libs.companion.events

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Feeds the canned 100-line `events-golden.ndjson` fixture through the
 * full parser pipeline and asserts both the total count and the per-type
 * counts. Acts as a regression guard against schema drift.
 */
class GoldenFileTest {

    private fun readGolden(): List<String> {
        val stream = javaClass.classLoader!!.getResourceAsStream("events-golden.ndjson")
        assertNotNull("events-golden.ndjson must be on the test classpath", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).useLines { it.toList() }
    }

    @Test
    fun `golden fixture has exactly 100 non-empty lines`() {
        val lines = readGolden().filter { it.isNotBlank() }
        assertEquals(100, lines.size)
    }

    @Test
    fun `parser decodes all 100 lines with expected type counts`() = runTest {
        val parser = EventParser()
        val source = flow {
            readGolden().forEach { emit(it) }
        }
        val events = parser.parse(source).toList()
        assertEquals(100, events.size)

        val counts = events.groupingBy { it::class.simpleName }.eachCount()
        assertEquals(10, counts["SessionCreated"])
        assertEquals(10, counts["SessionClosed"])
        assertEquals(10, counts["ShellCommandLong"])
        assertEquals(10, counts["ShellCommandError"])
        assertEquals(10, counts["PermissionRequested"])
        assertEquals(10, counts["PermissionResolved"])
        assertEquals(10, counts["DiffCreated"])
        assertEquals(10, counts["ClaudeIdle"])
        assertEquals(10, counts["ClaudeWorking"])
        // The `future_event` block exercises the Unknown fallback path.
        assertEquals(10, counts["Unknown"])

        // Every Unknown row should preserve its original type name.
        val unknowns = events.filterIsInstance<TermxEvent.Unknown>()
        assertEquals(10, unknowns.size)
        unknowns.forEach { assertEquals("future_event", it.type) }
    }

    @Test
    fun `byteFlowToEvents handles the golden fixture as a single chunk`() = runTest {
        val text = readGolden().joinToString("\n", postfix = "\n")
        val events = byteFlowToEvents(
            bytes = flow { emit(text.toByteArray(Charsets.UTF_8)) },
        ).toList()
        assertEquals(100, events.size)
    }

    @Test
    fun `byteFlowToEvents handles the golden fixture split into many small chunks`() = runTest {
        val text = readGolden().joinToString("\n", postfix = "\n")
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Emit in 37-byte slices — guaranteed to split many lines mid-content.
        val chunkSize = 37
        val events = byteFlowToEvents(
            bytes = flow {
                var i = 0
                while (i < bytes.size) {
                    val end = (i + chunkSize).coerceAtMost(bytes.size)
                    emit(bytes.copyOfRange(i, end))
                    i = end
                }
            },
        ).toList()
        assertEquals(100, events.size)
    }
}
