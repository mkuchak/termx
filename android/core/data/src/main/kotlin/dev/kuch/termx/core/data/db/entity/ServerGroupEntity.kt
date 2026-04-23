package dev.kuch.termx.core.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Room row for [dev.kuch.termx.core.domain.model.ServerGroup]. */
@Entity(
    tableName = "server_groups",
    indices = [Index("sortOrder")],
)
data class ServerGroupEntity(
    @PrimaryKey val id: UUID,
    val name: String,
    val sortOrder: Int,
    val isCollapsed: Boolean,
)
