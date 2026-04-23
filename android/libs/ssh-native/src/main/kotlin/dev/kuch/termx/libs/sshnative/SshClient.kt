package dev.kuch.termx.libs.sshnative

import dev.kuch.termx.libs.sshnative.impl.SshConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Entry point to this module. Build one and call [connect] for each VPS.
 *
 * BouncyCastle is registered at provider position 1 on first instantiation
 * (no-op on subsequent calls) so Ed25519 keys and modern MAC/KEX algorithms
 * resolve against BC instead of the stock Android JCA provider.
 */
class SshClient {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
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
