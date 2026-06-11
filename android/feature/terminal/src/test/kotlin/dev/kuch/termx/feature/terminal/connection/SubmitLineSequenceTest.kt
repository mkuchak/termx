package dev.kuch.termx.feature.terminal.connection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the EXACT byte shape of the two-phase PTT submit
 * ([buildSubmitSequence] / [sanitizePtySubmitText], Task #53). These
 * bytes are load-bearing — the recipe mirrors Claude Code's own
 * remote-session injector, and every detail below guards a verified
 * failure mode:
 *
 *  - text and CR are SEPARATE steps (one >=64-char buffer carrying
 *    `text + "\r"` is exactly what Claude Code's stdin tokenizer
 *    refuses to treat as a submit — the original bug);
 *  - the CR step is the LONE byte 0x0D, never 0x0A (LF is Ctrl+J =
 *    insert-newline in Claude Code, by design);
 *  - the CR delay is transport-sized: 75ms ssh vs 300ms mosh (mosh
 *    coalesces input into ~250ms state frames; less re-merges the CR
 *    into the text server-side);
 *  - ESC[200~/201~ wrapping happens ONLY when the remote enabled
 *    DECSET 2004 — a bare bash prompt must get clean text;
 *  - the sanitizer keeps the v1.3.3 exotic-line-break collapse
 *    (PttPayloadTest's contract) and paste()'s ESC/C1 strip.
 *
 * Pure functions, plain JUnit — no Robolectric, mirroring
 * [dev.kuch.termx.feature.terminal.PttPayloadTest]. The queue-level
 * behavior (atomicity, real delays, live DECSET detection) is pinned
 * by the Robolectric half in [ConnectionManagerWriteQueueTest].
 */
class SubmitLineSequenceTest {

    private val CR = byteArrayOf(0x0D)

    // Same explicit-codepoint construction as PttPayloadTest so source
    // encoding churn can't silently swap the control chars for spaces.
    private val NEL = Char(0x0085).toString()
    private val VT = Char(0x000B).toString()
    private val FF = Char(0x000C).toString()
    private val LSEP = Char(0x2028).toString()
    private val PSEP = Char(0x2029).toString()

    // ── step shape ──

    @Test fun `ssh, plain prompt - text step then lone CR step at 75ms`() {
        val steps = buildSubmitSequence("git status", bracketedPaste = false, moshBacked = false)
        assertEquals(2, steps.size)
        assertArrayEquals("git status".toByteArray(), steps[0].bytes)
        assertEquals("text goes immediately", 0L, steps[0].delayBeforeMs)
        assertArrayEquals("submit must be the LONE CR byte", CR, steps[1].bytes)
        assertEquals(SSH_SUBMIT_CR_DELAY_MS, steps[1].delayBeforeMs)
        assertEquals(75L, steps[1].delayBeforeMs)
    }

    @Test fun `mosh - CR waits a full state-frame interval (300ms)`() {
        val steps = buildSubmitSequence("git status", bracketedPaste = false, moshBacked = true)
        assertEquals(MOSH_SUBMIT_CR_DELAY_MS, steps[1].delayBeforeMs)
        assertEquals(300L, steps[1].delayBeforeMs)
    }

    @Test fun `a 64-plus char transcript stays ONE text step - the failing shape that motivated the split`() {
        val transcript = "type check the project then run the unit tests and commit the result"
        assertTrue("fixture must be >= 64 chars to model the bug", transcript.length >= 64)
        val steps = buildSubmitSequence(transcript, bracketedPaste = false, moshBacked = false)
        assertEquals(2, steps.size)
        assertArrayEquals(transcript.toByteArray(), steps[0].bytes)
        assertEquals("CR must be its own 1-byte write", 1, steps[1].bytes.size)
    }

    // ── bracketed-paste gating ──

    @Test fun `bracketed paste on - text wrapped in ESC 200 tilde markers, CR stays outside`() {
        val steps = buildSubmitSequence("summarize the diff", bracketedPaste = true, moshBacked = false)
        assertArrayEquals(
            "\u001B[200~summarize the diff\u001B[201~".toByteArray(),
            steps[0].bytes,
        )
        assertArrayEquals("the CR must NOT be inside the wrap", CR, steps[1].bytes)
    }

    @Test fun `bracketed paste off - clean text, no marker bytes anywhere`() {
        val steps = buildSubmitSequence("summarize the diff", bracketedPaste = false, moshBacked = false)
        steps.forEach { step ->
            assertFalse(
                "a non-2004 shell must never see ESC",
                step.bytes.contains(0x1B.toByte()),
            )
        }
    }

    @Test fun `a draft cannot smuggle a fake close marker out of the wrap`() {
        // paste()'s ESC strip is what makes the wrap safe: without it,
        // an ESC[201~ inside the text would terminate the paste early
        // and the remainder would land as live keystrokes.
        val steps = buildSubmitSequence(
            "innocent\u001B[201~rm -rf /",
            bracketedPaste = true,
            moshBacked = false,
        )
        val payload = String(steps[0].bytes, Charsets.UTF_8)
        assertEquals(
            "only the real close marker may carry an ESC",
            "\u001B[200~innocent[201~rm -rf /\u001B[201~",
            payload,
        )
    }

    // ── sanitization (shared ANY_LINE_BREAK contract + paste() strip) ──

    @Test fun `leading and trailing whitespace is trimmed - Gemini transcripts end in a newline`() {
        val steps = buildSubmitSequence("  npx tsc --noEmit \n", bracketedPaste = false, moshBacked = false)
        assertArrayEquals("npx tsc --noEmit".toByteArray(), steps[0].bytes)
    }

    @Test fun `interior exotic line-break runs collapse to a single CR (v1n3n3 contract)`() {
        assertEquals(
            "ls\rcat",
            sanitizePtySubmitText("ls\r\n${LSEP}${PSEP}${NEL}${VT}${FF}cat"),
        )
    }

    @Test fun `NEL collapses to CR instead of being C1-stripped - deliberate divergence from paste()`() {
        // paste() strips C1 FIRST, deleting NEL outright; our sanitizer
        // collapses line breaks first so a NEL "line break" keeps its
        // meaning. See sanitizePtySubmitText's KDoc.
        assertEquals("ls\rcat", sanitizePtySubmitText("ls${NEL}cat"))
    }

    @Test fun `ESC and C1 controls are stripped like paste() does`() {
        assertEquals("a[31mred", sanitizePtySubmitText("a\u001B[31mred"))
        assertEquals("ab", sanitizePtySubmitText("a\u009Cb"))
    }

    @Test fun `never LF - no 0x0A byte in any step for a multi-line draft`() {
        val steps = buildSubmitSequence("ls\necho done\n", bracketedPaste = true, moshBacked = true)
        steps.forEach { step ->
            assertFalse(
                "LF is Ctrl+J = insert-newline in Claude Code; it must never be emitted",
                step.bytes.contains(0x0A.toByte()),
            )
        }
    }

    // ── degenerate input ──

    @Test fun `blank draft collapses to a bare immediate Enter`() {
        listOf("", "   ", "\n\n").forEach { blank ->
            val steps = buildSubmitSequence(blank, bracketedPaste = true, moshBacked = true)
            assertEquals("blank input is just an Enter press", 1, steps.size)
            assertArrayEquals(CR, steps[0].bytes)
            assertEquals("no text chunk to separate from", 0L, steps[0].delayBeforeMs)
        }
    }
}
