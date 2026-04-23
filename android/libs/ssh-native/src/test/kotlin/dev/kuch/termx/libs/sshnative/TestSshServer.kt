package dev.kuch.termx.libs.sshnative

import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import java.io.File
import java.nio.file.Files

/**
 * Thin wrapper around Apache MINA sshd for unit tests — boots on a random
 * port, accepts one password, echoes commands via the host's `/bin/sh`
 * (when available) or a trivial in-process command otherwise.
 */
internal class TestSshServer(
    private val username: String = "tester",
    private val password: String = "secret",
) {
    private var sshd: SshServer? = null
    private var hostKeyFile: File? = null

    val port: Int get() = sshd?.port ?: error("Server not started")

    fun start() {
        val server = SshServer.setUpDefaultServer()
        val hostKey = Files.createTempFile("testhost", ".ser").toFile().also { it.deleteOnExit() }
        hostKeyFile = hostKey
        server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath()) as KeyPairProvider
        server.port = 0
        server.passwordAuthenticator = PasswordAuthenticator { u, p, _ ->
            u == username && p == password
        }
        server.shellFactory = InteractiveProcessShellFactory()
        server.commandFactory = CommandFactory { _, command ->
            ProcessShellWrapper(command)
        }
        server.start()
        sshd = server
    }

    fun stop() {
        runCatching { sshd?.stop(true) }
        hostKeyFile?.delete()
    }
}
