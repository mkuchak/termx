package dev.kuch.termx.feature.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Guards on [encodePttPayload] — the function the PTT Send/Insert
 * buttons run on the user's transcript before it hits the PTY. The
 * specific bytes here are load-bearing:
 *
 *  - We send `\r` (CR / 0x0D) for Enter, never `\n` (LF / 0x0A). LF
 *    renders as a literal newline glyph in raw-mode shells over
 *    tmux/mosh and doesn't submit the line. v1.1.10–v1.1.11 shipped
 *    LF and the user reported "Send doesn't fire Enter."
 *  - Embedded `\n`s (from pressing Enter inside the editable transcript
 *    field) get the same treatment so multi-line dictation runs each
 *    line as its own command.
 *  - Insert (appendNewline=false) emits no trailing CR — the user
 *    keeps editing in the shell prompt.
 */
class PttPayloadTest {

    @Test fun `Send appends CR (0x0D), not LF`() {
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls", appendNewline = true),
        )
    }

    @Test fun `Insert leaves the text untouched (no trailing CR)`() {
        assertArrayEquals(
            "ls".toByteArray(),
            encodePttPayload("ls", appendNewline = false),
        )
    }

    @Test fun `embedded LF in the draft becomes CR on Send`() {
        assertArrayEquals(
            "ls\recho foo\r".toByteArray(),
            encodePttPayload("ls\necho foo", appendNewline = true),
        )
    }

    @Test fun `embedded LF in the draft becomes CR on Insert`() {
        assertArrayEquals(
            "ls\recho foo".toByteArray(),
            encodePttPayload("ls\necho foo", appendNewline = false),
        )
    }

    @Test fun `empty draft + Send sends a bare CR`() {
        assertArrayEquals(
            byteArrayOf(0x0D),
            encodePttPayload("", appendNewline = true),
        )
    }

    @Test fun `empty draft + Insert sends nothing`() {
        assertArrayEquals(
            byteArrayOf(),
            encodePttPayload("", appendNewline = false),
        )
    }

    @Test fun `existing CR in the draft survives unchanged`() {
        // Some IMEs pre-insert \r when Enter is pressed; we shouldn't
        // double-translate them to \r\r or anything else. replace()
        // only rewrites \n.
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls\r", appendNewline = false),
        )
    }

    @Test fun `CRLF line ending in the draft is preserved as CR-CR`() {
        // Windows-style line break that some clipboard pastes deliver:
        // "\r\n" → the \r is kept, the \n becomes \r → "\r\r".
        // Bash readline accepts the first \r (executes), then second \r
        // on an empty buffer (just shows new prompt). Surprising on
        // paper, but harmless and matches what xterm does for an
        // accidental CRLF paste.
        assertArrayEquals(
            "ls\r\r".toByteArray(),
            encodePttPayload("ls\r\n", appendNewline = false),
        )
    }

    @Test fun `multibyte UTF-8 characters survive the encoding`() {
        val utf8 = "echo \"Olá, mundo\"".toByteArray()
        assertArrayEquals(
            utf8 + 0x0D,
            encodePttPayload("echo \"Olá, mundo\"", appendNewline = true),
        )
    }

    @Test fun `tab characters in the draft are passed through`() {
        // Tab-completion pasted into the field shouldn't be mangled.
        assertArrayEquals(
            "ls\t/tmp\r".toByteArray(),
            encodePttPayload("ls\t/tmp", appendNewline = true),
        )
    }
}
