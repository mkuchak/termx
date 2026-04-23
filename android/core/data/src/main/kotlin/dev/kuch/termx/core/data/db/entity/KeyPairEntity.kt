package dev.kuch.termx.core.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/**
 * Room row for [dev.kuch.termx.core.domain.model.KeyPair].
 *
 * Only public/metadata material is persisted here; the private bytes live
 * in the Keystore-encrypted vault and are referenced by [keystoreAlias].
 */
@Entity(tableName = "key_pairs")
data class KeyPairEntity(
    @PrimaryKey val id: UUID,
    val label: String,
    val algorithm: String,
    val publicKey: String,
    val keystoreAlias: String,
    val createdAt: Instant,
)
