package dev.kuch.termx.core.domain.theme

/**
 * A terminal color scheme: background, foreground, cursor, plus the
 * 16-entry ANSI palette (0..7 standard, 8..15 bright).
 *
 * Colors are encoded as ARGB [Long]s (upper byte 0xFF) so this type stays
 * Compose-free — `:core:domain` must not depend on
 * `androidx.compose.ui.graphics.Color`. Consumers in `:feature:terminal`
 * / `:feature:settings` cast with `.toInt()` when feeding Termux's
 * [`com.termux.terminal.TerminalColors.mCurrentColors`] or with
 * `androidx.compose.ui.graphics.Color(value.toInt())` for Compose surfaces.
 */
data class TerminalTheme(
    val id: String,
    val displayName: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: List<Long>,
) {
    init {
        require(ansi.size == 16) {
            "ANSI palette must have exactly 16 entries (0-7 standard + 8-15 bright); got ${ansi.size}"
        }
    }
}
