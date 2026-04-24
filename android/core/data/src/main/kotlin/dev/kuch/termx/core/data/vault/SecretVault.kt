package dev.kuch.termx.core.data.vault

/**
 * Abstraction over the persistent secret blob.
 *
 * The vault holds every piece of persistently-stored sensitive bytes —
 * SSH private keys (`KeyPair.keystoreAlias`), stored SSH passwords,
 * and the Gemini API key. Access is gated by biometric auth flowing
 * through [FileSystemSecretVault] and [VaultLockState].
 *
 * The blob lives in the app's per-UID filesDir. On most Android
 * devices this is the same protection boundary a software-backed
 * Keystore key would have had; on devices where the Keystore Cipher
 * path is broken (OEM Keymint bugs) it's the ONLY boundary that
 * works. See [FileSystemSecretVault] for the full threat-model
 * discussion.
 *
 * Callers address secrets via opaque alias strings. The domain model
 * (e.g. [dev.kuch.termx.core.domain.model.KeyPair.keystoreAlias]) owns
 * alias generation — this layer only cares about bytes.
 */
interface SecretVault {

    /** True when the vault is in the Unlocked state (biometric passed). */
    suspend fun isUnlocked(): Boolean

    /**
     * Persist [secret] against [alias]. Overwrites any existing value.
     *
     * @throws VaultLockedException when the vault is currently [VaultLockState.State.Locked].
     */
    suspend fun store(alias: String, secret: ByteArray)

    /** Returns the stored secret for [alias], or null if never stored. */
    suspend fun load(alias: String): ByteArray?

    /** Removes [alias] from the blob. No-op if absent. */
    suspend fun delete(alias: String)

    /** Membership check — cheaper than [load]. */
    suspend fun exists(alias: String): Boolean
}
