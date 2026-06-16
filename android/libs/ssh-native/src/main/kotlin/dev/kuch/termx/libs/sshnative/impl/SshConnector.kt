package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStreamReader

/**
 * The sshj-touching half of `SshClient.connect`, extracted so the public
 * surface (`SshClient.kt`) stays free of `net.schmizz` imports.
 */
internal object SshConnector {
    /**
     * SSH-level keepalive interval in seconds. A phone that backgrounds
     * the app (or switches Wi-Fi → cellular) can be left with a half-open
     * TCP socket — the next write succeeds locally but never reaches the
     * server, so input vanishes and the terminal looks frozen. Issue 2B,
     * v1.1.13.
     *
     * CRITICAL — the PROVIDER matters as much as the interval. sshj's
     * [DefaultConfig] defaults to [KeepAliveProvider.HEARTBEAT], a
     * `Heartbeater` that fires `SSH_MSG_IGNORE` packets fire-and-forget:
     * it warms the NAT pinhole but tracks no replies and NEVER tears a
     * dead session down, so a half-open link is never detected. That was
     * the v1.7.4 "frozen after long background" bug — the keepalive was
     * the intended mitigation and silently did nothing. We force
     * [KeepAliveProvider.KEEP_ALIVE] (a [KeepAliveRunner]) instead: it
     * sends `keepalive@openssh.com` global requests that EXPECT a reply
     * and, after [KEEPALIVE_MAX_COUNT] unanswered probes, throws
     * `ConnectionException(CONNECTION_LOST)`. That propagates as a channel
     * close → the read loop's EOF → `ConnectionManager.onShellFinished` →
     * Disconnected, so the app can surface it / auto-reconnect.
     *
     * NOTE this thread only runs while the app has CPU; deep Doze freezes
     * it, so a socket that dies while the screen is off is caught not here
     * but by the on-resume liveness probe (`ConnectionManager`). Detection
     * window when foregrounded: ~interval × maxAliveCount = 30s × 4 ≈ 120s.
     */
    private const val KEEPALIVE_INTERVAL_SECONDS = 30

    /**
     * Unanswered keepalive probes tolerated before the
     * [KeepAliveProvider.KEEP_ALIVE] runner declares the link dead and
     * throws `CONNECTION_LOST`. 4 × 30s ≈ 120s to detect a silently
     * dropped link without false-positiving on a brief network stall.
     */
    private const val KEEPALIVE_MAX_COUNT = 4

    fun open(target: SshTarget, auth: SshAuth, timeoutMillis: Long): SshSession {
        // Detecting keepalive provider — see KEEPALIVE_INTERVAL_SECONDS.
        // The default HEARTBEAT provider cannot detect a dead peer.
        val config = DefaultConfig().apply {
            keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        }
        val client = SSHClient(config)
        try {
            client.connectTimeout = timeoutMillis.toInt()
            client.timeout = timeoutMillis.toInt()

            // TODO(Phase 2 — Task #21): replace PromiscuousVerifier with a
            // proper known_hosts-backed verifier and auto-accept-on-first-use.
            ensureKnownHostsFile(target.knownHostsPath)
            client.addHostKeyVerifier(PromiscuousVerifier())

            client.connect(target.host, target.port)
            // The keepAlive instance is created by the provider at connect
            // time, so configure it now (not before connect()).
            client.connection.keepAlive.keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
            (client.connection.keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEPALIVE_MAX_COUNT

            when (auth) {
                is SshAuth.Password -> client.authPassword(target.username, auth.value)
                is SshAuth.PublicKey -> {
                    val provider: KeyProvider = loadKeyProvider(client, auth)
                    client.authPublickey(target.username, provider)
                }
            }

            return SshSessionImpl(client)
        } catch (t: Throwable) {
            runCatching { client.disconnect() }
            throw t.toSshException()
        }
    }

    private fun loadKeyProvider(client: SSHClient, auth: SshAuth.PublicKey): KeyProvider {
        val pem = InputStreamReader(ByteArrayInputStream(auth.privateKeyPem)).readText()
        return if (auth.passphrase != null) {
            client.loadKeys(pem, null, PasswordUtils.createOneOff(auth.passphrase.toCharArray()))
        } else {
            client.loadKeys(pem, null, null)
        }
    }

    private fun ensureKnownHostsFile(path: String) {
        val f = File(path)
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (!f.exists()) f.createNewFile()
    }
}
