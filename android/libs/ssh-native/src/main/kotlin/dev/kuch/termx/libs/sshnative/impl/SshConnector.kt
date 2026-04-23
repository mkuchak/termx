package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
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
    fun open(target: SshTarget, auth: SshAuth, timeoutMillis: Long): SshSession {
        val client = SSHClient(DefaultConfig())
        try {
            client.connectTimeout = timeoutMillis.toInt()
            client.timeout = timeoutMillis.toInt()

            // TODO(Phase 2 — Task #21): replace PromiscuousVerifier with a
            // proper known_hosts-backed verifier and auto-accept-on-first-use.
            ensureKnownHostsFile(target.knownHostsPath)
            client.addHostKeyVerifier(PromiscuousVerifier())

            client.connect(target.host, target.port)

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
