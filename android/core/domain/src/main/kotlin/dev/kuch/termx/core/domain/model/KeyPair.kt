package dev.kuch.termx.core.domain.model

import java.time.Instant
import java.util.UUID

/**
 * Metadata for a private/public key pair.
 *
 * The private key bytes are NOT stored here — [keystoreAlias] is an opaque
 * handle into the Keystore-encrypted vault (Task #20). Only [publicKey]
 * (OpenSSH `ssh-ed25519 AAAA...` format) is safe to persist in Room.
 */
data class KeyPair(
    val id: UUID,
    val label: String,
    val algorithm: KeyAlgorithm,
    val publicKey: String,
    val keystoreAlias: String,
    val createdAt: Instant,
)
