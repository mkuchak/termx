package dev.kuch.termx.libs.sshnative

/**
 * Credentials used to authenticate against an SSH endpoint.
 *
 * Kept as a sealed interface so call sites can `when`-exhaust over every
 * supported auth flavour. Additional variants (e.g. `Agent`, `Keyboard`)
 * land in later phases.
 */
sealed interface SshAuth {
    /** Plain password authentication. */
    data class Password(val value: String) : SshAuth

    /**
     * OpenSSH-compatible private key in PEM form (PKCS#8, PKCS#1, or
     * OpenSSH format — sshj auto-detects).
     *
     * Uses content-based equality to avoid the `ByteArray` identity trap.
     */
    class PublicKey(
        val privateKeyPem: ByteArray,
        val passphrase: String?,
    ) : SshAuth {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PublicKey) return false
            if (!privateKeyPem.contentEquals(other.privateKeyPem)) return false
            if (passphrase != other.passphrase) return false
            return true
        }

        override fun hashCode(): Int {
            var result = privateKeyPem.contentHashCode()
            result = 31 * result + (passphrase?.hashCode() ?: 0)
            return result
        }
    }
}
