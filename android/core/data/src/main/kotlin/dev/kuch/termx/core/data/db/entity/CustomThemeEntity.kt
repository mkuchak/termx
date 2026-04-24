package dev.kuch.termx.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Room row for a user-authored terminal palette.
 *
 * Task #48's theme editor writes one row per custom palette. The ARGB
 * swatch values live encoded as JSON in [colorsJson] to avoid a 19-column
 * table for a payload that's essentially opaque to SQLite — no query ever
 * filters on an individual color, so the columnar shape adds nothing.
 *
 * The [id] column is the same string identifier the
 * [dev.kuch.termx.core.domain.theme.TerminalTheme] consumers use; we
 * prefix user themes with `custom:` so they can't collide with the
 * built-in ids (see [dev.kuch.termx.core.domain.theme.BuiltInThemes]).
 *
 * Schema lives at v2 — introduced in the same migration that first
 * ships this table.
 */
@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val colorsJson: String,
    val createdAt: Instant,
)
