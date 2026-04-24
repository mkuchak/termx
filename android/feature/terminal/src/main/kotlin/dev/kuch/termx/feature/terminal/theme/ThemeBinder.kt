package dev.kuch.termx.feature.terminal.theme

import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import dev.kuch.termx.core.domain.theme.TerminalTheme

/**
 * Paints a [TerminalTheme] onto Termux's
 * [com.termux.terminal.TerminalColors] palette for the currently-bound
 * emulator on [TerminalView].
 *
 * Mapping (from [TextStyle]):
 *  - indices  0..15  → `theme.ansi[0..15]` (standard + bright ANSI)
 *  - index    256    → `theme.foreground`  (`COLOR_INDEX_FOREGROUND`)
 *  - index    257    → `theme.background`  (`COLOR_INDEX_BACKGROUND`)
 *  - index    258    → `theme.cursor`      (`COLOR_INDEX_CURSOR`)
 *
 * The `mColors.mCurrentColors` field is a public `int[]` on Termux's
 * [com.termux.terminal.TerminalColors] — we overwrite those four
 * "interesting" slots and leave the xterm 16..255 ramp alone so 256-color
 * escape sequences keep rendering the Termux defaults. (A future theme
 * spec can widen to the ramp; none of the built-ins define one.)
 *
 * No-op when the view has no emulator attached yet (initial frame
 * during AndroidView creation).
 */
object ThemeBinder {

    fun apply(theme: TerminalTheme, view: TerminalView) {
        val emulator = view.mEmulator ?: return
        val colors = emulator.mColors.mCurrentColors

        theme.ansi.forEachIndexed { index, argb ->
            colors[index] = argb.toInt()
        }
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = theme.foreground.toInt()
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = theme.background.toInt()
        colors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor.toInt()

        view.setBackgroundColor(theme.background.toInt())
        view.invalidate()
    }
}
