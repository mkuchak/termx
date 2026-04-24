package dev.kuch.termx.feature.settings.configsync

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto helpers for the Task #47 encrypted config export/import flow.
 *
 * Format (little-endian numeric fields are unused; all integers live in
 * a Java [ByteBuffer] which defaults to big-endian):
 *
 * ```
 *   offset  size  field
 *   ------  ----  -----------------------------------------------------
 *        0     6  magic bytes "TRMX1\0"
 *        6     2  format version (currently 1)
 *        8     4  PBKDF2 iteration count (currently 100_000)
 *       12     2  salt length N (currently 16)
 *       14     N  PBKDF2 salt bytes
 *     14+N     2  GCM IV length M (currently 12)
 *     16+N   M    AES-GCM IV bytes
 *   16+N+M   rest AES-256-GCM ciphertext followed by a 16-byte tag
 * ```
 *
 * The passphrase is stretched with PBKDF2-HMAC-SHA256 at [PBKDF2_ITERATIONS]
 * rounds into a 256-bit AES key. Encryption uses AES/GCM/NoPadding with a
 * 12-byte random IV and a 128-bit tag, which is the standard Android
 * platform recommendation and what BouncyCastle ships with.
 */
object ConfigCrypto {

    val MAGIC: ByteArray = byteArrayOf(0x54, 0x52, 0x4D, 0x58, 0x31, 0x00) // "TRMX1\0"
    const val FORMAT_VERSION: Short = 1
    const val PBKDF2_ITERATIONS: Int = 100_000
    const val SALT_BYTES: Int = 16
    const val IV_BYTES: Int = 12
    const val KEY_BITS: Int = 256
    const val GCM_TAG_BITS: Int = 128

    private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Encrypt [plaintext] under [passphrase]. Returns a byte array that
     * begins with the magic header documented above and ends with the
     * AES-GCM ciphertext+tag.
     */
    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext)
        return writeHeader(salt, iv) + ciphertext
    }

    /**
     * Decrypt a blob previously produced by [encrypt]. Throws
     * [IllegalArgumentException] if the magic header is absent or the
     * format version is unknown, or the platform's
     * [javax.crypto.AEADBadTagException] when the passphrase is wrong.
     */
    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        require(blob.size > MAGIC.size + 10) { "Encrypted blob is too small" }
        require(blob.sliceArray(MAGIC.indices).contentEquals(MAGIC)) {
            "Not a termx config export (magic bytes mismatch)"
        }
        val buffer = ByteBuffer.wrap(blob, MAGIC.size, blob.size - MAGIC.size)
        val version = buffer.short
        require(version == FORMAT_VERSION) {
            "Unsupported config version: $version (expected $FORMAT_VERSION)"
        }
        val iterations = buffer.int
        val saltLen = buffer.short.toInt()
        val salt = ByteArray(saltLen).also(buffer::get)
        val ivLen = buffer.short.toInt()
        val iv = ByteArray(ivLen).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun writeHeader(salt: ByteArray, iv: ByteArray): ByteArray {
        val size = MAGIC.size + 2 + 4 + 2 + salt.size + 2 + iv.size
        val buf = ByteBuffer.allocate(size)
        buf.put(MAGIC)
        buf.putShort(FORMAT_VERSION)
        buf.putInt(PBKDF2_ITERATIONS)
        buf.putShort(salt.size.toShort())
        buf.put(salt)
        buf.putShort(iv.size.toShort())
        buf.put(iv)
        return buf.array()
    }

    private fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS,
    ): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val derived = factory.generateSecret(spec).encoded
            return SecretKeySpec(derived, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
