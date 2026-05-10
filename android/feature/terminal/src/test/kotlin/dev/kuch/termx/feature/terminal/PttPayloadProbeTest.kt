package dev.kuch.termx.feature.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial probe of [encodePttPayload] — runs every input I can
 * dream up that might plausibly come back from Gemini, the IME, the
 * clipboard, or a paste, and dumps the actual byte sequence for human
 * inspection.
 *
 * The single absolute invariant we assert: the output bytes for the
 * Send button (appendNewline=true) must NEVER contain a literal LF
 * (0x0A) — that's the byte sequence that, sent to a raw-mode shell
 * over tmux/mosh, produces the "text appears, line breaks, doesn't
 * execute" symptom v1.1.12 already shipped a fix for. If any of these
 * cases produces an LF, the encoder has a real hole.
 */
class PttPayloadProbeTest {

    private data class Probe(val label: String, val input: String)

    private val cases: List<Probe> = listOf(
        Probe("plain ASCII",                "ls"),
        Probe("trailing LF",                "ls\n"),
        Probe("trailing CR",                "ls\r"),
        Probe("trailing CRLF",              "ls\r\n"),
        Probe("trailing LSEP (U+2028)",     "ls "),
        Probe("trailing PSEP (U+2029)",     "ls "),
        Probe("trailing NEL (U+0085)",      "ls"),
        Probe("trailing VT (U+000B)",       "ls"),
        Probe("trailing FF (U+000C)",       "ls"),
        Probe("trailing ASCII space",       "ls "),
        Probe("trailing NBSP (U+00A0)",     "ls "),
        Probe("trailing tab",               "ls\t"),
        Probe("trailing zero-width space",  "ls​"),
        Probe("leading dot-space",          ". ls"),
        Probe("with argument",              "ls /tmp"),
        Probe("quoted string",              "echo \"hello world\""),
        Probe("multiple cmds + semicolon",  "ls; pwd"),
        Probe("backslash continuation",     "ls\\"),
        Probe("backticks",                  "echo `pwd`"),
        Probe("comment",                    "ls #"),
        Probe("empty",                      ""),
        Probe("multibyte UTF-8",            "Olá"),
        Probe("emoji",                      "echo 🚀"),
        Probe("BOM",                        "﻿ls"),
        Probe("LF + trailing space",        "ls\n "),
        Probe("CR + trailing space",        "ls\r "),
        Probe("two LFs",                    "ls\n\n"),
        Probe("LF then text",               "\nls"),
        Probe("text + LF + text",           "ls\necho ok"),
        Probe("Gemini-style trailing dot",  "ls."),
        Probe("Gemini-style with period",   "List directory."),
        Probe("Word-boundary special",      "ls⁠/tmp"),
    )

    @Test
    fun `Send-mode bytes never contain LF (0x0A)`() {
        val report = StringBuilder()
        var failures = 0
        for (probe in cases) {
            val bytes = encodePttPayload(probe.input, appendNewline = true)
            val hex = bytes.joinToString(" ") { "%02x".format(it) }
            val containsLf = bytes.any { it == 0x0A.toByte() }
            report.append(
                "[%s] '%s' → %s%s\n".format(
                    probe.label,
                    probe.input
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace(" ", "\\u2028")
                        .replace(" ", "\\u2029")
                        .replace("", "\\u0085")
                        .replace("", "\\u000B")
                        .replace("", "\\u000C")
                        .replace(" ", "\\u00A0")
                        .replace("​", "\\u200B")
                        .replace("﻿", "\\uFEFF")
                        .replace("⁠", "\\u2060"),
                    hex,
                    if (containsLf) "  ⚠ LF PRESENT" else "",
                ),
            )
            if (containsLf) failures++
        }
        println(report.toString())
        assertEquals("Cases produced LF bytes:\n$report", 0, failures)
    }

    @Test
    fun `Send-mode bytes always end in CR (0x0D)`() {
        val failures = mutableListOf<String>()
        for (probe in cases) {
            val bytes = encodePttPayload(probe.input, appendNewline = true)
            if (bytes.isEmpty() || bytes.last() != 0x0D.toByte()) {
                failures += "${probe.label}: last byte = ${bytes.lastOrNull()?.let { "%02x".format(it) } ?: "(empty)"}"
            }
        }
        assertTrue("Cases that don't end in CR:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    @Test
    fun `Insert-mode bytes never contain LF (0x0A)`() {
        for (probe in cases) {
            val bytes = encodePttPayload(probe.input, appendNewline = false)
            assertFalse(
                "Insert mode of '${probe.label}' produces LF: ${bytes.joinToString(" ") { "%02x".format(it) }}",
                bytes.any { it == 0x0A.toByte() },
            )
        }
    }

    @Test
    fun `bytes are valid UTF-8 round-trip for any input`() {
        // Mirror the encoder's ANY_LINE_BREAK regex so the expected
        // string reflects the v1.3.3+ widened normalization (any run
        // of line-break codepoints collapses to one `\r`).
        val anyLineBreak = Regex("[\\r\\n\\u0085\\u000B\\u000C\\u2028\\u2029]+")
        for (probe in cases) {
            val bytes = encodePttPayload(probe.input, appendNewline = true)
            val expected = anyLineBreak.replace(probe.input, "\r") + "\r"
            assertEquals(
                "Round-trip mismatch for '${probe.label}'",
                expected,
                bytes.toString(Charsets.UTF_8),
            )
        }
    }

    @Test
    fun `multibyte trailing characters dont leak LF in any byte position`() {
        // Specifically targets the suspicion that some Unicode line-
        // break codepoint, when UTF-8 encoded, might happen to contain
        // a 0x0a byte. (It can't — UTF-8 continuation bytes are
        // 0x80-0xBF — but assert it anyway.)
        val tricky = listOf(
            " ", " ", "", " ", "​", "⁠",
            "﻿", "　",
        )
        for (ch in tricky) {
            val bytes = encodePttPayload("ls$ch", appendNewline = true)
            assertFalse(
                "Codepoint U+%04X encodes to bytes containing 0x0a: %s".format(
                    ch.codePointAt(0),
                    bytes.joinToString(" ") { "%02x".format(it) },
                ),
                bytes.any { it == 0x0A.toByte() },
            )
        }
    }
}
