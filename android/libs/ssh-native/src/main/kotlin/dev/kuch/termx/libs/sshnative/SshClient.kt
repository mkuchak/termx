package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.SshConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Entry point to this module. Build one and call [connect] for each VPS.
 *
 * Android ships a stripped BouncyCastle pre-registered under the name `"BC"`
 * that does not expose modern primitives like X25519 or Ed25519. We remove
 * that stub and insert the full `bcprov-jdk18on` provider at position 1 so
 * sshj's curve25519-sha256 KEX, Ed25519 host keys, and RFC 7748 X25519 key
 * agreement all resolve. Safe to re-run: [Security.removeProvider] is a
 * no-op when the provider name is absent.
 */
class SshClient {
    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    /**
     * Connect + authenticate; returns a ready-to-use [SshSession].
     *
     * @throws SshException.HostUnreachable if TCP/transport fails
     * @throws SshException.AuthFailed if credentials are rejected
     * @throws SshException.HostKeyMismatch if known_hosts has a conflicting pin
     * @throws SshException.TimedOut if the combined connect+auth exceeds [timeoutMillis]
     * @throws SshException.Unknown for any other sshj failure
     */
    suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        timeoutMillis: Long = 10_000,
    ): SshSession = withContext(Dispatchers.IO) {
        SshConnector.open(target, auth, timeoutMillis)
    }
}
