package dev.kuch.termx.core.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A persisted VPS/SSH destination the user can connect to.
 *
 * Secrets are intentionally absent: [keyPairId] references a [KeyPair] whose
 * actual private bytes live in the Keystore-encrypted vault. The Room table
 * mirrors this type 1:1 — see `ServerEntity` in `:core:data`.
 */
data class Server(
    val id: UUID,
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val keyPairId: UUID?,
    /**
     * Vault key for the SSH password. Null for key-auth; for password-auth,
     * points at bytes stored under [dev.kuch.termx.core.data.vault.SecretVault]'s
     * alias (UTF-8 encoded password). Wiped on delete. Never contains the
     * plaintext.
     */
    val passwordAlias: String? = null,
    val groupId: UUID?,
    val useMosh: Boolean = true,
    val autoAttachTmux: Boolean = true,
    val tmuxSessionName: String = "main",
    val lastConnected: Instant?,
    val pingMs: Int?,
    val sortOrder: Int = 0,
    val companionInstalled: Boolean = false,
)
