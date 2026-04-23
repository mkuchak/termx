package dev.kuch.termx.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * Room row for [dev.kuch.termx.core.domain.model.Server].
 *
 * [authType] is stored as the enum's `name()` string; conversion is handled
 * in the mapper rather than a Room `@TypeConverter` so the schema stays
 * readable for the SQL-inspecting human.
 *
 * Both foreign keys use `SET_NULL` on delete: removing a key or group must
 * not cascade-delete every server that happened to reference it.
 */
@Entity(
    tableName = "servers",
    foreignKeys = [
        ForeignKey(
            entity = KeyPairEntity::class,
            parentColumns = ["id"],
            childColumns = ["keyPairId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ServerGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("keyPairId"),
        Index("groupId"),
        Index("sortOrder"),
    ],
)
data class ServerEntity(
    @PrimaryKey val id: UUID,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val keyPairId: UUID?,
    val groupId: UUID?,
    val useMosh: Boolean,
    val autoAttachTmux: Boolean,
    val tmuxSessionName: String,
    val lastConnected: Instant?,
    val pingMs: Int?,
    val sortOrder: Int,
    val companionInstalled: Boolean,
)
