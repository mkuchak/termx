package dev.kuch.termx.libs.companion.events

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateSerializationTest {

    private val json: Json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses a canonical termxd session file`() {
        val raw = """
            {
              "name": "main",
              "created_at": "2026-04-23T12:00:00Z",
              "windows": 3,
              "status": "working",
              "claude": true
            }
        """.trimIndent()

        val decoded = json.decodeFromString<SessionState>(raw)

        assertEquals("main", decoded.name)
        assertEquals(Instant.parse("2026-04-23T12:00:00Z"), decoded.createdAt)
        assertEquals(3, decoded.windows)
        assertEquals("working", decoded.status)
        assertTrue(decoded.claude)
    }

    @Test
    fun `parses idle status with no Claude`() {
        val raw = """
            {"name":"scratch","created_at":"2026-04-23T11:30:00Z","windows":1,"status":"idle","claude":false}
        """.trimIndent()

        val decoded = json.decodeFromString<SessionState>(raw)

        assertEquals("scratch", decoded.name)
        assertFalse(decoded.claude)
        assertEquals("idle", decoded.status)
    }

    @Test
    fun `parses awaiting_permission status`() {
        val raw = """
            {"name":"dev","created_at":"2026-04-23T12:00:00Z","windows":2,"status":"awaiting_permission","claude":true}
        """.trimIndent()

        val decoded = json.decodeFromString<SessionState>(raw)

        assertEquals("awaiting_permission", decoded.status)
    }

    @Test
    fun `tolerates unknown fields so newer termxd emits don't break older phones`() {
        val raw = """
            {"name":"n","created_at":"2026-04-23T12:00:00Z","windows":0,"status":"idle","claude":false,
             "new_field_from_the_future":"whatever","pid":12345}
        """.trimIndent()

        val decoded = json.decodeFromString<SessionState>(raw)

        assertEquals("n", decoded.name)
    }

    @Test
    fun `round-trips through encode then decode`() {
        val original = SessionState(
            name = "main",
            createdAt = Instant.parse("2026-04-23T12:00:00Z"),
            windows = 4,
            status = "working",
            claude = true,
        )
        val encoded = Json.encodeToString(SessionState.serializer(), original)
        val decoded = json.decodeFromString<SessionState>(encoded)
        assertEquals(original, decoded)
    }
}
