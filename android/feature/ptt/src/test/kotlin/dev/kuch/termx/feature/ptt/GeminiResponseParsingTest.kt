package dev.kuch.termx.feature.ptt

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Tests for [GeminiClient.extractTranscript] — the JSON parser that
 * pulls the transcript out of Gemini's `generateContent` response.
 *
 * Failure modes here are silent in production: we get a
 * `GeminiException` with a snippet, the user sees a snackbar, but
 * upstream there's no way to tell whether Gemini changed its
 * response shape or returned something we should have handled.
 * These cases lock the parser to the contract we ship against.
 */
class GeminiResponseParsingTest {

    private lateinit var client: GeminiClient

    @Before fun setUp() {
        client = GeminiClient(OkHttpClient.Builder().build())
    }

    @Test fun `extracts text from a well-formed single-part response`() {
        val body = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      { "text": "list pods" }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals("list pods", client.extractTranscript(body))
    }

    @Test fun `concatenates multiple parts into one string`() {
        // Gemini occasionally splits a long answer across parts; we
        // join them so the user sees one continuous transcript.
        val body = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "text": "list " },
                    { "text": "pods " },
                    { "text": "in default" }
                  ]
                }
              }]
            }
        """.trimIndent()
        assertEquals("list pods in default", client.extractTranscript(body))
    }

    @Test fun `returns NO_SPEECH sentinel verbatim (caller handles it)`() {
        // The view-model checks for this sentinel and converts to a
        // friendly Error("No speech detected…"). The parser itself
        // must not swallow it.
        val body = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "text": "NO_SPEECH" }
                  ]
                }
              }]
            }
        """.trimIndent()
        assertEquals("NO_SPEECH", client.extractTranscript(body))
    }

    @Test fun `trims surrounding whitespace from concatenated parts`() {
        val body = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "text": "  hello world  " }
                  ]
                }
              }]
            }
        """.trimIndent()
        assertEquals("hello world", client.extractTranscript(body))
    }

    @Test fun `throws GeminiException on malformed JSON`() {
        val body = "this is not JSON {{{{"
        try {
            client.extractTranscript(body)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(
                "message should mention 'not JSON': ${e.message}",
                e.message.orEmpty().contains("not JSON"),
            )
        }
    }

    @Test fun `throws when candidates field is missing`() {
        val body = """{"feedback": "rate-limited"}"""
        try {
            client.extractTranscript(body)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(
                "message should mention 'candidates': ${e.message}",
                e.message.orEmpty().contains("candidates"),
            )
        }
    }

    @Test fun `throws when candidates is empty`() {
        val body = """{"candidates": []}"""
        try {
            client.extractTranscript(body)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(
                "message should mention 'no candidates': ${e.message}",
                e.message.orEmpty().contains("no candidates"),
            )
        }
    }

    @Test fun `throws when content_parts is missing`() {
        val body = """
            {
              "candidates": [{
                "content": { "role": "model" }
              }]
            }
        """.trimIndent()
        try {
            client.extractTranscript(body)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(
                "message should mention 'content.parts': ${e.message}",
                e.message.orEmpty().contains("content.parts"),
            )
        }
    }

    @Test fun `throws when parts contain only blank text`() {
        val body = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "text": "   " },
                    { "text": "\t\n" }
                  ]
                }
              }]
            }
        """.trimIndent()
        try {
            client.extractTranscript(body)
            fail("expected GeminiException")
        } catch (e: GeminiException) {
            assertTrue(
                "message should mention 'empty transcript': ${e.message}",
                e.message.orEmpty().contains("empty transcript"),
            )
        }
    }

    @Test fun `ignores parts without a text field`() {
        // Gemini's API returns inline image / function-call parts in
        // some modes. We only care about text; non-text parts should
        // be silently filtered.
        val body = """
            {
              "candidates": [{
                "content": {
                  "parts": [
                    { "inline_data": { "mime_type": "image/png" } },
                    { "text": "list pods" },
                    { "function_call": { "name": "noop" } }
                  ]
                }
              }]
            }
        """.trimIndent()
        assertEquals("list pods", client.extractTranscript(body))
    }
}
