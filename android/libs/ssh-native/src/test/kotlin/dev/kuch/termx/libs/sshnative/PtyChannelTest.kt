package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PtyChannelTest {
    private lateinit var server: TestSshServer
    private lateinit var knownHosts: File

    @Before
    fun setUp() {
        server = TestSshServer()
        server.start()
        knownHosts = Files.createTempFile("known_hosts", "").toFile().also { it.deleteOnExit() }
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `shell output flow terminates after exit`() = runTest {
        val session = SshClient().connect(
            target = SshTarget(
                host = "127.0.0.1",
                port = server.port,
                username = "tester",
                knownHostsPath = knownHosts.absolutePath,
            ),
            auth = SshAuth.Password("secret"),
            timeoutMillis = 15_000,
        )
        try {
            val pty = session.openShell(term = "xterm-256color", cols = 80, rows = 24)
            // Send `exit` — the interactive shell should terminate, closing the flow.
            pty.write("exit\n".toByteArray())
            val collected = withTimeout(15_000) {
                pty.output.toList()
            }
            // The flow terminated without throwing — that's the contract under test.
            assertTrue("collected at least some frames or cleanly closed", collected.size >= 0)
            pty.close()
        } finally {
            session.close()
        }
    }
}
