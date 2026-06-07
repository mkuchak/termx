package dev.kuch.termx.libs.companion.events

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks down the `agent_finished` wire contract that the VPS-side termxd
 * watcher emits when an AI agent under the herdr multiplexer finishes.
 *
 * Round-trips through the exact [Json] instance [EventParser] decodes with so
 * the canonical contract and the parser stay in lockstep.
 */
class AgentFinishedSerializationTest {

    private val json: Json = EventParser.DEFAULT_JSON

    @Test
    fun `AgentFinished round-trips through the parser Json`() {
        val original = TermxEvent.AgentFinished(
            ts = Instant.parse("2026-06-07T00:00:00Z"),
            session = "1-1",
            source = "herdr",
            agent = "claude",
            workspace = "proj",
        )
        val encoded = json.encodeToString(TermxEvent.serializer(), original)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("agent_finished", obj["type"]?.jsonPrimitive?.content)
        assertEquals("herdr", obj["source"]?.jsonPrimitive?.content)
        assertEquals("claude", obj["agent"]?.jsonPrimitive?.content)
        assertEquals("proj", obj["workspace"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromString(TermxEvent.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `AgentFinished defaults workspace to null`() {
        val original = TermxEvent.AgentFinished(
            ts = Instant.parse("2026-06-07T00:00:00Z"),
            session = "2-3",
            source = "herdr",
            agent = "claude",
        )
        val encoded = json.encodeToString(TermxEvent.serializer(), original)
        val decoded = json.decodeFromString(TermxEvent.serializer(), encoded)
            as TermxEvent.AgentFinished
        assertNull(decoded.workspace)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes raw agent_finished line via type discriminator`() {
        val raw =
            """{"type":"agent_finished","ts":"2026-06-07T00:00:00Z","session":"1-1",""" +
                """"agent":"claude","workspace":"proj","source":"herdr"}"""
        val decoded = json.decodeFromString(TermxEvent.serializer(), raw)
            as TermxEvent.AgentFinished
        assertEquals(Instant.parse("2026-06-07T00:00:00Z"), decoded.ts)
        assertEquals("1-1", decoded.session)
        assertEquals("claude", decoded.agent)
        assertEquals("proj", decoded.workspace)
        assertEquals("herdr", decoded.source)
    }

    @Test
    fun `decodes raw agent_finished line with workspace omitted`() {
        val raw =
            """{"type":"agent_finished","ts":"2026-06-07T00:00:00Z","session":"4-2",""" +
                """"agent":"claude","source":"herdr"}"""
        val decoded = json.decodeFromString(TermxEvent.serializer(), raw)
            as TermxEvent.AgentFinished
        assertNull(decoded.workspace)
        assertEquals("herdr", decoded.source)
    }
}
