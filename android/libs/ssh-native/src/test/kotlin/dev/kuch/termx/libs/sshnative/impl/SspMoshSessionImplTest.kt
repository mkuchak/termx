package dev.kuch.termx.libs.sshnative.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the v1.1.21 pure-Kotlin mosh adapter.
 *
 * We don't construct a full [SspMoshSessionImpl] here because its
 * `init` block immediately spawns a UDP socket via the underlying
 * [sh.haven.mosh.transport.MoshTransport.start] — running that in a
 * pure-JVM unit test would either hang on real network I/O or take
 * 15s to time out (the bumped `SESSION_DEAD_MS`). Instead we verify
 * the small pieces that are testable in isolation:
 *
 *  - [mapDisconnect] — the pure mapping from the library's
 *    `(cleanExit: Boolean)` callback to our [MoshDiagnostic] shape;
 *    every consumer downstream (`MoshExitMessage`, the snackbar in
 *    `TerminalViewModel.openMoshTab`, the `MoshPreflight` error row)
 *    branches off these values, so the four lines here are
 *    load-bearing.
 *  - [CapturingMoshLogger] — caches the most-recent error message so
 *    the diagnostic head can carry the real reason when
 *    `cleanExit=false` (which on its own carries nothing).
 *
 * Real end-to-end mosh exercise lives in the manual smoke step (A8)
 * and in the on-cellular validation gate (D1).
 */
class SspMoshSessionImplTest {

    // ---- mapDisconnect ----------------------------------------------------

    @Test fun `cleanExit true maps to exitCode 0 and the timeout copy`() {
        val diag = mapDisconnect(cleanExit = true, elapsedMs = 15_000L, lastError = "")
        assertEquals(0, diag.exitCode)
        assertEquals(15_000L, diag.elapsedMs)
        assertTrue(
            "expected the UDP-blocked / shell-ended copy, got: ${diag.head}",
            diag.head.contains("no UDP traffic from server"),
        )
        assertTrue(
            "expected the VPS-firewall hint, got: ${diag.head}",
            diag.head.contains("60000-61000"),
        )
    }

    @Test fun `cleanExit false with a captured error surfaces that error in head`() {
        val diag = mapDisconnect(
            cleanExit = false,
            elapsedMs = 80L,
            lastError = "Failed to bind UDP socket: Address already in use",
        )
        assertEquals(1, diag.exitCode)
        assertEquals("Failed to bind UDP socket: Address already in use", diag.head)
    }

    @Test fun `cleanExit false with no captured error falls back to a generic transport-error label`() {
        // Path that fires when the receive loop crashes before the
        // logger sees an explicit e() call, e.g. an InvalidCipherText
        // burst the loop just keeps draining. Better to surface
        // *something* than to leave the snackbar with an empty head.
        val diag = mapDisconnect(cleanExit = false, elapsedMs = 4L, lastError = "")
        assertEquals(1, diag.exitCode)
        assertNotEquals("", diag.head)
        assertTrue(
            "expected the fallback label, got: ${diag.head}",
            diag.head.contains("Mosh transport error", ignoreCase = true),
        )
    }

    @Test fun `mapDisconnect preserves elapsedMs unchanged`() {
        val diag = mapDisconnect(cleanExit = true, elapsedMs = 1_234_567L, lastError = "")
        assertEquals(1_234_567L, diag.elapsedMs)
    }

    // ---- CapturingMoshLogger ---------------------------------------------

    @Test fun `CapturingMoshLogger lastError starts empty`() {
        val logger = CapturingMoshLogger()
        assertEquals("", logger.lastError)
    }

    @Test fun `e() with a non-blank throwable message joins msg and cause`() {
        val logger = CapturingMoshLogger()
        logger.e("MoshTransport", "send failed", RuntimeException("Broken pipe"))
        assertEquals("send failed: Broken pipe", logger.lastError)
    }

    @Test fun `e() with a null throwable keeps just the message`() {
        val logger = CapturingMoshLogger()
        logger.e("MoshTransport", "decryption failed", null)
        assertEquals("decryption failed", logger.lastError)
    }

    @Test fun `e() with a blank-message throwable keeps just the message`() {
        // Some thrown exceptions carry only a class name, not a
        // user-readable text. The `:` joiner would then produce
        // "msg: " with a trailing dangle — guard against that.
        val logger = CapturingMoshLogger()
        logger.e("MoshTransport", "receive failed", RuntimeException("   "))
        assertEquals("receive failed", logger.lastError)
    }

    @Test fun `consecutive e() calls overwrite lastError`() {
        val logger = CapturingMoshLogger()
        logger.e("Tag", "first", null)
        logger.e("Tag", "second", null)
        assertEquals("second", logger.lastError)
    }

    @Test fun `d() does not affect lastError`() {
        val logger = CapturingMoshLogger()
        logger.e("Tag", "real error", null)
        logger.d("Tag", "noisy debug message")
        assertEquals(
            "debug log lines must not clobber the error capture",
            "real error",
            logger.lastError,
        )
    }
}
