package dev.kuch.termx.feature.ptt

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-logic guards on [GeminiClient]. We don't exercise the actual
 * network call here — that would require a mock server and is what
 * integration tests are for. We do exercise:
 *
 *  - Prompt builders: language interpolation + NO_SPEECH guard +
 *    optional context appendix. Mistakes here would change what
 *    Gemini transcribes / translates.
 *  - Retry classification: which HTTP codes / IOException-bodies
 *    count as transient. Mistakes here would either make us hammer
 *    Gemini on a permanent 401, or give up on a transient 503.
 *  - Backoff timing math: stays within ±20% of the published 500ms /
 *    1200ms per-attempt budget.
 */
class GeminiClientTest {

    private lateinit var client: GeminiClient

    @Before fun setUp() {
        client = GeminiClient(OkHttpClient.Builder().build())
    }

    // ---- transcribe prompt ----------------------------------------------

    @Test fun `transcribe prompt names both the language and the locale code`() {
        val prompt = client.buildTranscribePrompt("pt-BR")
        assertTrue(prompt.contains("Brazilian Portuguese"))
        assertTrue(prompt.contains("(pt-BR)"))
    }

    @Test fun `transcribe prompt commits to transcription, not translation`() {
        val prompt = client.buildTranscribePrompt("en-US")
        assertTrue(prompt.contains("transcription assistant"))
        assertFalse(prompt.contains("translate"))
    }

    @Test fun `transcribe prompt appends the NO_SPEECH guard`() {
        val prompt = client.buildTranscribePrompt("en-US")
        assertTrue(
            "NO_SPEECH guard missing from transcribe prompt",
            prompt.contains("NO_SPEECH"),
        )
    }

    // ---- translate prompt -----------------------------------------------

    @Test fun `translate prompt names both languages and both codes`() {
        val prompt = client.buildTranslatePrompt(from = "en-US", to = "pt-BR")
        assertTrue(prompt.contains("American English"))
        assertTrue(prompt.contains("(en-US)"))
        assertTrue(prompt.contains("Brazilian Portuguese"))
        assertTrue(prompt.contains("(pt-BR)"))
    }

    @Test fun `translate prompt instructs the model to treat the audio as the source language`() {
        // Without this guard, Gemini sometimes refuses to translate
        // bilingual passages — it picks whichever language sounds
        // dominant. The "treat the entire input as <source>" line is
        // load-bearing.
        val prompt = client.buildTranslatePrompt(from = "en-US", to = "pt-BR")
        assertTrue(prompt.contains("treat the entire input as English"))
    }

    @Test fun `translate prompt appends the NO_SPEECH guard`() {
        val prompt = client.buildTranslatePrompt(from = "en-US", to = "pt-BR")
        assertTrue(prompt.contains("NO_SPEECH"))
    }

    @Test fun `transcribe and translate prompts diverge on text`() {
        val transcribe = client.buildTranscribePrompt("en-US")
        val translate = client.buildTranslatePrompt(from = "en-US", to = "pt-BR")
        assertNotEquals(transcribe, translate)
    }

    // ---- context appendix -----------------------------------------------

    @Test fun `buildPrompt skips the context appendix when context is blank`() {
        val withBlank = client.buildPrompt("en-US", "en-US", "")
        val withWhitespace = client.buildPrompt("en-US", "en-US", "   \n\t  ")
        val plain = client.buildTranscribePrompt("en-US")
        assertEquals(plain, withBlank)
        assertEquals(plain, withWhitespace)
    }

    @Test fun `buildPrompt appends the context block in triple-quote form when non-blank`() {
        val ctx = "kubectl, systemctl, k9s"
        val prompt = client.buildPrompt("en-US", "en-US", ctx)
        assertTrue(prompt.contains("domain-specific terms"))
        assertTrue(prompt.contains("\"\"\""))
        assertTrue(prompt.contains(ctx))
    }

    // ---- retry classification ------------------------------------------

    @Test fun `retryable HTTP codes are 429 and 5xx (500-504)`() {
        assertTrue(client.isRetryableHttp(429))
        assertTrue(client.isRetryableHttp(500))
        assertTrue(client.isRetryableHttp(502))
        assertTrue(client.isRetryableHttp(503))
        assertTrue(client.isRetryableHttp(504))
    }

    @Test fun `non-retryable HTTP codes do not match`() {
        assertFalse(client.isRetryableHttp(200))
        assertFalse(client.isRetryableHttp(400))
        assertFalse(client.isRetryableHttp(401))
        assertFalse(client.isRetryableHttp(403))
        assertFalse(client.isRetryableHttp(404))
        assertFalse(client.isRetryableHttp(505))
        assertFalse(client.isRetryableHttp(0))
    }

    @Test fun `retryable body matches the four documented substrings case-insensitively`() {
        assertTrue(client.isRetryableBody("server overloaded, try again"))
        assertTrue(client.isRetryableBody("currently unavailable"))
        assertTrue(client.isRetryableBody("Try Again later"))
        assertTrue(client.isRetryableBody("fetch failed"))
        assertTrue(client.isRetryableBody("OVERLOADED"))
        assertTrue(client.isRetryableBody("Service Unavailable"))
    }

    @Test fun `non-retryable body does not match`() {
        assertFalse(client.isRetryableBody(""))
        assertFalse(client.isRetryableBody("invalid api key"))
        assertFalse(client.isRetryableBody("permission denied"))
    }

    // ---- backoff timing -------------------------------------------------

    @Test fun `attempt 1 backoff stays within plus-minus 20 percent of 500ms`() {
        // Run a few iterations so we cover the random jitter range.
        for (i in 1..50) {
            val ms = client.jitteredBackoff(1)
            assertTrue("attempt-1 backoff $ms ms exceeded 600 (500+20%)", ms <= 600L)
            assertTrue("attempt-1 backoff $ms ms below 400 (500-20%)", ms >= 400L)
        }
    }

    @Test fun `attempt 2 (and beyond) backoff stays within plus-minus 20 percent of 1200ms`() {
        for (i in 1..50) {
            val ms = client.jitteredBackoff(2)
            assertTrue("attempt-2 backoff $ms ms exceeded 1440 (1200+20%)", ms <= 1440L)
            assertTrue("attempt-2 backoff $ms ms below 960 (1200-20%)", ms >= 960L)
        }
    }

    @Test fun `backoff never returns negative even with extreme jitter`() {
        // Defensive — coerceAtLeast(0L) in the impl protects against
        // a future jitter widening that overshoots zero.
        for (i in 1..200) {
            assertTrue(client.jitteredBackoff(1) >= 0L)
            assertTrue(client.jitteredBackoff(2) >= 0L)
        }
    }

    // ---- companion constants --------------------------------------------

    @Test fun `NO_SPEECH_GUARD constant mentions the literal sentinel value`() {
        assertTrue(GeminiClient.NO_SPEECH_GUARD.contains("NO_SPEECH"))
    }

    @Test fun `model + endpoint look like a Gemini route`() {
        assertNotNull(GeminiClient.MODEL)
        assertTrue(GeminiClient.ENDPOINT_BASE.startsWith("https://"))
        assertTrue(GeminiClient.ENDPOINT_BASE.endsWith("/"))
    }

    @Test fun `MAX_ATTEMPTS is greater than 1 (otherwise retry is dead code)`() {
        assertTrue(GeminiClient.MAX_ATTEMPTS > 1)
    }
}
