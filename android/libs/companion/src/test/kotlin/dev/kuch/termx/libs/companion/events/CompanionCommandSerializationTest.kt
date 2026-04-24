package dev.kuch.termx.libs.companion.events

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips each [CompanionCommand] subclass through the same `type`
 * discriminator that the VPS-side termxd uses for events, making sure the
 * wire format the Go reader expects stays stable.
 */
class CompanionCommandSerializationTest {

    private val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }

    @Test
    fun `ApprovePermission round-trips`() {
        val original = CompanionCommand.ApprovePermission(
            id = "a-1",
            requestId = "req-1",
            remember = true,
        )
        val encoded = json.encodeToString(CompanionCommand.serializer(), original)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("approve_permission", obj["type"]?.jsonPrimitive?.content)
        assertEquals("a-1", obj["id"]?.jsonPrimitive?.content)
        assertEquals("req-1", obj["request_id"]?.jsonPrimitive?.content)
        assertEquals("true", obj["remember"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromString(CompanionCommand.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `ApprovePermission defaults remember to false`() {
        val original = CompanionCommand.ApprovePermission(
            id = "a-2",
            requestId = "req-2",
        )
        val encoded = json.encodeToString(CompanionCommand.serializer(), original)
        val decoded = json.decodeFromString(CompanionCommand.serializer(), encoded)
            as CompanionCommand.ApprovePermission
        assertFalse(decoded.remember)
    }

    @Test
    fun `DenyPermission with reason round-trips`() {
        val original = CompanionCommand.DenyPermission(
            id = "d-1",
            requestId = "req-3",
            reason = "unsafe path",
        )
        val encoded = json.encodeToString(CompanionCommand.serializer(), original)
        val decoded = json.decodeFromString(CompanionCommand.serializer(), encoded)
            as CompanionCommand.DenyPermission
        assertEquals(original, decoded)
        assertEquals("unsafe path", decoded.reason)
    }

    @Test
    fun `DenyPermission with null reason round-trips`() {
        val original = CompanionCommand.DenyPermission(
            id = "d-2",
            requestId = "req-4",
        )
        val encoded = json.encodeToString(CompanionCommand.serializer(), original)
        val decoded = json.decodeFromString(CompanionCommand.serializer(), encoded)
            as CompanionCommand.DenyPermission
        assertNull(decoded.reason)
    }

    @Test
    fun `InjectPrompt round-trips with multi-line text`() {
        val original = CompanionCommand.InjectPrompt(
            id = "i-1",
            session = "main",
            text = "line1\nline2\n",
        )
        val encoded = json.encodeToString(CompanionCommand.serializer(), original)
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("inject_prompt", obj["type"]?.jsonPrimitive?.content)
        assertEquals("main", obj["session"]?.jsonPrimitive?.content)
        assertEquals("line1\nline2\n", obj["text"]?.jsonPrimitive?.content)

        val decoded = json.decodeFromString(CompanionCommand.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes via sealed-class serializer using type discriminator`() {
        val raw = """
            {"type":"approve_permission","id":"x","request_id":"r","remember":false}
        """.trimIndent()
        val decoded = json.decodeFromString(CompanionCommand.serializer(), raw)
        assertTrue(decoded is CompanionCommand.ApprovePermission)
        assertNotNull(decoded)
        assertEquals("x", decoded.id)
    }
}
