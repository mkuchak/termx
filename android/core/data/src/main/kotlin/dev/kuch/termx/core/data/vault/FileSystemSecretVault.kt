package dev.kuch.termx.core.data.vault

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64 as AndroidBase64

/**
 * [SecretVault] backed by a single plaintext JSON blob at
 * `filesDir/vault.json`. The app's per-UID sandbox is the protection
 * boundary — the file is only readable by processes running as this
 * app's UID. Biometric unlock still gates the UI via [VaultLockState],
 * but it is an app-level policy gate now, not a cryptographic key gate.
 *
 * ## Why the Android Keystore is gone
 *
 * Earlier versions (v1.1.0–v1.1.6) wrapped every entry in AES-256-GCM
 * under an Android Keystore master key. The intent was "hardware-
 * backed encryption at rest." In practice, on software-backed Keystore
 * devices the master key itself sits in the same UID sandbox the JSON
 * blob sits in — so the real boundary has always been the sandbox.
 *
 * Worse, on at least one user's device the Keystore provider surfaced
 * an opaque `NullPointerException: Attempt to get length of null
 * array` on every `Cipher.doFinal` against the master key, regardless
 * of whether the key was created with `setUserAuthenticationRequired(true)`
 * (v1.1.2 spec) or the minimal no-auth shape (v1.1.5 v2-alias spec).
 * That matches a long tail of OEM Keymint/Keystore2 bugs tracked in
 * Google IssueTracker (151002502, 323093578, 176085956) and reproduced
 * by FlowCrypt, Expo's `expo-secure-store`, adorsys, and others. We
 * cannot patch device Keystore bugs from application code.
 *
 * Switching to a plain JSON blob gives up the defence-in-depth that
 * hardware-backed Keystore WOULD have provided on a StrongBox-qualified
 * device, in exchange for the vault actually working on every device.
 * For a single-user FOSS SSH client that's the right trade.
 *
 * ## On-disk shape
 *
 * ```json
 * {
 *   "password-<uuid>": "<base64(utf-8 password bytes)>",
 *   "key-<uuid>":      "<base64(private key PEM bytes)>",
 *   "gemini.api.key":  "<base64(utf-8 api key bytes)>"
 * }
 * ```
 *
 * The atomic-rename scaffolding (`vault.json.tmp` → `vault.json`) is
 * preserved from the Keystore implementation so concurrent writes
 * cannot corrupt the blob.
 *
 * ## Lock gate
 *
 * All mutation / read methods still require
 * [VaultLockState.state] to be [VaultLockState.State.Unlocked]. The
 * biometric unlock flow is the only thing that flips that flag, so
 * the UI-level "vault is locked / unlock to continue" behaviour is
 * unchanged.
 *
 * ## Legacy-blob cleanup
 *
 * On first access we delete any lingering `vault.enc` (the old
 * encrypted blob — entries were encrypted under a Keystore key that
 * doesn't work on this device; they're unrecoverable) and the old
 * `dev.kuch.termx.vault-master{,-v2}` Keystore aliases. The user
 * re-enters any saved secrets once; thereafter the JSON blob is the
 * source of truth and nothing touches the Keystore.
 */
