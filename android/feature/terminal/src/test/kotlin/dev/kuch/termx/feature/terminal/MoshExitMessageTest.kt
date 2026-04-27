package dev.kuch.termx.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MoshExitMessage]. Each branch here maps
 * directly to one of the three previously-silent failure modes the
 * v1.1.18 change is meant to surface to the user:
 *
 *  - early non-zero exit (preserved from v1.1.16) — must format
 *    identically to before so the user's mental model survives.
 *  - late non-zero exit (NEW) — runtime crash / OOM-kill / signal,
 *    used to fall through to bare "disconnected" with no info.
 *  - clean exit shorter than [SUSPECT_CLEAN_EXIT_MS] (NEW) — almost
 *    always means UDP between phone and VPS is blocked; we now hint
 *    at that.
 *  - clean exit at or above the suspect window — normal user-typed
 *    `exit`; suppressed on purpose so a successful long session
 *    doesn't show a misleading snackbar on its way out.
 */
class MoshExitMessageTest {

    // ---- forDiagnostic: non-zero exits ---------------------------------

    @Test fun `early non-zero exit produces the v1_1_16 message format`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 139,
            elapsedMs = 14L,
            head = "",
        )
        // Preserves the wording the user has seen since v1.1.16.
        assertEquals(
            "mosh-client exited after 14ms (exit 139): no output captured",
            msg,
        )
    }

    @Test fun `late non-zero exit (formerly silent) now surfaces with seconds`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 137,
            elapsedMs = 12_345L,
            head = "",
        )
        // The 2s gate is gone — late crashes (OOM-kill, late
        // segfault) must show up. Seconds-formatted to keep the
        // snackbar compact.
        assertNotNull(msg)
        assertTrue("expected '12.3s' in $msg", msg!!.contains("12.3s"))
        assertTrue("expected 'exit 137' in $msg", msg.contains("exit 137"))
    }

    @Test fun `non-zero exit picks the most useful line from head`() {
        val head = """
            mosh-client: Cannot find termcap entry for "xterm-256color".
            random preamble line
        """.trimIndent()
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 1,
            elapsedMs = 80L,
            head = head,
        )
        assertNotNull(msg)
        assertTrue(
            "expected the termcap line, got: $msg",
            msg!!.contains("termcap"),
        )
    }

    // ---- forDiagnostic: clean exits ------------------------------------

    @Test fun `clean fast exit hints at UDP-blocked path`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 0,
            elapsedMs = 4_200L,
            head = "",
        )
        assertNotNull(msg)
        assertTrue(
            "expected the UDP hint, got: $msg",
            msg!!.contains("UDP path may be blocked"),
        )
        assertTrue(
            "expected the firewall port range, got: $msg",
            msg.contains("60000-61000"),
        )
    }

    @Test fun `clean exit at the boundary still hints at UDP`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 0,
            elapsedMs = MoshExitMessage.SUSPECT_CLEAN_EXIT_MS - 1,
            head = "",
        )
        assertNotNull("just-under boundary should still surface", msg)
    }

    @Test fun `clean exit past the suspect window returns null (suppress)`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 0,
            elapsedMs = MoshExitMessage.SUSPECT_CLEAN_EXIT_MS,
            head = "",
        )
        // A long-running clean exit is a normal "user typed `exit`"
        // — surfacing a snackbar on the way out would be noise.
        assertNull(msg)
    }

    @Test fun `clean exit way past the suspect window returns null`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 0,
            elapsedMs = 5L * 60L * 1_000L,
            head = "",
        )
        assertNull(msg)
    }

    // ---- formatElapsed -------------------------------------------------

    @Test fun `formatElapsed renders sub-second values with ms suffix`() {
        assertEquals("0ms", MoshExitMessage.formatElapsed(0L))
        assertEquals("14ms", MoshExitMessage.formatElapsed(14L))
        assertEquals("999ms", MoshExitMessage.formatElapsed(999L))
    }

    @Test fun `formatElapsed switches to seconds at the 1-second mark`() {
        assertEquals("1.0s", MoshExitMessage.formatElapsed(1_000L))
        assertEquals("12.3s", MoshExitMessage.formatElapsed(12_345L))
        assertEquals("59.9s", MoshExitMessage.formatElapsed(59_900L))
    }

    @Test fun `formatElapsed switches to minutes plus zero-padded seconds`() {
        assertEquals("1m00s", MoshExitMessage.formatElapsed(60_000L))
        assertEquals("1m07s", MoshExitMessage.formatElapsed(67_000L))
        assertEquals("3m07s", MoshExitMessage.formatElapsed(187_000L))
        assertEquals("12m45s", MoshExitMessage.formatElapsed(12L * 60_000L + 45_000L))
    }

    // ---- extractReadableReason ----------------------------------------

    @Test fun `extractReadableReason on blank head returns the v1_1_16 fallback`() {
        assertEquals("no output captured", MoshExitMessage.extractReadableReason(""))
        assertEquals("no output captured", MoshExitMessage.extractReadableReason("   \n  \n"))
    }

    @Test fun `extractReadableReason picks line containing 'error' over neighbours`() {
        val head = """
            preamble
            something normal
            CANNOT LOCATE SYMBOL: __aarch64_some_runtime
            mosh-client: error: bind: address already in use
            trailing line
        """.trimIndent()
        // Both the SYMBOL and mosh-client lines match; either is
        // useful. We just guarantee we don't fall back to the
        // generic preamble.
        val pick = MoshExitMessage.extractReadableReason(head)
        assertTrue(
            "expected an actionable line, got: $pick",
            pick.contains("error", ignoreCase = true) ||
                pick.contains("symbol", ignoreCase = true) ||
                pick.startsWith("mosh"),
        )
    }

    @Test fun `extractReadableReason caps at 200 chars`() {
        val long = "mosh-client: " + "x".repeat(500)
        val pick = MoshExitMessage.extractReadableReason(long)
        assertEquals(200, pick.length)
    }

    @Test fun `extractReadableReason falls back to the first non-blank line`() {
        val head = """

              boring info line
              another boring line
        """.trimIndent()
        val pick = MoshExitMessage.extractReadableReason(head)
        assertEquals("boring info line", pick)
    }
}
