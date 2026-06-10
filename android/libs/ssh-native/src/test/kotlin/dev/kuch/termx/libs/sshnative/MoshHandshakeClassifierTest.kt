package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.MoshClientImpl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure handshake-failure classifier: given the stderr
 * transcript of a `mosh-server new` exec that never produced a
 * `MOSH CONNECT` line, map it to the structured [MoshFailureReason] the
 * UI can act on. No I/O — same convention as [MoshServerCommandTest].
 */
class MoshHandshakeClassifierTest {

    @Test
    fun `mosh-server UTF-8 refusal classifies as MissingUtf8Locale`() {
        // Verbatim shape of mosh-server 1.4.0's hard refusal on a C/POSIX
        // locale — the single most common mosh failure on minimal VPSes.
        val stderr = """
            mosh-server needs a UTF-8 native locale to run.

            Unfortunately, the local environment (LC_ALL=C) specifies
            the character set "US-ASCII",

            The client-supplied environment (LC_ALL=C) specifies
            the character set "US-ASCII".
        """.trimIndent()
        assertEquals(
            MoshFailureReason.MissingUtf8Locale,
            MoshClientImpl.classifyHandshakeFailure(stderr),
        )
    }

    @Test
    fun `bash command not found classifies as MoshServerMissing`() {
        assertEquals(
            MoshFailureReason.MoshServerMissing,
            MoshClientImpl.classifyHandshakeFailure(
                "bash: line 1: mosh-server: command not found\n",
            ),
        )
    }

    @Test
    fun `posix sh not found variant classifies as MoshServerMissing`() {
        // dash/ash phrase it without the word "command".
        assertEquals(
            MoshFailureReason.MoshServerMissing,
            MoshClientImpl.classifyHandshakeFailure("sh: 1: mosh-server: not found\n"),
        )
    }

    @Test
    fun `UTF-8 wins over not-found when both appear`() {
        // Locale errors often also say "not found" (charmap lookup); the
        // UTF-8 probe must run first or we'd tell the user to install a
        // mosh-server they already have.
        assertEquals(
            MoshFailureReason.MissingUtf8Locale,
            MoshClientImpl.classifyHandshakeFailure(
                "locale: charmap 'C.UTF-8' not found\n",
            ),
        )
    }

    @Test
    fun `other stderr is surfaced trimmed as Other`() {
        val reason = MoshClientImpl.classifyHandshakeFailure(
            "  mosh-server: bind: Address already in use\n",
        )
        assertEquals(
            MoshFailureReason.Other("mosh-server: bind: Address already in use"),
            reason,
        )
    }

    @Test
    fun `other stderr detail is capped at 200 chars`() {
        val noisy = "x".repeat(1_000)
        val reason = MoshClientImpl.classifyHandshakeFailure(noisy)
        assertEquals(
            MoshFailureReason.Other("x".repeat(MoshClientImpl.OTHER_DETAIL_MAX_CHARS)),
            reason,
        )
    }

    @Test
    fun `empty stderr classifies as HandshakeTimeout`() {
        assertEquals(
            MoshFailureReason.HandshakeTimeout,
            MoshClientImpl.classifyHandshakeFailure(""),
        )
    }

    @Test
    fun `whitespace-only stderr classifies as HandshakeTimeout`() {
        assertEquals(
            MoshFailureReason.HandshakeTimeout,
            MoshClientImpl.classifyHandshakeFailure("  \n\r\n  "),
        )
    }
}
