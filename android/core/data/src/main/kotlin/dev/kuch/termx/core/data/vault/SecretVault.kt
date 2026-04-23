package dev.kuch.termx.core.data.vault

/**
 * Abstraction over the Keystore-encrypted secret blob.
 *
 * The vault holds every piece of persistently-stored sensitive bytes —
 * SSH private keys (`KeyPair.keystoreAlias`), stored SSH passwords, the
 * Gemini API key once Phase 6 lands. Access is gated by biometric auth
 * flowing through [KeystoreSecretVault] and [VaultLockState].
 *
 * Callers address secrets via opaque alias strings. The domain model
 * (e.g. [dev.kuch.termx.core.domain.model.KeyPair.keystoreAlias]) owns
 * alias generation — this layer only cares about bytes.
 */
interface SecretVault {

    /** True when the master key has been unwrapped by a recent biometric prompt. */
    suspend fun isUnlocked(): Boolean

    /**
     * Encrypt [secret] under the master AES-256-GCM key and persist it
     * against [alias]. Overwrites any existing value.
     *
     * @throws VaultLockedException when the vault is currently [VaultLockState.State.Locked].
     */
    suspend fun store(alias: String, secret: ByteArray)

    /** Decrypts and returns the secret for [alias], or null if never stored. */
    suspend fun load(alias: String): ByteArray?

    /** Removes [alias] from the blob. No-op if absent. */
    suspend fun delete(alias: String)

    /** Cheap membership check that does not decrypt the payload. */
    suspend fun exists(alias: String): Boolean
}
