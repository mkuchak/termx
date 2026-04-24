package dev.kuch.termx.core.domain.theme

/**
 * The six opinionated built-in themes shipped in v0.x. Every palette
 * uses the canonical hex codes from each theme's upstream project. The
 * 0xFF alpha is baked in so values flow straight into the ARGB int
 * arrays Termux and Compose both expect.
 *
 * See [TerminalTheme] for the ARGB-encoding contract. Custom themes
 * (Task #48) will layer over the same type and just add entries to the
 * list; no code outside this file needs to know the list is finite.
 */
object BuiltInThemes {

    val dracula: TerminalTheme = TerminalTheme(
        id = "dracula",
        displayName = "Dracula",
        background = 0xFF282A36,
        foreground = 0xFFF8F8F2,
        cursor = 0xFFF8F8F2,
        ansi = listOf(
            0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C,
            0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
            0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5,
            0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF,
        ),
    )

    val nord: TerminalTheme = TerminalTheme(
        id = "nord",
        displayName = "Nord",
        background = 0xFF2E3440,
        foreground = 0xFFD8DEE9,
        cursor = 0xFFD8DEE9,
        ansi = listOf(
            0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
            0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B,
            0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4,
        ),
    )

    val gruvboxDark: TerminalTheme = TerminalTheme(
        id = "gruvbox-dark",
        displayName = "Gruvbox Dark",
        background = 0xFF282828,
        foreground = 0xFFEBDBB2,
        cursor = 0xFFEBDBB2,
        ansi = listOf(
            0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921,
            0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
            0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F,
            0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2,
        ),
    )

    val tokyoNight: TerminalTheme = TerminalTheme(
        id = "tokyo-night",
        displayName = "Tokyo Night",
        background = 0xFF1A1B26,
        foreground = 0xFFC0CAF5,
        cursor = 0xFFC0CAF5,
        ansi = listOf(
            0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
            0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
            0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68,
            0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5,
        ),
    )

    val catppuccinMocha: TerminalTheme = TerminalTheme(
        id = "catppuccin-mocha",
        displayName = "Catppuccin Mocha",
        background = 0xFF1E1E2E,
        foreground = 0xFFCDD6F4,
        cursor = 0xFFF5E0DC,
        ansi = listOf(
            0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
            0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
            0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF,
            0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8,
        ),
    )

    val solarizedDark: TerminalTheme = TerminalTheme(
        id = "solarized-dark",
        displayName = "Solarized Dark",
        background = 0xFF002B36,
        foreground = 0xFF839496,
        cursor = 0xFF93A1A1,
        ansi = listOf(
            0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900,
            0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
            0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83,
            0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3,
        ),
    )

    val all: List<TerminalTheme> = listOf(
        dracula,
        nord,
        gruvboxDark,
        tokyoNight,
        catppuccinMocha,
        solarizedDark,
    )

    fun byId(id: String): TerminalTheme = all.firstOrNull { it.id == id } ?: dracula
}
