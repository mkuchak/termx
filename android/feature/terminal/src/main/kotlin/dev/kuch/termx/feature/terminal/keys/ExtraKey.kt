/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

/**
 * One button in the extra-keys toolbar.
 *
 * Each variant renders as a single soft-key above the IME. The sealed
 * shape keeps the layout list self-describing — the renderer switches on
 * the instance, the encoder switches on the instance, and end users can
 * later define a `List<ExtraKey>` from settings without needing to
 * reach into a string DSL.
 */
sealed class ExtraKey {
    /** Literal character key (eg. `|`, `~`, `\`, `/`). Encoded as UTF-8. */
    data class Char(val c: kotlin.Char, val display: String = c.toString()) : ExtraKey()

    /** ESC — sends `0x1B`. */
    data object Escape : ExtraKey()

    /** TAB — sends `0x09`. */
    data object Tab : ExtraKey()

    /** Sticky Ctrl modifier. Taps toggle between Off/OneShot/Locked. */
    data object Ctrl : ExtraKey()

    /** Sticky Alt modifier. Taps toggle between Off/OneShot/Locked. */
    data object Alt : ExtraKey()

    /** Arrow key — emits cursor-mode-aware CSI sequences via KeyHandler. */
    data class Arrow(val dir: ArrowDir) : ExtraKey()

    /** Function key 1..12 — emits the matching xterm sequence. */
    data class Fn(val n: Int) : ExtraKey()

    data object Home : ExtraKey()
    data object End : ExtraKey()
    data object PageUp : ExtraKey()
    data object PageDown : ExtraKey()

    /** Enter — sends `0x0D` (CR). Alt+Enter prefixes with ESC. */
    data object Enter : ExtraKey()
}

enum class ArrowDir { Up, Down, Left, Right }
