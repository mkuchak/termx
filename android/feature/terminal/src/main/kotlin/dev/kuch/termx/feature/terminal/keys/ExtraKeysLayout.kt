/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

/**
 * Default two-row layout for the extra-keys toolbar.
 *
 * Matches the Phase 1 ROADMAP §1.5 spec:
 *  Row 1: ESC TAB CTRL ALT ↑ ↓ ← →
 *  Row 2: HOME END PGUP PGDN | ~ \ /
 *
 * User-editable layouts + presets (Termux classic / Vim / Claude Code) ship
 * with the server-manager settings work in Phase 2+. The lists stay `val`s
 * of `ExtraKey` so a future settings screen can just swap in a different
 * `List<ExtraKey>` without touching rendering or encoding.
 */
object ExtraKeysLayout {
    val ROW_1: List<ExtraKey> = listOf(
        ExtraKey.Escape,
        ExtraKey.Tab,
        ExtraKey.Ctrl,
        ExtraKey.Alt,
        ExtraKey.Arrow(ArrowDir.Up),
        ExtraKey.Arrow(ArrowDir.Down),
        ExtraKey.Arrow(ArrowDir.Left),
        ExtraKey.Arrow(ArrowDir.Right),
    )

    val ROW_2: List<ExtraKey> = listOf(
        ExtraKey.Home,
        ExtraKey.End,
        ExtraKey.PageUp,
        ExtraKey.PageDown,
        ExtraKey.Char('|'),
        ExtraKey.Char('~'),
        ExtraKey.Char('\\'),
        ExtraKey.Char('/'),
    )
}
