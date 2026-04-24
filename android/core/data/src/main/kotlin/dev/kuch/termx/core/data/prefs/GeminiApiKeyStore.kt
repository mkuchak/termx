package dev.kuch.termx.core.data.prefs

import dev.kuch.termx.core.data.vault.SecretVault
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin Task #41 wrapper around [SecretVault] that stores the user's
 * Gemini API key under a stable alias.
 *
 * We do not use DataStore for this — the key is secret and belongs in
 * the same Keystore-backed blob that holds SSH private keys and cached
 * passwords. Because the vault is biometric-gated, the Gemini-transcribe
 * path on the PTT FAB will fail with [dev.kuch.termx.core.data.vault.VaultLockedException]
 * when the vault is locked; the UI surface handles that by nudging the
 * user to unlock.
 *
 * API key bytes are UTF-8 encoded — Google's issued API keys are ASCII
 * but we use UTF-8 defensively for future proofing.
 */
@Singleton
class GeminiApiKeyStore @Inject constructor(
    private val vault: SecretVault,
) {

    /** Encrypt [key] and persist under [ALIAS]. Overwrites any prior value. */
    suspend fun put(key: String) {
        vault.store(ALIAS, key.toByteArray(StandardCharsets.UTF_8))
    }

    /** Decrypt and return the stored key, or null if never set / cleared. */
    suspend fun get(): String? =
        vault.load(ALIAS)?.toString(StandardCharsets.UTF_8)

    /** Membership check that does not decrypt. Cheap. */
    suspend fun exists(): Boolean = vault.exists(ALIAS)

    /** Remove the API key from the vault. No-op when absent. */
    suspend fun clear() {
        vault.delete(ALIAS)
    }

    private companion object {
        const val ALIAS = "gemini.api.key"
    }
}
