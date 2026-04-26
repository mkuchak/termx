package dev.kuch.termx.feature.terminal.keys

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic encoding tests for [ExtraKeyBytes]. Covers the three
 * top-row chips (ESC/TAB/Enter) and character encoding with sticky
 * CTRL/ALT modifiers — the paths regressed across v1.1.8, v1.1.9,
 * v1.1.13 and v1.1.14 and the table-driven assertions below would
 * have caught each one.
 *
 * Arrow / Home / End / PgUp / PgDn / F-keys delegate to Termux's
 * [com.termux.terminal.KeyHandler] which reads from real Android
 * KeyEvents and isn't easily exercised in plain JVM tests; those
 * paths stay manual-test territory.
 */
class ExtraKeyBytesTest {

    private fun encode(key: ExtraKey, ctrl: Boolean = false, alt: Boolean = false) =
        ExtraKeyBytes.encode(key, ctrl, alt)

    @Test fun `Escape sends a bare ESC (0x1B)`() {
        assertArrayEquals(byteArrayOf(0x1B), encode(ExtraKey.Escape))
    }

    @Test fun `Tab sends a bare TAB (0x09)`() {
        assertArrayEquals(byteArrayOf(0x09), encode(ExtraKey.Tab))
    }

    @Test fun `Enter sends CR (0x0D), not LF`() {
        assertArrayEquals(byteArrayOf(0x0D), encode(ExtraKey.Enter))
    }

    @Test fun `Alt+Enter prefixes ESC for xterm Meta-Enter`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x0D), encode(ExtraKey.Enter, alt = true))
    }

    @Test fun `Ctrl chip alone emits no bytes`() {
        assertArrayEquals(byteArrayOf(), encode(ExtraKey.Ctrl))
    }

    @Test fun `Alt chip alone emits no bytes`() {
        assertArrayEquals(byteArrayOf(), encode(ExtraKey.Alt))
    }

    @Test fun `Ctrl+letter maps to POSIX control byte across a-z`() {
        // Ctrl+a → 0x01, Ctrl+b → 0x02 … Ctrl+z → 0x1A
        for (letter in 'a'..'z') {
            val expected = (letter.code - 'a'.code + 1).toByte()
            assertArrayEquals(
                "Ctrl+$letter should be 0x${"%02X".format(expected.toInt() and 0xFF)}",
                byteArrayOf(expected),
                encode(ExtraKey.Char(letter), ctrl = true),
            )
        }
    }

    @Test fun `Ctrl+letter is case-insensitive`() {
        // Tap CTRL on the bar then type 'C' on a shifted IME →
        // should still produce 0x03, not a literal 'C'.
        assertArrayEquals(byteArrayOf(0x03), encode(ExtraKey.Char('C'), ctrl = true))
        assertArrayEquals(byteArrayOf(0x03), encode(ExtraKey.Char('c'), ctrl = true))
    }

    @Test fun `Ctrl plus special chars match xterm control mapping`() {
        assertArrayEquals(byteArrayOf(0x1B), encode(ExtraKey.Char('['), ctrl = true))
        assertArrayEquals(byteArrayOf(0x1C), encode(ExtraKey.Char('\\'), ctrl = true))
        assertArrayEquals(byteArrayOf(0x1D), encode(ExtraKey.Char(']'), ctrl = true))
        assertArrayEquals(byteArrayOf(0x1E), encode(ExtraKey.Char('^'), ctrl = true))
        assertArrayEquals(byteArrayOf(0x1F), encode(ExtraKey.Char('_'), ctrl = true))
        // @ and space → NUL (Ctrl+@)
        assertArrayEquals(byteArrayOf(0x00), encode(ExtraKey.Char('@'), ctrl = true))
        assertArrayEquals(byteArrayOf(0x00), encode(ExtraKey.Char(' '), ctrl = true))
        // ? → DEL (0x7F)
        assertArrayEquals(byteArrayOf(0x7F), encode(ExtraKey.Char('?'), ctrl = true))
    }

    @Test fun `Ctrl plus a digit without a control mapping passes through`() {
        // Ctrl+5 has no canonical control byte, encodeChar falls
        // through to the raw character.
        val out = encode(ExtraKey.Char('5'), ctrl = true)
        assertEquals("expected raw '5' bytes", "5".toByteArray().toList(), out.toList())
    }

    @Test fun `Alt+letter ESC-prefixes the UTF-8 bytes`() {
        // Alt+b → ESC + 'b', the xterm convention for Meta-letter.
        assertArrayEquals(byteArrayOf(0x1B, 'b'.code.toByte()), encode(ExtraKey.Char('b'), alt = true))
        assertArrayEquals(byteArrayOf(0x1B, 'F'.code.toByte()), encode(ExtraKey.Char('F'), alt = true))
    }

    @Test fun `plain printable char emits its own UTF-8 bytes`() {
        assertArrayEquals(byteArrayOf('|'.code.toByte()), encode(ExtraKey.Char('|')))
        assertArrayEquals(byteArrayOf('/'.code.toByte()), encode(ExtraKey.Char('/')))
    }

    @Test fun `multibyte UTF-8 char survives without modifiers`() {
        // 'ç' = 0xC3 0xA7 in UTF-8. The literal Settings layout never
        // exposes this, but the encodeChar path should handle any Char.
        val out = encode(ExtraKey.Char('ç'))
        assertArrayEquals("ç".toByteArray(), out)
    }
}
