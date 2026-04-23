package dev.kuch.termx.libs.sshnative.crypto

import dev.kuch.termx.core.domain.model.KeyAlgorithm
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.RSAPrivateKey
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * Parses user-supplied OpenSSH / PKCS#1 / PKCS#8 private keys into the
 * canonical forms termx persists:
 *   - [ParsedPrivate.privatePem]  — re-armored PEM bytes, suitable for the
 *     vault and for sshj's auto-detecting loader.
 *   - [ParsedPrivate.publicOpenSsh] — classic `ssh-<alg> AAAA...` line.
 *   - [ParsedPrivate.algorithm]    — the termx enum variant.
 *
 * Passphrase handling: pass null for unencrypted input; pass the plaintext
 * passphrase otherwise. We try every BC decryption provider that's relevant
 * to the encoding we detected, so callers don't need to know upfront whether
 * the key is PKCS#1-style or PKCS#8-style encrypted.
 */
object OpenSshKeyParser {

    /** Result of a successful [parsePrivatePem]. */
    data class ParsedPrivate(
        val algorithm: KeyAlgorithm,
        val privatePem: ByteArray,
        val publicOpenSsh: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedPrivate) return false
            if (algorithm != other.algorithm) return false
            if (!privatePem.contentEquals(other.privatePem)) return false
            if (publicOpenSsh != other.publicOpenSsh) return false
            return true
        }

        override fun hashCode(): Int {
            var result = algorithm.hashCode()
            result = 31 * result + privatePem.contentHashCode()
            result = 31 * result + publicOpenSsh.hashCode()
            return result
        }
    }

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Parse [pem] (UTF-8 bytes of a PEM blob). Returns a [ParsedPrivate]
     * with a consistent algorithm tag and a derived public-key line.
     *
     * @throws IllegalArgumentException on unsupported / malformed input.
     * @throws SecurityException when decryption fails (wrong passphrase).
     */
    fun parsePrivatePem(pem: ByteArray, passphrase: String? = null): ParsedPrivate {
        val text = pem.toString(Charsets.UTF_8)

        // Branch on the armor header first — the OpenSSH binary format lives
        // inside `-----BEGIN OPENSSH PRIVATE KEY-----` and is NOT something
        // PEMParser currently reifies into a JCA keypair without help.
        return if (text.contains(OPENSSH_HEADER)) {
            parseOpenSshPem(text, passphrase)
        } else {
            parseClassicPem(text, passphrase)
        }
    }

    /**
     * Compute `SHA256:base64(...)` for an OpenSSH public-key line. The
     * trailing `=` padding is stripped per the display convention used by
     * `ssh-keygen -lf`.
     */
    fun fingerprintSha256(publicOpenSsh: String): String {
        val body = publicOpenSsh.trim().split(' ')
            .getOrNull(1)
            ?: throw IllegalArgumentException("Public key line missing base64 body")
        val raw = Base64.getDecoder().decode(body)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$encoded"
    }

    // --- OpenSSH PEM (ed25519 + rsa) -----------------------------------------

    private fun parseOpenSshPem(text: String, passphrase: String?): ParsedPrivate {
        val binary = stripPemArmor(text, OPENSSH_KEY_TYPE)
        val params = try {
            OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(binary)
        } catch (t: Throwable) {
            // BouncyCastle will throw on encrypted-without-passphrase here.
            // We don't implement decrypting the inner OpenSSH binary format
            // ourselves — the classic PEM fallback handles `Proc-Type`
            // encrypted content, and unencrypted OpenSSH is by far the
            // common case for keys created by `ssh-keygen` without a pass.
            if (passphrase != null) {
                throw SecurityException(
                    "OpenSSH keys with an internal passphrase are not supported yet. " +
                        "Re-encrypt with `ssh-keygen -m pem -p` or import an unencrypted copy.",
                    t,
                )
            }
            throw IllegalArgumentException(
                "Failed to parse OpenSSH private key. If the key is passphrase-protected, " +
                    "re-export without a passphrase or convert to PKCS#1 / PKCS#8.",
                t,
            )
        }

        return when (params) {
            is Ed25519PrivateKeyParameters -> {
                val pub = params.generatePublicKey()
                val pubLine = buildEd25519OpenSshPublicKey(pub.encoded, comment = "")
                ParsedPrivate(
                    algorithm = KeyAlgorithm.ED25519,
                    privatePem = text.toByteArray(Charsets.UTF_8),
                    publicOpenSsh = pubLine,
                )
            }
            // BouncyCastle returns RSA keys as BC's RSAPrivateCrtKeyParameters
            // when the OpenSSH blob is RSA — fall through to the JCA branch
            // by re-delegating to the JCA converter via PEMParser.
            else -> {
                // Best-effort: feed the original PEM back through PEMParser;
                // PEMParser >= 1.70 handles OPENSSH PRIVATE KEY for RSA/ECDSA
                // via JcaPEMKeyConverter. Ed25519 is the handled-above case.
                parseClassicPem(text, passphrase)
            }
        }
    }

    // --- PKCS#1 RSA / PKCS#8 / other PEMParser cases -------------------------

    private fun parseClassicPem(text: String, passphrase: String?): ParsedPrivate {
        val parser = PEMParser(InputStreamReader(text.byteInputStream(Charsets.UTF_8)))
        val obj = parser.readObject()
            ?: throw IllegalArgumentException("PEM body is empty — not a private key.")

        val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)

        val javaPriv = when (obj) {
            is PEMEncryptedKeyPair -> {
                val pass = passphrase
                    ?: throw SecurityException("Key is passphrase-protected; please provide one.")
                val decProv = JcePEMDecryptorProviderBuilder().build(pass.toCharArray())
                converter.getKeyPair(obj.decryptKeyPair(decProv)).private
            }
            is PEMKeyPair -> converter.getKeyPair(obj).private
            is PrivateKeyInfo -> converter.getPrivateKey(obj)
            is PKCS8EncryptedPrivateKeyInfo -> {
                val pass = passphrase
                    ?: throw SecurityException("Key is passphrase-protected; please provide one.")
                val decryptor = JceOpenSSLPKCS8DecryptorProviderBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(pass.toCharArray())
                val pki = obj.decryptPrivateKeyInfo(decryptor)
                converter.getPrivateKey(pki)
            }
            else -> throw IllegalArgumentException(
                "Unsupported PEM object: ${obj.javaClass.simpleName}",
            )
        }

        return when (javaPriv) {
            is RSAPrivateCrtKey -> {
                // Derive the RSA public half from the CRT modulus + public
                // exponent — no separate public-key material needed.
                val keyFactory = KeyFactory.getInstance(
                    "RSA",
                    BouncyCastleProvider.PROVIDER_NAME,
                )
                val pub = keyFactory.generatePublic(
                    RSAPublicKeySpec(javaPriv.modulus, javaPriv.publicExponent),
                ) as RSAPublicKey

                val publicLine = buildRsaOpenSshPublicKey(
                    modulus = pub.modulus.toByteArray(),
                    publicExponent = pub.publicExponent.toByteArray(),
                    comment = "",
                )
                // Re-armor as PKCS#1 so the vault holds a predictable format
                // even when the user's source was PKCS#8 or encrypted — sshj
                // accepts PKCS#1 RSA natively.
                val pkcs1 = rsaPrivateToPkcs1Der(javaPriv)
                ParsedPrivate(
                    algorithm = KeyAlgorithm.RSA_4096,
                    privatePem = pemArmor("RSA PRIVATE KEY", pkcs1),
                    publicOpenSsh = publicLine,
                )
            }
            else -> throw IllegalArgumentException(
                "Unsupported algorithm: ${javaPriv.algorithm}. termx accepts Ed25519 and RSA.",
            )
        }
    }

    // --- shared helpers ------------------------------------------------------

    private fun rsaPrivateToPkcs1Der(priv: RSAPrivateCrtKey): ByteArray {
        val r = RSAPrivateKey(
            priv.modulus,
            priv.publicExponent,
            priv.privateExponent,
            priv.primeP,
            priv.primeQ,
            priv.primeExponentP,
            priv.primeExponentQ,
            priv.crtCoefficient,
        )
        return r.encoded
    }

    @Suppress("SameParameterValue")
    private fun stripPemArmor(text: String, type: String): ByteArray {
        val begin = "-----BEGIN $type-----"
        val end = "-----END $type-----"
        val startIdx = text.indexOf(begin)
        val endIdx = text.indexOf(end)
        require(startIdx >= 0 && endIdx > startIdx) { "PEM blob missing $begin/$end" }
        val body = text.substring(startIdx + begin.length, endIdx)
            .replace("\r", "")
            .replace("\n", "")
            .trim()
        return Base64.getDecoder().decode(body)
    }

    private fun pemArmor(type: String, body: ByteArray): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(body)
        val wrapped = buildString {
            for (i in b64.indices step PEM_LINE_WIDTH) {
                val end = minOf(i + PEM_LINE_WIDTH, b64.length)
                append(b64, i, end)
                append('\n')
            }
        }
        val pem = buildString {
            append("-----BEGIN ").append(type).append("-----\n")
            append(wrapped)
            append("-----END ").append(type).append("-----\n")
        }
        return pem.toByteArray(Charsets.US_ASCII)
    }

    private fun buildEd25519OpenSshPublicKey(publicKeyBytes: ByteArray, comment: String): String {
        val out = ByteArrayOutputStream()
        writeSshString(out, ED25519_KEY_TYPE.toByteArray(Charsets.US_ASCII))
        writeSshString(out, publicKeyBytes)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return if (comment.isBlank()) "$ED25519_KEY_TYPE $b64" else "$ED25519_KEY_TYPE $b64 $comment"
    }

    private fun buildRsaOpenSshPublicKey(
        modulus: ByteArray,
        publicExponent: ByteArray,
        comment: String,
    ): String {
        val out = ByteArrayOutputStream()
        writeSshString(out, RSA_KEY_TYPE.toByteArray(Charsets.US_ASCII))
        writeSshString(out, publicExponent)
        writeSshString(out, modulus)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return if (comment.isBlank()) "$RSA_KEY_TYPE $b64" else "$RSA_KEY_TYPE $b64 $comment"
    }

    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    // Silences unused BigInteger import when users don't need it.
    @Suppress("unused") private val bigIntegerPing: BigInteger = BigInteger.ONE

    private const val OPENSSH_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val OPENSSH_KEY_TYPE = "OPENSSH PRIVATE KEY"
    private const val ED25519_KEY_TYPE = "ssh-ed25519"
    private const val RSA_KEY_TYPE = "ssh-rsa"
    private const val PEM_LINE_WIDTH = 64
}
