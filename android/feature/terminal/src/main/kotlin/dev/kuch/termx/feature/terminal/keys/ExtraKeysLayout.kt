/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

/**
 * Default layout for the extra-keys toolbar: one ordered list rendered
 * as a single horizontally-scrollable row, most-used keys first:
 *
 *  ESC TAB CTRL ALT ↑ ↓ ← → HOME END PGUP PGDN | ~ \ /
 *
 * (Replaces the former two-row pager layout — keys no longer teleport
 * between pages; off-screen keys are hinted by a fading edge.)
 *
 * User-editable layouts + presets (Termux classic / Vim / Claude Code) ship
 * with the server-manager settings work in Phase 2+. The list stays a `val`
 * of `ExtraKey` so a future settings screen can just swap in a different
 * `List<ExtraKey>` without touching rendering or encoding.
 */
object ExtraKeysLayout {
    val KEYS: List<ExtraKey> = listOf(
        ExtraKey.Escape,
        ExtraKey.Tab,
        ExtraKey.Ctrl,
        ExtraKey.Alt,
        ExtraKey.Arrow(ArrowDir.Up),
        ExtraKey.Arrow(ArrowDir.Down),
        ExtraKey.Arrow(ArrowDir.Left),
        ExtraKey.Arrow(ArrowDir.Right),
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
