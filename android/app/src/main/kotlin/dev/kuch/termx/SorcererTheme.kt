package dev.kuch.termx

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import dev.kuch.termx.core.domain.theme.Sorcerer

/**
 * Builds the Material 3 [ColorScheme] that drives every Compose
 * surface in the app. Reads the raw ARGB constants from the
 * [Sorcerer] palette in `:core:domain` (which is Compose-free) and
 * maps them onto Material 3's token names.
 *
 * Mapping rationale (see Sorcerer.kt for the source palette):
 *  - `primary` = accent: the vibrant pink is the brand color, used
 *    for FABs, switches, selected tabs, primary buttons.
 *  - `secondary` = cyan: cool counterpoint, used by Material 3
 *    components that want to differentiate from primary.
 *  - `tertiary` = green: rarely surfaced; included for completeness.
 *  - `error` = accent (same as primary). Sorcerer collapses red into
 *    the accent slot, so destructive states look identical to primary
 *    actions. Faithful to the source palette; if you want them
 *    distinct, override `error` here with a different hue.
 *  - `background` is Sorcerer's main canvas; `surface` (cards,
 *    sheets, dialogs) goes DARKER per Sorcerer's "details: darker"
 *    hint — the inverse of Material 3's usual tonal-elevation rule.
 *  - `surfaceTint` is set to [Color.Transparent] so Material 3
 *    doesn't apply a primary-color wash to elevated surfaces; with
 *    the inverted dark→darker ramp, that tint would produce a
 *    confusing pink halo on cards.
 */
fun sorcererColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(Sorcerer.ACCENT.toInt()),
    onPrimary = Color(Sorcerer.FOREGROUND.toInt()),
    primaryContainer = Color(Sorcerer.PRIMARY_CONTAINER.toInt()),
    onPrimaryContainer = Color(Sorcerer.ON_PRIMARY_CONTAINER.toInt()),
    inversePrimary = Color(Sorcerer.ACCENT.toInt()),

    secondary = Color(Sorcerer.NORMAL_CYAN.toInt()),
    onSecondary = Color(Sorcerer.BACKGROUND.toInt()),
    secondaryContainer = Color(Sorcerer.SECONDARY_CONTAINER.toInt()),
    onSecondaryContainer = Color(Sorcerer.ON_SECONDARY_CONTAINER.toInt()),

    tertiary = Color(Sorcerer.NORMAL_GREEN.toInt()),
    onTertiary = Color(Sorcerer.BACKGROUND.toInt()),
    tertiaryContainer = Color(Sorcerer.TERTIARY_CONTAINER.toInt()),
    onTertiaryContainer = Color(Sorcerer.ON_TERTIARY_CONTAINER.toInt()),

    error = Color(Sorcerer.ACCENT.toInt()),
    onError = Color(Sorcerer.FOREGROUND.toInt()),
    errorContainer = Color(Sorcerer.PRIMARY_CONTAINER.toInt()),
    onErrorContainer = Color(Sorcerer.ON_PRIMARY_CONTAINER.toInt()),

    background = Color(Sorcerer.BACKGROUND.toInt()),
    onBackground = Color(Sorcerer.FOREGROUND.toInt()),

    surface = Color(Sorcerer.DARKER_SURFACE.toInt()),
    onSurface = Color(Sorcerer.FOREGROUND.toInt()),
    surfaceVariant = Color(Sorcerer.SURFACE_VARIANT.toInt()),
    onSurfaceVariant = Color(Sorcerer.NORMAL_WHITE.toInt()),
    surfaceTint = Color.Transparent,

    // Material 3 elevated containers — Card defaults to surfaceContainerHigh,
    // so the most user-visible token of this set is that one. Without
    // explicit values here the framework falls back to a stock M3 dark
    // gray, which is exactly the "PTT card has a gray background" leak
    // v1.3.0 left behind.
    surfaceContainerLowest = Color(Sorcerer.SURFACE_CONTAINER_LOWEST.toInt()),
    surfaceContainerLow = Color(Sorcerer.SURFACE_CONTAINER_LOW.toInt()),
    surfaceContainer = Color(Sorcerer.SURFACE_CONTAINER.toInt()),
    surfaceContainerHigh = Color(Sorcerer.SURFACE_CONTAINER_HIGH.toInt()),
    surfaceContainerHighest = Color(Sorcerer.SURFACE_CONTAINER_HIGHEST.toInt()),
    surfaceDim = Color(Sorcerer.SURFACE_DIM.toInt()),
    surfaceBright = Color(Sorcerer.SURFACE_BRIGHT.toInt()),

    outline = Color(Sorcerer.OUTLINE.toInt()),
    outlineVariant = Color(Sorcerer.OUTLINE_VARIANT.toInt()),

    inverseSurface = Color(Sorcerer.FOREGROUND.toInt()),
    inverseOnSurface = Color(Sorcerer.BACKGROUND.toInt()),

    scrim = Color.Black,
)
