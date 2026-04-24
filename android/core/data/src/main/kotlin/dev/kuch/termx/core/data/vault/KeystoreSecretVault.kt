package dev.kuch.termx.core.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Base64 as AndroidBase64

/**
 * Real [SecretVault] implementation backed by:
 *
 * 1. An Android Keystore AES-256-GCM symmetric master key stored under
 *    alias [MASTER_KEY_ALIAS_V2]. The key is hardware-backed and bound
 *    to this device/install, but does NOT require per-operation user
 *    authentication — the app-level [VaultLockState] (biometric prompt
 *    on launch + auto-lock timer) is the access gate. An earlier spec
 *    (stored under the legacy [MASTER_KEY_ALIAS_V1]) used
 *    `setUserAuthenticationParameters(0, …)` / `-1` validity which
 *    demanded a CryptoObject-bound prompt per op; our unlock flow
 *    doesn't use a CryptoObject, and some devices surfaced that as an
 *    opaque "Attempt to get length of null array" NPE from deep in the
 *    Keystore provider. Migration strategy is version-aliased: if the
 *    v2 key exists we use it; otherwise we wipe the v1 alias and the
 *    vault blob (the old entries are unrecoverable without auth) and
 *    generate a fresh v2. This is deterministic — once v2 exists we
 *    never re-enter the migration path, so the user only re-enters
 *    their secrets once.
 *
 * 2. A single blob file `filesDir/vault.enc` holding a JSON map from
 *    opaque alias → base64(iv || ciphertext_with_gcm_tag). A fresh
 *    12-byte IV is generated for every store.
 *
 * Lock gate: all mutation / read methods require
 * [VaultLockState.state] to be [VaultLockState.State.Unlocked] or they
 * throw [VaultLockedException]. The biometric unlock flow is responsible
 * for flipping that flag after a successful prompt.
 *
 * The [Mutex] serialises access to the blob file so concurrent callers
 * cannot corrupt the atomic `vault.enc.tmp` → `vault.enc` rename.
 */
@Singleton
class KeystoreSecretVault @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockState: VaultLockState,
) : SecretVault {

    private val blobFile: File by lazy { File(context.filesDir, BLOB_FILENAME) }
    private val tmpFile: File by lazy { File(context.filesDir, "$BLOB_FILENAME.tmp") }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    override suspend fun isUnlocked(): Boolean =
        lockState.state.value == VaultLockState.State.Unlocked

    override suspend fun store(alias: String, secret: ByteArray) {
        requireUnlocked()
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Resolve (and migrate, if needed) the master key BEFORE
                // reading the blob. Migration wipes vault.enc to avoid
                // carrying forward entries encrypted under the legacy key;
                // if we read the map first, we'd otherwise resurrect those
                // stale entries in writeMap below.
                getOrCreateMasterKey()
                val map = readMap().toMutableMap()
                map[alias] = encryptToBase64(secret)
                writeMap(map)
            }
        }
    }

    override suspend fun load(alias: String): ByteArray? {
        requireUnlocked()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                // See store() for why the key resolves before the blob read.
                getOrCreateMasterKey()
                val encoded = readMap()[alias] ?: return@withLock null
                decryptFromBase64(encoded)
            }
        }
    }

    override suspend fun delete(alias: String) {
        requireUnlocked()
        withContext(Dispatchers.IO) {
            mutex.withLock {
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
                readMap().containsKey(alias)
            }
        }

    private fun requireUnlocked() {
        if (lockState.state.value != VaultLockState.State.Unlocked) {
            throw VaultLockedException()
        }
    }

    // --- Keystore -----------------------------------------------------------

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        // Happy path: the current-shape key is already present.
        (keyStore.getKey(MASTER_KEY_ALIAS_V2, null) as? SecretKey)?.let { return it }

        // No v2 yet — look for the legacy alias so we know whether this is
        // a fresh install or an upgrade from the pre-per-op-auth scheme.
        // Introspecting the legacy key via SecretKeyFactory + KeyInfo is
        // unreliable on some devices (NoSuchAlgorithmException / opaque
        // Keystore errors), so we don't ask questions — if v1 exists, wipe
        // v1 + the vault blob and start fresh under v2. If v1 also isn't
        // there, this is a clean install.
        if (keyStore.containsAlias(MASTER_KEY_ALIAS_V1)) {
            runCatching { keyStore.deleteEntry(MASTER_KEY_ALIAS_V1) }
            runCatching { blobFile.delete() }
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )

        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS_V2,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }

    // --- Crypto -------------------------------------------------------------

    private fun encryptToBase64(plaintext: ByteArray): String {
        val key = getOrCreateMasterKey()
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        val combined = ByteArray(iv.size + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
        }
        return AndroidBase64.encodeToString(combined, AndroidBase64.NO_WRAP)
    }

    private fun decryptFromBase64(encoded: String): ByteArray {
        val combined = AndroidBase64.decode(encoded, AndroidBase64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "Ciphertext blob is truncated" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    // --- Blob I/O -----------------------------------------------------------

    private fun readMap(): Map<String, String> {
        if (!blobFile.exists()) return emptyMap()
        val txt = blobFile.readText(Charsets.UTF_8)
        if (txt.isBlank()) return emptyMap()
        return json.decodeFromString(mapSerializer, txt)
    }

    private fun writeMap(map: Map<String, String>) {
        val txt = json.encodeToString(mapSerializer, map)
        tmpFile.writeText(txt, Charsets.UTF_8)
        // Atomic rename on POSIX filesystems — matches Android's own
        // SharedPreferences persistence contract.
        if (!tmpFile.renameTo(blobFile)) {
            // Fallback: renameTo can fail if target exists on some FS.
            blobFile.delete()
            check(tmpFile.renameTo(blobFile)) { "Failed to persist vault blob" }
        }
    }

    private companion object {
        /** Legacy alias — created with setUserAuthenticationRequired(true). */
        const val MASTER_KEY_ALIAS_V1 = "dev.kuch.termx.vault-master"

        /** Current alias — no per-op auth; app-level VaultLockState gates access. */
        const val MASTER_KEY_ALIAS_V2 = "dev.kuch.termx.vault-master-v2"

        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val BLOB_FILENAME = "vault.enc"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
