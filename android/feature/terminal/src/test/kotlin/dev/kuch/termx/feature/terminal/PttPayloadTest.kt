package dev.kuch.termx.feature.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * Guards on [encodePttPayload] — the function the PTT Send/Insert
 * buttons run on the user's transcript before it hits the PTY. The
 * specific bytes here are load-bearing:
 *
 *  - We send `\r` (CR / 0x0D) for Enter, never `\n` (LF / 0x0A). LF
 *    renders as a literal newline glyph in raw-mode shells (including
 *    over mosh) and doesn't submit the line. v1.1.10–v1.1.11 shipped
 *    LF and the user reported "Send doesn't fire Enter."
 *  - Embedded `\n`s (from pressing Enter inside the editable transcript
 *    field) get the same treatment so multi-line dictation runs each
 *    line as its own command.
 *  - Insert (appendNewline=false) emits no trailing CR — the user
 *    keeps editing in the shell prompt.
 *  - v1.3.3: the encoder also collapses U+0085 / U+000B / U+000C /
 *    U+2028 / U+2029 to `\r`, because Gemini transcripts and clipboard
 *    pastes occasionally include them and they otherwise render as
 *    visual line breaks without triggering accept-line.
 */
class PttPayloadTest {

    // Each new exotic-line-break case constructs its input with
    // explicit Char(codepoint) so the source file's encoding can't
    // silently substitute control characters for spaces (which it
    // does for U+2028 / U+2029 / U+0085 / U+000B / U+000C when the
    // file passes through editors or transports that normalize them).
    private val NEL = Char(0x0085).toString()    // Next Line
    private val VT = Char(0x000B).toString()     // Vertical Tab
    private val FF = Char(0x000C).toString()     // Form Feed
    private val LSEP = Char(0x2028).toString()   // Line Separator
    private val PSEP = Char(0x2029).toString()   // Paragraph Separator

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
        // double-translate them to \r\r or anything else. The regex
        // matches \r as a line break, but a single \r in a run still
        // collapses to a single \r — net no-op.
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls\r", appendNewline = false),
        )
    }

    @Test fun `CRLF line ending in the draft collapses to a single CR`() {
        // v1.3.3 widened ANY_LINE_BREAK to a regex that matches a RUN
        // of mixed line-break codepoints, replacing the whole run with
        // one `\r`. So "\r\n" no longer becomes "\r\r" (which it did
        // pre-v1.3.3) but collapses to a single "\r". Same end-effect
        // at the shell — readline accepts the line — without leaving a
        // stray empty Enter.
        assertArrayEquals(
            "ls\r".toByteArray(),
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

    @Test fun `LSEP (U+2028) collapses to CR — Word paste, LLM sentence boundary`() {
        // Microsoft Word emits U+2028 for soft line breaks. Pasting from
        // Word — or from an LLM transcription that picked it up via
        // training data — pre-v1.3.3 leaked the 3-byte UTF-8 sequence
        // E2 80 A8 into the wire; xterm renders that as a cursor-down,
        // and readline never sees an accept-line. Now collapses to CR.
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls$LSEP", appendNewline = false),
        )
    }

    @Test fun `PSEP (U+2029) collapses to CR — paragraph boundary`() {
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls$PSEP", appendNewline = false),
        )
    }

    @Test fun `NEL (U+0085) collapses to CR — IBM-legacy next-line`() {
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls$NEL", appendNewline = false),
        )
    }

    @Test fun `VT (U+000B) collapses to CR — vertical tab cursor-down`() {
        // xterm interprets 0x0B as "cursor down 1 line, same column."
        // Without normalization, "ls" + VT would render as "ls" then
        // cursor down — exactly the symptom users saw on Send before
        // v1.3.3.
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls$VT", appendNewline = false),
        )
    }

    @Test fun `FF (U+000C) collapses to CR — form feed cursor-down`() {
        assertArrayEquals(
            "ls\r".toByteArray(),
            encodePttPayload("ls$FF", appendNewline = false),
        )
    }

    @Test fun `mixed run of line-break codepoints collapses to a single CR`() {
        // Pathological case: a transcript that picked up multiple
        // line-break flavors back-to-back. Old encoder would have left
        // each one through (or converted only the LF). New encoder
        // collapses the whole run to a single `\r`.
        assertArrayEquals(
            "ls\rcat\r".toByteArray(),
            encodePttPayload("ls\r\n${LSEP}${NEL}${VT}${FF}cat", appendNewline = true),
        )
    }
}
