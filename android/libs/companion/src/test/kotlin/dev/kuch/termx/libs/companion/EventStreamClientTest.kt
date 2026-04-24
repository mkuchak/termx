package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.companion.events.CompanionCommand
import dev.kuch.termx.libs.companion.events.SessionState
import dev.kuch.termx.libs.companion.events.TermxEvent
import dev.kuch.termx.libs.companion.fakes.FakeExecChannel
import dev.kuch.termx.libs.companion.fakes.FakeSftpClient
import dev.kuch.termx.libs.companion.fakes.FakeSshSession
import dev.kuch.termx.libs.companion.fakes.bytesFlowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventStreamClientTest {

    private val tailCommand = "tail -F --lines=0 ~/.termx/events.ndjson"

    @Test
    fun `emits a single SessionCreated event from a canned NDJSON chunk`() = runTest {
        val session = FakeSshSession()
        session.queueExec(tailCommand) {
            FakeExecChannel(
                stdout = bytesFlowOf(
                    """{"type":"session_created","ts":"2026-04-23T12:00:00Z","session":"main"}""" + "\n",
                ),
            )
        }
        val client = EventStreamClient(session)

        val event = client.stream().take(1).first()

        assertTrue(event is TermxEvent.SessionCreated)
        assertEquals("main", (event as TermxEvent.SessionCreated).session)
    }

    @Test
    fun `reassembles a line split across two chunks`() = runTest {
        val session = FakeSshSession()
        session.queueExec(tailCommand) {
            FakeExecChannel(
                stdout = bytesFlowOf(
                    """{"type":"session_c""",
                    """reated","ts":"2026-04-23T12:00:00Z","session":"main"}""" + "\n",
                    """{"type":"session_closed","ts":"2026-04-23T12:01:00Z","session":"main"}""" + "\n",
                ),
            )
        }
        val client = EventStreamClient(session)

        val events = client.stream().take(2).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is TermxEvent.SessionCreated)
        assertTrue(events[1] is TermxEvent.SessionClosed)
    }

    @Test
    fun `reconnects after an exec failure and keeps emitting`() = runTest {
        val session = FakeSshSession()
        // First tail attempt throws immediately — simulates tail-not-installed
        // or a dead transport. Second attempt succeeds with a real payload.
        session.queueExec(tailCommand) { error("boom") }
        session.queueExec(tailCommand) {
            FakeExecChannel(
                stdout = bytesFlowOf(
                    """{"type":"claude_idle","ts":"2026-04-23T12:00:00Z","session":"main"}""" + "\n",
                ),
            )
        }
        val client = EventStreamClient(session)

        val event = client.stream().take(1).first()

        assertTrue(event is TermxEvent.ClaudeIdle)
        val callCount = session.execCallCounts[tailCommand]?.get() ?: 0
        assertTrue("expected at least 2 tail attempts, got $callCount", callCount >= 2)
    }

    @Test
    fun `errors SharedFlow surfaces transient exec failures`() = runTest {
        val session = FakeSshSession()
        session.queueExec(tailCommand) { error("transport reset") }
        session.queueExec(tailCommand) {
            FakeExecChannel(
                stdout = bytesFlowOf(
                    """{"type":"claude_working","ts":"2026-04-23T12:00:00Z","session":"s"}""" + "\n",
                ),
            )
        }
        val client = EventStreamClient(session)

        // Subscribe to `errors` BEFORE the stream fires so the tryEmit
        // from the first failed tail attempt lands in the SharedFlow.
        val error: Throwable = coroutineScope {
            val errorsDeferred = async { client.errors.first() }
            // Drive the stream until it produces its first event — that
            // happens on the second (successful) tail attempt, which means
            // the first failure has already emitted to `errors`.
            client.stream().take(1).first()
            errorsDeferred.await()
        }

        assertNotNull(error)
        assertTrue(
            "unexpected error message: ${error.message}",
            error.message?.contains("transport reset") == true,
        )
    }

    @Test
    fun `loadSessionRegistry parses every JSON file in sessions dir`() = runTest {
        val session = FakeSshSession()
        val home = "/home/alice"
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf(home))
        }
        val sftp = FakeSftpClient()
        sftp.listings["$home/.termx/sessions"] = listOf("main.json", "scratch.json", "README.md")
        sftp.reads["$home/.termx/sessions/main.json"] = """
            {"name":"main","created_at":"2026-04-23T12:00:00Z","windows":3,"status":"working","claude":true}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        sftp.reads["$home/.termx/sessions/scratch.json"] = """
            {"name":"scratch","created_at":"2026-04-23T11:00:00Z","windows":1,"status":"idle","claude":false}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        val states = client.loadSessionRegistry()

        assertEquals(2, states.size)
        val main = states.single { it.name == "main" }
        assertEquals(3, main.windows)
        assertEquals("working", main.status)
        assertTrue(main.claude)
        val scratch = states.single { it.name == "scratch" }
        assertEquals("idle", scratch.status)
        assertEquals(1, scratch.windows)
    }

    @Test
    fun `loadSessionRegistry resolves HOME exactly once across multiple calls`() = runTest {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf("/home/bob"))
        }
        val sftp = FakeSftpClient()
        sftp.listings["/home/bob/.termx/sessions"] = emptyList()
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        client.loadSessionRegistry()
        client.loadSessionRegistry()

        // Only one HOME exec call should have been made.
        val homeCalls = session.execCallCounts["printf %s \"\$HOME\""]?.get() ?: 0
        assertEquals(1, homeCalls)
    }

    @Test
    fun `sendCommand writes approve_permission JSON to commands dir`() = runTest {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf("/home/eve"))
        }
        val sftp = FakeSftpClient()
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        val cmd = CompanionCommand.ApprovePermission(
            id = "cmd-1",
            requestId = "req-42",
            remember = true,
        )

        client.sendCommand(cmd)

        val path = "/home/eve/.termx/commands/cmd-1.json"
        val bytes = sftp.writes[path]
        assertNotNull("expected a write at $path", bytes)
        val decoded = Json.parseToJsonElement(bytes!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("approve_permission", decoded["type"]?.jsonPrimitive?.content)
        assertEquals("cmd-1", decoded["id"]?.jsonPrimitive?.content)
        assertEquals("req-42", decoded["request_id"]?.jsonPrimitive?.content)
        assertEquals("true", decoded["remember"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sendCommand round-trips InjectPrompt payload`() = runTest {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf("/root"))
        }
        val sftp = FakeSftpClient()
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        val cmd = CompanionCommand.InjectPrompt(
            id = "cmd-2",
            session = "main",
            text = "hello from phone",
        )

        client.sendCommand(cmd)

        val bytes = sftp.writes["/root/.termx/commands/cmd-2.json"]
        assertNotNull(bytes)
        val decoded = Json.parseToJsonElement(bytes!!.toString(Charsets.UTF_8)).jsonObject
        assertEquals("inject_prompt", decoded["type"]?.jsonPrimitive?.content)
        assertEquals("main", decoded["session"]?.jsonPrimitive?.content)
        assertEquals("hello from phone", decoded["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sendCommand uses tmp-then-rename so readers never see a partial file`() = runTest {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf("/home/atomic"))
        }
        val sftp = FakeSftpClient()
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        client.sendCommand(
            CompanionCommand.InjectPrompt(
                id = "cmd-atomic",
                session = "main",
                text = "atomic",
            ),
        )

        val finalPath = "/home/atomic/.termx/commands/cmd-atomic.json"
        val rename = synchronized(sftp.renames) { sftp.renames.toList() }.single()
        assertTrue(
            "expected tmp src under commands dir: ${rename.first}",
            rename.first.startsWith("$finalPath$TEMP_SUFFIX_SEPARATOR"),
        )
        assertEquals(finalPath, rename.second)
        // After the rename, the bytes must live at the final path (not the tmp).
        assertNotNull(sftp.writes[finalPath])
        assertTrue(sftp.writes.none { it.key.startsWith("$finalPath$TEMP_SUFFIX_SEPARATOR") })
    }

    @Test
    fun `loadSessionRegistry recovers when a single file is corrupt`() = runTest {
        val session = FakeSshSession()
        session.queueExec("printf %s \"\$HOME\"") {
            FakeExecChannel(stdout = bytesFlowOf("/home/u"))
        }
        val sftp = FakeSftpClient()
        sftp.listings["/home/u/.termx/sessions"] = listOf("good.json", "bad.json")
        sftp.reads["/home/u/.termx/sessions/good.json"] =
            """{"name":"good","created_at":"2026-04-23T12:00:00Z","windows":1,"status":"idle","claude":false}"""
                .toByteArray(Charsets.UTF_8)
        sftp.reads["/home/u/.termx/sessions/bad.json"] = "{not-json".toByteArray(Charsets.UTF_8)
        session.setSftpFactory { sftp }
        val client = EventStreamClient(session)

        val states = client.loadSessionRegistry()

        assertEquals(1, states.size)
        assertEquals("good", states[0].name)
    }

    @Test
    fun `ignores blank lines in the tail stream`() = runTest {
        val session = FakeSshSession()
        session.queueExec(tailCommand) {
            FakeExecChannel(
                stdout = bytesFlowOf(
                    "\n\n",
                    """{"type":"claude_idle","ts":"2026-04-23T12:00:00Z","session":"s"}""" + "\n",
                    "   \n",
                    """{"type":"claude_working","ts":"2026-04-23T12:01:00Z","session":"s"}""" + "\n",
                ),
            )
        }
        val client = EventStreamClient(session)

        val events = client.stream().take(2).toList()

        assertTrue(events[0] is TermxEvent.ClaudeIdle)
        assertTrue(events[1] is TermxEvent.ClaudeWorking)
    }

    @Test
    fun `session state default Json ignores unknown keys`() {
        val raw = """
            {"name":"a","created_at":"2026-04-23T12:00:00Z","windows":1,"status":"idle","claude":false,"new_field":"x"}
        """.trimIndent()
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<SessionState>(raw)
        assertEquals("a", decoded.name)
    }
}
