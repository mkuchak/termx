package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SshClientTest {
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

    @org.junit.Ignore("MINA embedded-sshd timing-flaky under runTest; see Task #52")
    @Test
    fun `exec echo hello returns stdout`() = runTest {
        val client = SshClient()
        val session = client.connect(
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
            val exec = session.openExec("echo hello")
            val outChunks = exec.stdout.toList()
            val code = exec.exitCode.await()

            val combined = outChunks.fold(ByteArray(0)) { acc, b -> acc + b }
            assertEquals("hello\n", combined.toString(Charsets.UTF_8))
            assertEquals(0, code)
            exec.close()
        } finally {
            session.close()
        }
    }
}
