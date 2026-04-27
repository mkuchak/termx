package dev.kuch.termx.libs.sshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [MoshExitMessage]. Covers each branch of the
 * decision tree that drives the live-connect snackbar AND the
 * mosh-aware preflight row in `:feature:servers`.
 *
 *  - early non-zero exit (preserved from v1.1.16) — must format
 *    identically to before so the user's mental model survives.
 *  - late non-zero exit (added v1.1.18) — runtime crash / OOM-kill /
 *    signal, used to fall through to bare "disconnected" with no info.
 *  - clean exit shorter than [MoshExitMessage.SUSPECT_CLEAN_EXIT_MS] —
 *    almost always means UDP between phone and VPS is blocked; we hint
 *    at that.
 *  - clean exit at or above the suspect window — normal user-typed
 *    `exit`; suppressed on purpose so a successful long session
 *    doesn't show a misleading snackbar on its way out.
 *  - signal-decoded exit codes (added v1.1.20) — `exit 139` becomes
 *    `exit 139, SIGSEGV` so an empty-head crash still tells the user
 *    *what kind* of crash without depending on logcat being
 *    accessible.
 */
class MoshExitMessageTest {

    // ---- forDiagnostic: non-zero exits ---------------------------------

    @Test fun `early non-zero exit produces the v1_1_16 message format with signal name`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 139,
            elapsedMs = 14L,
            head = "",
        )
        // v1.1.20: the bare exit number is now decorated with the
        // signal name so the user gets actionable info even when the
        // PTY head and the logcat capture both come back empty (which
        // is exactly the user's case on their device).
        assertEquals(
            "mosh-client exited after 14ms (exit 139, SIGSEGV): no output captured",
            msg,
        )
    }

    @Test fun `late non-zero exit (formerly silent) now surfaces with seconds`() {
        val msg = MoshExitMessage.forDiagnostic(
            exitCode = 137,
            elapsedMs = 12_345L,
            head = "",
        )
        assertNotNull(msg)
        assertTrue("expected '12.3s' in $msg", msg!!.contains("12.3s"))
        assertTrue("expected 'exit 137' in $msg", msg.contains("exit 137"))
        assertTrue("expected SIGKILL decode in $msg", msg.contains("SIGKILL"))
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

    // ---- formatExitCode (signal decoding, v1.1.20) --------------------

    @Test fun `formatExitCode passes through plain status codes unchanged`() {
        // Anything <= 128 is a regular exit status — leave it alone.
        assertEquals("exit 0", MoshExitMessage.formatExitCode(0))
        assertEquals("exit 1", MoshExitMessage.formatExitCode(1))
        assertEquals("exit 127", MoshExitMessage.formatExitCode(127))
        assertEquals("exit 128", MoshExitMessage.formatExitCode(128))
    }

    @Test fun `formatExitCode decodes 128 plus N as the named signal`() {
        // The user's specific failure mode lands on 139 = 128 + SIGSEGV.
        assertEquals("exit 139, SIGSEGV", MoshExitMessage.formatExitCode(139))
        assertEquals("exit 137, SIGKILL", MoshExitMessage.formatExitCode(137))
        assertEquals("exit 143, SIGTERM", MoshExitMessage.formatExitCode(143))
        assertEquals("exit 134, SIGABRT", MoshExitMessage.formatExitCode(134))
        assertEquals("exit 135, SIGBUS", MoshExitMessage.formatExitCode(135))
    }

    @Test fun `formatExitCode falls back to bare code for unknown signals`() {
        // Real-time signals (>= 32) and the few we don't enumerate
        // shouldn't break — just don't decorate them.
        assertEquals("exit 200", MoshExitMessage.formatExitCode(200))
        assertEquals("exit 158", MoshExitMessage.formatExitCode(158))
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
        val pick = MoshExitMessage.extractReadableReason(head)
        assertTrue(
            "expected an actionable line, got: $pick",
            pick.contains("error", ignoreCase = true) ||
                pick.contains("symbol", ignoreCase = true) ||
                pick.startsWith("mosh"),
        )
    }

    @Test fun `extractReadableReason picks Fatal signal tombstone marker`() {
        // The kernel's debuggerd writes lines like
        // "Fatal signal 11 (SIGSEGV), code 1, fault addr …" — that's
        // the most actionable line in a tombstone, so we must prefer
        // it over surrounding boilerplate.
        val head = """
            04-27 12:34:56.789  1234  1234 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0
            04-27 12:34:56.790  1234  1234 F DEBUG   : *** *** *** *** ***
        """.trimIndent()
        val pick = MoshExitMessage.extractReadableReason(head)
        assertTrue(
            "expected the Fatal signal line, got: $pick",
            pick.contains("Fatal signal", ignoreCase = true),
        )
    }

    @Test fun `extractReadableReason picks linker CANNOT LINK output`() {
        val head = """
            04-27 12:34:56.789  1234  1234 E linker  : CANNOT LINK EXECUTABLE "libmoshclient.so": library "libfoo.so" not found
            unrelated noise
        """.trimIndent()
        val pick = MoshExitMessage.extractReadableReason(head)
        assertTrue(
            "expected the linker line, got: $pick",
            pick.contains("CANNOT LINK", ignoreCase = true),
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
