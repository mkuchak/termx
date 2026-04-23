package dev.kuch.termx.core.data.db.converter

import androidx.room.TypeConverter
import java.time.Instant
import java.util.UUID

/**
 * Room type converters for values Kotlin/Java carry but SQLite doesn't
 * natively store.
 *
 * Enums ([dev.kuch.termx.core.domain.model.AuthType],
 * [dev.kuch.termx.core.domain.model.KeyAlgorithm]) are intentionally NOT
 * converted here — the entities keep them as `String` columns and the
 * domain ↔ entity mappers translate with `Enum.valueOf`/`name()`. That
 * keeps the on-disk schema readable with any SQL browser.
 */
class Converters {
    @TypeConverter
    fun uuidToString(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun stringToUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter
    fun instantToEpoch(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun epochToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
