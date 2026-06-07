package dev.kuch.termx.feature.terminal.theme

import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import dev.kuch.termx.core.domain.theme.TerminalTheme

/**
 * Bridge between [TerminalTheme] (termx's domain palette) and Termux's
 * [com.termux.terminal.TerminalColors] state.
 *
 * Mapping:
 *  - indices  0..15  → `theme.ansi[0..15]` (standard + bright ANSI)
 *  - index    256    → `theme.foreground`  (`COLOR_INDEX_FOREGROUND`)
 *  - index    257    → `theme.background`  (`COLOR_INDEX_BACKGROUND`)
 *  - index    258    → `theme.cursor`      (`COLOR_INDEX_CURSOR`)
 *
 * The xterm 16..255 ramp is left untouched so 256-color escape sequences
 * keep rendering Termux's defaults; widening the theme spec to cover that
 * ramp is a future call.
 *
 * ## Two surfaces
 *
 * [installAsDefault] is the load-bearing one: it mutates
 * [TerminalColors.COLOR_SCHEME]'s static `mDefaultColors[]` so that
 * (a) every fresh `TerminalEmulator` is born with the theme already
 * applied (its `TerminalColors()` constructor calls `reset()`, which
 * copies from these defaults), and (b) every `mColors.reset()`
 * triggered by an escape sequence — `ESC c` (RIS), `ESC [!p` (DECSTR),
 * `OSC 104` (palette reset), `OSC 110/111/112` (fg/bg/cursor reset) —
 * restores the theme instead of stock xterm. Without this step, a
 * single `:colorscheme default` in vim or a `reset`/`clear` call from
 * the user's shell would silently flip the live palette back to xterm
 * defaults.
 *
 * [apply] is the live-paint variant for runtime theme changes after a
 * session is already running. It writes into the live emulator's
 * `mCurrentColors[]` AND repaints the View. It silently no-ops when
 * the View has no emulator yet — DO NOT use this from
 * `AndroidView { factory / update }` to set the initial theme: the
 * factory and the first update both run at composition-commit time,
 * BEFORE the wrapped View has been laid out, which means
 * `RemoteTerminalSession.initializeEmulator` hasn't run yet,
 * `view.mEmulator` is null, and the apply silently no-ops. This
 * exact race bites every other Compose-based fork of Termux's
 * terminal-view (Visual-Code-Space, Xed-Editor, etc.); the canonical
 * fix is [installAsDefault] from `Application.onCreate`.
 */
object ThemeBinder {

    /**
     * Install [theme] as the process-wide Termux palette default. Call
     * once from `Application.onCreate` before any session is created.
     * Re-calling is safe (idempotent overwrite).
     */
    fun installAsDefault(theme: TerminalTheme) {
        writeInto(TerminalColors.COLOR_SCHEME.mDefaultColors, theme)
    }

    /**
     * Live-paint [theme] onto the emulator currently bound to [view].
     * Used for runtime theme changes after a session is already
     * running. Silently no-ops if the View has no emulator yet — the
     * caller should rely on [installAsDefault] for the initial paint
     * instead, which has no race.
     */
    fun apply(theme: TerminalTheme, view: TerminalView) {
        val emulator = view.mEmulator ?: return
        writeInto(emulator.mColors.mCurrentColors, theme)
        view.setBackgroundColor(theme.background.toInt())
        view.invalidate()
    }

    private fun writeInto(colors: IntArray, theme: TerminalTheme) {
        theme.ansi.forEachIndexed { index, argb ->
            colors[index] = argb.toInt()
        }
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = theme.foreground.toInt()
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = theme.background.toInt()
        colors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor.toInt()
    }
}
