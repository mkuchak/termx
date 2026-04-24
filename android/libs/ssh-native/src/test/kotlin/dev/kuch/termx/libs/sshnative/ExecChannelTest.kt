package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ExecChannelTest {
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
    fun `exec exits with exit code 7`() = runTest {
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
            val exec = session.openExec("exit 7")
            // Drain streams so the exit status is observable.
            exec.stdout.toList()
            exec.stderr.toList()
            val code = exec.exitCode.await()
            assertEquals(7, code)
            exec.close()
        } finally {
            session.close()
        }
    }
}
