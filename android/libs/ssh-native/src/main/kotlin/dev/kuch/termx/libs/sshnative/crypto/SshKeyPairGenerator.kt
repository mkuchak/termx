package dev.kuch.termx.libs.sshnative.crypto

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

/**
 * Generates fresh SSH key material in OpenSSH-compatible serialisations.
 *
 * The public half lands as a classic `ssh-<alg> AAAA... comment` one-liner
 * (the exact string you'd paste into `~/.ssh/authorized_keys`). The private
 * half is PEM-armored:
 *   - Ed25519: the OpenSSH binary payload wrapped in
 *     `-----BEGIN OPENSSH PRIVATE KEY-----` via [OpenSSHPrivateKeyUtil].
 *   - RSA-4096: classic PKCS#1 `-----BEGIN RSA PRIVATE KEY-----` — sshj and
 *     OpenSSH itself both accept this form.
 *
 * No key ever leaves this object in raw byte-array form except as
 * [GeneratedKey.privatePem], which the caller must pass directly to the
 * [dev.kuch.termx.core.data.vault.SecretVault]; clear it from memory after.
 */
object SshKeyPairGenerator {

    /** Output of [ed25519] / [rsa4096]. */
    data class GeneratedKey(
        val privatePem: ByteArray,
        val publicOpenSsh: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedKey) return false
            if (!privatePem.contentEquals(other.privatePem)) return false
            if (publicOpenSsh != other.publicOpenSsh) return false
            return true
        }

        override fun hashCode(): Int {
            var result = privatePem.contentHashCode()
            result = 31 * result + publicOpenSsh.hashCode()
            return result
        }
    }

    init {
        // Ensure BouncyCastle is registered so JCA (used for RSA) can find
        // the BC provider. Registering twice is a no-op.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /**
     * Generate an Ed25519 key pair. Default [comment] is empty; when set it
     * is appended to the OpenSSH public-key line.
     */
    fun ed25519(comment: String = ""): GeneratedKey {
        val generator = Ed25519KeyPairGenerator().apply {
            init(Ed25519KeyGenerationParameters(SecureRandom()))
        }
        val keyPair = generator.generateKeyPair()
        val priv = keyPair.private as Ed25519PrivateKeyParameters
        val pub = keyPair.public as Ed25519PublicKeyParameters

        val privateBinary = OpenSSHPrivateKeyUtil.encodePrivateKey(priv)
        val privatePem = pemArmor("OPENSSH PRIVATE KEY", privateBinary)

        val publicOpenSsh = buildEd25519OpenSshPublicKey(pub.encoded, comment)

        return GeneratedKey(privatePem = privatePem, publicOpenSsh = publicOpenSsh)
    }

    /**
     * Generate an RSA 4096-bit key pair using the public exponent F4 (65537).
     */
    fun rsa4096(comment: String = ""): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(RSAKeyGenParameterSpec(4096, RSAKeyGenParameterSpec.F4), SecureRandom())
        val kp = kpg.generateKeyPair()
        val priv = kp.private as RSAPrivateCrtKey
        val pub = kp.public as RSAPublicKey

        val privatePkcs1Der = rsaPrivateToPkcs1(priv)
        val privatePem = pemArmor("RSA PRIVATE KEY", privatePkcs1Der)

        val publicOpenSsh = buildRsaOpenSshPublicKey(
            modulus = pub.modulus.toByteArray(),
            publicExponent = pub.publicExponent.toByteArray(),
            comment = comment,
        )

        return GeneratedKey(privatePem = privatePem, publicOpenSsh = publicOpenSsh)
    }

    // --- internals ----------------------------------------------------------

    /**
     * Build the one-line `ssh-ed25519 BASE64[ comment]` wire form.
     *
     * Body is `string("ssh-ed25519") || string(raw public key bytes)`,
     * base64-encoded.
     */
    private fun buildEd25519OpenSshPublicKey(publicKeyBytes: ByteArray, comment: String): String {
        val out = ByteArrayOutputStream()
        writeSshString(out, ED25519_KEY_TYPE.toByteArray(Charsets.US_ASCII))
        writeSshString(out, publicKeyBytes)
        val b64 = Base64.getEncoder().encodeToString(out.toByteArray())
        return if (comment.isBlank()) "$ED25519_KEY_TYPE $b64" else "$ED25519_KEY_TYPE $b64 $comment"
    }

    /**
     * Build the one-line `ssh-rsa BASE64[ comment]` wire form.
     *
     * Body is `string("ssh-rsa") || mpint(e) || mpint(n)`, base64-encoded.
     * [modulus] and [publicExponent] are the raw `BigInteger.toByteArray()`
     * outputs (already in big-endian two's-complement form — exactly what
     * the SSH mpint codec wants).
     */
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

    /**
     * SSH wire format string: 4-byte big-endian length followed by raw bytes.
     */
    private fun writeSshString(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        out.write((len ushr 24) and 0xFF)
        out.write((len ushr 16) and 0xFF)
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(bytes)
    }

    /**
     * Convert a JCA [RSAPrivateCrtKey] into the PKCS#1 DER body accepted by
     * `-----BEGIN RSA PRIVATE KEY-----`. The JCA key's `encoded` attribute
     * returns PKCS#8; the PKCS#1 body is the nested `privateKey` OCTET
     * STRING. We use BouncyCastle's ASN.1 surface via the pkix module.
     */
    private fun rsaPrivateToPkcs1(priv: RSAPrivateCrtKey): ByteArray {
        // Build the PKCS#1 RSAPrivateKey ASN.1 structure directly from the
        // JCA key's CRT parameters — the `RSAPrivateKey` BC type encodes to
        // the exact byte layout that `-----BEGIN RSA PRIVATE KEY-----` wraps.
        val rsa = org.bouncycastle.asn1.pkcs.RSAPrivateKey(
            priv.modulus,
            priv.publicExponent,
            priv.privateExponent,
            priv.primeP,
            priv.primeQ,
            priv.primeExponentP,
            priv.primeExponentQ,
            priv.crtCoefficient,
        )
        return rsa.encoded
    }

    /**
     * Wrap [body] in classic RFC 7468 PEM armor with [type] as the header.
     * Lines are broken at 64 characters per the PEM convention.
     */
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

    private const val ED25519_KEY_TYPE = "ssh-ed25519"
    private const val RSA_KEY_TYPE = "ssh-rsa"
    private const val PEM_LINE_WIDTH = 64
}
