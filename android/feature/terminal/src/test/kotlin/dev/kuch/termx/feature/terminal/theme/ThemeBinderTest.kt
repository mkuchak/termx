package dev.kuch.termx.feature.terminal.theme

import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import dev.kuch.termx.core.domain.theme.Sorcerer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the [ThemeBinder.installAsDefault] path. The static
 * [TerminalColors.COLOR_SCHEME] is process-global JVM state, so each
 * test snapshots it on entry and restores on exit to keep the suite
 * order-independent.
 */
class ThemeBinderTest {

    private val snapshot: IntArray =
        TerminalColors.COLOR_SCHEME.mDefaultColors.copyOf()

    @After
    fun restoreDefaults() {
        System.arraycopy(
            snapshot,
            0,
            TerminalColors.COLOR_SCHEME.mDefaultColors,
            0,
            snapshot.size,
        )
    }

    @Test
    fun `installAsDefault writes Sorcerer into the static color scheme`() {
        ThemeBinder.installAsDefault(Sorcerer.terminalTheme)

        val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
        // Spot-check a few representative slots.
        assertEquals(
            "ansi[1] = red maps to Sorcerer accent",
            Sorcerer.NORMAL_RED.toInt(),
            defaults[1],
        )
        assertEquals(
            "ansi[2] = green maps to Sorcerer lime",
            Sorcerer.NORMAL_GREEN.toInt(),
            defaults[2],
        )
        assertEquals(
            "ansi[4] = blue maps to Sorcerer cyan",
            Sorcerer.NORMAL_BLUE.toInt(),
            defaults[4],
        )
        assertEquals(
            "BACKGROUND defaults to Sorcerer canvas",
            Sorcerer.BACKGROUND.toInt(),
            defaults[TextStyle.COLOR_INDEX_BACKGROUND],
        )
        assertEquals(
            "FOREGROUND defaults to Sorcerer foreground",
            Sorcerer.FOREGROUND.toInt(),
            defaults[TextStyle.COLOR_INDEX_FOREGROUND],
        )
        assertEquals(
            "CURSOR defaults to Sorcerer accent",
            Sorcerer.ACCENT.toInt(),
            defaults[TextStyle.COLOR_INDEX_CURSOR],
        )
    }

    @Test
    fun `freshly constructed TerminalColors inherits installed defaults`() {
        ThemeBinder.installAsDefault(Sorcerer.terminalTheme)

        val colors = TerminalColors()

        // Pre-fix, this would have been Termux's stock 0xFFCD0000 dim red.
        assertEquals(Sorcerer.NORMAL_RED.toInt(), colors.mCurrentColors[1])
        assertEquals(
            Sorcerer.BACKGROUND.toInt(),
            colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
        )
        assertEquals(
            Sorcerer.ACCENT.toInt(),
            colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR],
        )
    }

    @Test
    fun `mColors-reset after installAsDefault restores theme not xterm`() {
        // This is the load-bearing scenario: shells / vim / tmux frequently
        // emit reset escapes (RIS, DECSTR, OSC 104, OSC 110/111/112) which
        // call mColors.reset() on the live emulator. Reset reads from the
        // STATIC mDefaultColors — so a per-session-only fix would let the
        // shell flip Sorcerer back to xterm defaults. Test here that
        // installAsDefault makes resets idempotent on Sorcerer.
        val colors = TerminalColors()
        // Simulate something on the wire mutating the live palette.
        colors.mCurrentColors[1] = 0xFF000001.toInt()
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF000002.toInt()

        ThemeBinder.installAsDefault(Sorcerer.terminalTheme)
        // Simulate the shell sending RIS / OSC 104 — this is the same
        // call path TerminalEmulator hits when those escapes arrive.
        colors.reset()

        assertEquals(
            "RIS / OSC 104 must restore Sorcerer red, not xterm dim red",
            Sorcerer.NORMAL_RED.toInt(),
            colors.mCurrentColors[1],
        )
        assertEquals(
            "RIS / OSC 104 must restore Sorcerer canvas, not xterm black",
            Sorcerer.BACKGROUND.toInt(),
            colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
        )
    }

    @Test
    fun `single-index reset uses installed default for that slot`() {
        // OSC 110 / 111 / 112 reset only foreground / background / cursor
        // respectively. They go through TerminalColors.reset(int index)
        // which reads from mDefaultColors[index]. Verify that path picks
        // up Sorcerer too.
        val colors = TerminalColors()
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xFF111111.toInt()

        ThemeBinder.installAsDefault(Sorcerer.terminalTheme)
        colors.reset(TextStyle.COLOR_INDEX_BACKGROUND)

        assertEquals(
            Sorcerer.BACKGROUND.toInt(),
            colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND],
        )
    }
}
