package dev.kuch.termx.libs.companion.events

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {

    private val parser = EventParser()

    private fun parseOne(line: String): TermxEvent =
        parser.decodeLine(line) ?: error("expected a decoded event for: $line")

    @Test
    fun `parses session_created`() {
        val line = """{"type":"session_created","ts":"2026-04-23T12:00:00Z","session":"s1"}"""
        val ev = parseOne(line) as TermxEvent.SessionCreated
        assertEquals("s1", ev.session)
        assertEquals(Instant.parse("2026-04-23T12:00:00Z"), ev.ts)
    }

    @Test
    fun `parses session_closed`() {
        val line = """{"type":"session_closed","ts":"2026-04-23T12:01:00Z","session":"s2"}"""
        val ev = parseOne(line) as TermxEvent.SessionClosed
        assertEquals("s2", ev.session)
    }

    @Test
    fun `parses shell_command_long with pwd`() {
        val line = """{"type":"shell_command_long","ts":"2026-04-23T12:02:00Z","session":"s3",""" +
            """"cmd":"cargo build","duration_ms":45000,"exit_code":0,"pwd":"/home/u/proj"}"""
        val ev = parseOne(line) as TermxEvent.ShellCommandLong
        assertEquals("cargo build", ev.cmd)
        assertEquals(45000L, ev.durationMs)
        assertEquals(0, ev.exitCode)
        assertEquals("/home/u/proj", ev.pwd)
    }

    @Test
    fun `parses shell_command_long with null pwd`() {
        val line = """{"type":"shell_command_long","ts":"2026-04-23T12:02:00Z","session":"s3",""" +
            """"cmd":"sleep 10","duration_ms":10000,"exit_code":0,"pwd":null}"""
        val ev = parseOne(line) as TermxEvent.ShellCommandLong
        assertEquals(null, ev.pwd)
    }

    @Test
    fun `parses shell_command_error`() {
        val line = """{"type":"shell_command_error","ts":"2026-04-23T12:03:00Z","session":"s4",""" +
            """"cmd":"false","duration_ms":12,"exit_code":1,"pwd":"/tmp"}"""
        val ev = parseOne(line) as TermxEvent.ShellCommandError
        assertEquals(1, ev.exitCode)
        assertEquals("false", ev.cmd)
    }

    @Test
    fun `parses permission_requested with tool_args object`() {
        val line = """{"type":"permission_requested","ts":"2026-04-23T12:04:00Z","session":"s5",""" +
            """"request_id":"r1","tool_name":"Bash","tool_args":{"cmd":"ls /etc"}}"""
        val ev = parseOne(line) as TermxEvent.PermissionRequested
        assertEquals("r1", ev.requestId)
        assertEquals("Bash", ev.toolName)
        assertEquals("ls /etc", ev.toolArgs.jsonObject["cmd"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parses permission_resolved with null reason`() {
        val line = """{"type":"permission_resolved","ts":"2026-04-23T12:05:00Z","session":"s6",""" +
            """"request_id":"r2","decision":"allow","reason":null}"""
        val ev = parseOne(line) as TermxEvent.PermissionResolved
        assertEquals("allow", ev.decision)
        assertEquals(null, ev.reason)
    }

    @Test
    fun `parses permission_resolved with reason`() {
        val line = """{"type":"permission_resolved","ts":"2026-04-23T12:05:00Z","session":"s6",""" +
            """"request_id":"r3","decision":"deny","reason":"unsafe"}"""
        val ev = parseOne(line) as TermxEvent.PermissionResolved
        assertEquals("deny", ev.decision)
        assertEquals("unsafe", ev.reason)
    }

    @Test
    fun `parses diff_created`() {
        val line = """{"type":"diff_created","ts":"2026-04-23T12:06:00Z","session":"s7",""" +
            """"diff_id":"d1","file_path":"/src/main.kt","tool":"Edit"}"""
        val ev = parseOne(line) as TermxEvent.DiffCreated
        assertEquals("d1", ev.diffId)
        assertEquals("/src/main.kt", ev.filePath)
        assertEquals("Edit", ev.tool)
    }

    @Test
    fun `parses claude_idle`() {
        val line = """{"type":"claude_idle","ts":"2026-04-23T12:07:00Z","session":"s8"}"""
        val ev = parseOne(line) as TermxEvent.ClaudeIdle
        assertEquals("s8", ev.session)
    }

    @Test
    fun `parses claude_working`() {
        val line = """{"type":"claude_working","ts":"2026-04-23T12:08:00Z","session":"s9"}"""
        val ev = parseOne(line) as TermxEvent.ClaudeWorking
        assertEquals("s9", ev.session)
    }

    @Test
    fun `malformed json produces Unknown with type malformed`() {
        val line = """{"type":"session_created","ts":"not-a-timestamp","""
        val ev = parseOne(line) as TermxEvent.Unknown
        assertEquals("malformed", ev.type)
        assertTrue(ev.raw.contains("not-a-timestamp"))
    }

    @Test
    fun `unknown event type produces Unknown with extracted type`() {
        val line = """{"type":"future_event","ts":"2026-04-23T12:09:00Z","session":"sX","extra":42}"""
        val ev = parseOne(line) as TermxEvent.Unknown
        assertEquals("future_event", ev.type)
        assertTrue(ev.raw.contains("future_event"))
    }

    @Test
    fun `flow parse skips blank lines`() = runTest {
        val lines = flowOf(
            "",
            """{"type":"claude_idle","ts":"2026-04-23T12:07:00Z","session":"s1"}""",
            "   ",
            """{"type":"claude_working","ts":"2026-04-23T12:08:00Z","session":"s1"}""",
            "",
        )
        val events = parser.parse(lines).toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is TermxEvent.ClaudeIdle)
        assertTrue(events[1] is TermxEvent.ClaudeWorking)
    }

    @Test
    fun `flow parse emits Unknown inline without breaking the stream`() = runTest {
        val lines = flowOf(
            """{"type":"claude_idle","ts":"2026-04-23T12:07:00Z","session":"s1"}""",
            """{"type":"future_event","ts":"2026-04-23T12:08:00Z","session":"s1"}""",
            """{"type":"claude_working","ts":"2026-04-23T12:09:00Z","session":"s1"}""",
        )
        val events = parser.parse(lines).toList()
        assertEquals(3, events.size)
        assertTrue(events[0] is TermxEvent.ClaudeIdle)
        val unknown = events[1] as TermxEvent.Unknown
        assertEquals("future_event", unknown.type)
        assertNotNull(events[2] as TermxEvent.ClaudeWorking)
    }
}