@Singleton
class FileSystemSecretVault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockState: VaultLockState,
) : SecretVault {

    private val blobFile: File by lazy { File(context.filesDir, BLOB_FILENAME) }
    private val tmpFile: File by lazy { File(context.filesDir, "$BLOB_FILENAME.tmp") }
    private val legacyBlobFile: File by lazy { File(context.filesDir, LEGACY_BLOB_FILENAME) }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    @Volatile private var legacyCleanupDone = false

    override suspend fun isUnlocked(): Boolean =
        lockState.state.value == VaultLockState.State.Unlocked

    override suspend fun store(alias: String, secret: ByteArray) {
        // No lock-state gate on writes. The vault file is plaintext on
        // disk; refusing to write while "Locked" doesn't protect any
        // secret — it only causes the auto-lock timer to silently kill
        // legitimate persists when it fires at an awkward moment (the
        // user backgrounds the app to copy a Gemini key from AI Studio,
        // returns >5 min later, taps Save, gets "Vault is locked"). The
        // VaultLockState lock screen still gates the UI surface via
        // NavHost — that's what actually keeps a casual onlooker from
        // reading vault.json through the app.
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLegacyCleanup()
                val map = readMap().toMutableMap()
                map[alias] = AndroidBase64.encodeToString(secret, AndroidBase64.NO_WRAP)
                writeMap(map)
            }
        }
    }

    override suspend fun load(alias: String): ByteArray? {
        // Reads stay gated as defence-in-depth: NavHost normally
        // redirects to the unlock screen before any caller reaches a
        // load() site, but a future background path (notification
        // action, broadcast receiver, …) shouldn't be able to silently
        // pull plaintext out of a locked vault.
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLegacyCleanup()
                val encoded = readMap()[alias] ?: return@withLock null
                runCatching { AndroidBase64.decode(encoded, AndroidBase64.NO_WRAP) }
                    .onFailure { Log.w(LOG_TAG, "base64 decode failed for alias=$alias", it) }
                    .getOrNull()
            }
        }
    }

    override suspend fun delete(alias: String) {
        // No lock-state gate — same reasoning as store().
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLegacyCleanup()
                val map = readMap().toMutableMap()
                if (map.remove(alias) != null) {
                    writeMap(map)
                }
            }
        }
    }

    override suspend fun exists(alias: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureLegacyCleanup()
                readMap().containsKey(alias)
            }
        }

    private fun requireUnlocked() {
        if (lockState.state.value != VaultLockState.State.Unlocked) {
            throw VaultLockedException()
        }
    }

    /**
     * One-time sweep of the pre-v1.1.7 state. Safe to call under
     * [mutex] because it only touches files and Keystore aliases that
     * the new code never reads. The [legacyCleanupDone] flag makes it
     * cheap on every subsequent call.
     */
    private fun ensureLegacyCleanup() {
        if (legacyCleanupDone) return
        legacyCleanupDone = true

        if (legacyBlobFile.exists()) {
            if (!legacyBlobFile.delete()) {
                Log.w(LOG_TAG, "failed to delete legacy $LEGACY_BLOB_FILENAME; vault will regenerate around it")
            }
        }

        // Drop the orphaned Keystore aliases from v1.1.0–v1.1.6. They
        // hold no value we can still decrypt, and leaving them behind
        // wastes Keystore slots.
        runCatching {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            for (alias in LEGACY_KEYSTORE_ALIASES) {
                if (keyStore.containsAlias(alias)) {
                    runCatching { keyStore.deleteEntry(alias) }
                        .onFailure { Log.w(LOG_TAG, "deleteEntry($alias) failed during cleanup", it) }
                }
            }
        }.onFailure { Log.w(LOG_TAG, "legacy Keystore sweep failed (non-fatal)", it) }
    }

    private fun readMap(): Map<String, String> {
        if (!blobFile.exists()) return emptyMap()
        val txt = blobFile.readText(Charsets.UTF_8)
        if (txt.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString(mapSerializer, txt) }
            .onFailure { Log.w(LOG_TAG, "vault.json parse failed; returning empty map", it) }
            .getOrDefault(emptyMap())
    }

    private fun writeMap(map: Map<String, String>) {
        val txt = json.encodeToString(mapSerializer, map)
        tmpFile.writeText(txt, Charsets.UTF_8)
        // Atomic rename on POSIX filesystems — matches Android's own
        // SharedPreferences persistence contract.
        if (!tmpFile.renameTo(blobFile)) {
            blobFile.delete()
            check(tmpFile.renameTo(blobFile)) { "Failed to persist vault blob" }
        }
    }

    private companion object {
        const val LOG_TAG = "FileSystemSecretVault"

        /** New plaintext JSON blob — all writes go here. */
        const val BLOB_FILENAME = "vault.json"

        /** Pre-v1.1.7 AES-GCM blob. Deleted on first access and never recreated. */
        const val LEGACY_BLOB_FILENAME = "vault.enc"

        const val KEYSTORE_PROVIDER = "AndroidKeyStore"

        /** Master-key aliases created by v1.1.0–v1.1.4 (V1) and v1.1.5–v1.1.6 (V2). */
        val LEGACY_KEYSTORE_ALIASES = listOf(
            "dev.kuch.termx.vault-master",
            "dev.kuch.termx.vault-master-v2",
        )
    }
}
