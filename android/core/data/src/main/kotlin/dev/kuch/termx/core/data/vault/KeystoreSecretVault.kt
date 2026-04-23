package dev.kuch.termx.core.data.vault

import android.content.Context
import android.os.Build
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
 *    alias [MASTER_KEY_ALIAS] that requires user authentication per use.
 *    On API 30+ we pin to BIOMETRIC_STRONG | DEVICE_CREDENTIAL via
 *    [KeyGenParameterSpec.Builder.setUserAuthenticationParameters]. On
 *    API 28-29 we fall back to `setUserAuthenticationValidityDurationSeconds(-1)`
 *    which maps to the same "auth-required every op" behaviour.
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
        (keyStore.getKey(MASTER_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )

        val specBuilder = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            specBuilder.setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(specBuilder.build())
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
        const val MASTER_KEY_ALIAS = "dev.kuch.termx.vault-master"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val BLOB_FILENAME = "vault.enc"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
