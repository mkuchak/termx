package dev.kuch.termx.core.domain.theme

/**
 * Sorcerer — the single theme termx ships from v1.3.0 onward.
 *
 * Inspired by the VSCode Sorcerer theme. A "limited palette" design
 * that collapses red and magenta into the same vibrant pink accent,
 * with cool cyan + lime accents balanced against a near-black canvas
 * and a deliberately darker surface ramp ("details: darker").
 *
 * This object is the single source of truth for every color in the
 * app:
 *  - [terminalTheme]  → the [TerminalTheme] handed to
 *    `dev.kuch.termx.feature.terminal.theme.ThemeBinder` to drive the
 *    16 ANSI + fg/bg/cursor slots in Termux's renderer.
 *  - The raw ARGB constants below are also consumed by `:app`'s
 *    `SorcererTheme.sorcererColorScheme()` to build the Material 3
 *    [androidx.compose.material3.ColorScheme]. That builder lives in
 *    `:app` because `:core:domain` must stay Compose-free (see
 *    [TerminalTheme] KDoc for the rationale).
 *
 * Coherence note: Sorcerer's `red == magenta == accent`. In Material
 * 3 token terms this means `error == primary` — destructive states
 * look identical to primary actions. This is faithful to the
 * upstream theme, not a bug; pick a different `error` token in
 * SorcererTheme.kt if you want the visual distinction back.
 */
object Sorcerer {

    // Source palette (ARGB Longs, 0xFF alpha).
    const val ACCENT: Long = 0xFFFF006A
    const val BACKGROUND: Long = 0xFF0E141A
    const val FOREGROUND: Long = 0xFFFFFFFF

    // Terminal palette — normal.
    const val NORMAL_BLACK: Long = 0xFF0A1016
    const val NORMAL_RED: Long = 0xFFFF006A
    const val NORMAL_GREEN: Long = 0xFFAAED36
    const val NORMAL_YELLOW: Long = 0xFFF5AF19
    const val NORMAL_BLUE: Long = 0xFF44DFFF
    const val NORMAL_MAGENTA: Long = 0xFFFF006A
    const val NORMAL_CYAN: Long = 0xFF44DFFF
    const val NORMAL_WHITE: Long = 0xFFDDDDDD

    // Terminal palette — bright.
    const val BRIGHT_BLACK: Long = 0xFF5A6986
    const val BRIGHT_RED: Long = 0xFFFF006A
    const val BRIGHT_GREEN: Long = 0xFFAAED36
    const val BRIGHT_YELLOW: Long = 0xFFF5AF19
    const val BRIGHT_BLUE: Long = 0xFF44DFFF
    const val BRIGHT_MAGENTA: Long = 0xFFFF006A
    const val BRIGHT_CYAN: Long = 0xFF44DFFF
    const val BRIGHT_WHITE: Long = 0xFFFFFFFF

    /**
     * Derived shades — needed for Material 3 container tokens that
     * Sorcerer's source palette doesn't define directly. Hand-tuned
     * once; if you want different shading, change them here, not at
     * the call site.
     *
     *  - [DARKER_SURFACE] sits below [BACKGROUND], honoring Sorcerer's
     *    "details: darker" inversion. Used as Material 3 `surface`.
     *  - [SURFACE_VARIANT] sits between [BACKGROUND] and [DARKER_SURFACE].
     *    Used for chips / dropdowns / inactive controls.
     *  - [PRIMARY_CONTAINER] is a deeply darkened accent, readable
     *    underneath light pink text.
     *  - [ON_PRIMARY_CONTAINER] is a high-contrast light pink for
     *    text on the dark accent container.
     *  - [OUTLINE_VARIANT] is a quiet border tone.
     */
    const val DARKER_SURFACE: Long = NORMAL_BLACK            // #0A1016
    const val SURFACE_VARIANT: Long = 0xFF141A20             // between bg and surface
    const val PRIMARY_CONTAINER: Long = 0xFF4A0020           // deep pink
    const val ON_PRIMARY_CONTAINER: Long = 0xFFFFD0E0        // light pink
    const val SECONDARY_CONTAINER: Long = 0xFF003A4A         // deep cyan
    const val ON_SECONDARY_CONTAINER: Long = 0xFFC0EEFF      // light cyan
    const val TERTIARY_CONTAINER: Long = 0xFF2A4D00          // deep green
    const val ON_TERTIARY_CONTAINER: Long = 0xFFD8FFAA       // light green
    const val OUTLINE: Long = BRIGHT_BLACK                   // #5A6986
    const val OUTLINE_VARIANT: Long = 0xFF2A3540

    /** The terminal-side palette: 16 ANSI + fg/bg/cursor. */
    val terminalTheme: TerminalTheme = TerminalTheme(
        id = "sorcerer",
        displayName = "Sorcerer",
        background = BACKGROUND,
        foreground = FOREGROUND,
        cursor = ACCENT,
        ansi = listOf(
            NORMAL_BLACK, NORMAL_RED, NORMAL_GREEN, NORMAL_YELLOW,
            NORMAL_BLUE, NORMAL_MAGENTA, NORMAL_CYAN, NORMAL_WHITE,
            BRIGHT_BLACK, BRIGHT_RED, BRIGHT_GREEN, BRIGHT_YELLOW,
            BRIGHT_BLUE, BRIGHT_MAGENTA, BRIGHT_CYAN, BRIGHT_WHITE,
        ),
    )
}
