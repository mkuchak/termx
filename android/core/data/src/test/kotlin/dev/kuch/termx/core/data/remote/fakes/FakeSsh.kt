package dev.kuch.termx.core.data.remote.fakes

import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SftpClient
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal in-process stand-ins for `:libs:ssh-native` surfaces. None of
 * these touch sshj — they exist purely so the install-flow use-case can
 * be driven deterministically from a JVM unit test.
 *
 * Hand-rolled rather than MockK because the contracts are small and the
 * intent is to outlive mock-library churn.
 */

class FakeExecChannel(
    stdout: String = "",
    stderr: String = "",
    exitCodeValue: Int = 0,
) : ExecChannel {
    override val stdout: Flow<ByteArray> =
        if (stdout.isEmpty()) emptyFlow() else flowOf(stdout.toByteArray(Charsets.UTF_8))
    override val stderr: Flow<ByteArray> =
        if (stderr.isEmpty()) emptyFlow() else flowOf(stderr.toByteArray(Charsets.UTF_8))
    override val exitCode: Deferred<Int> = CompletableDeferred(exitCodeValue)
    val closed = AtomicInteger(0)

    override suspend fun write(bytes: ByteArray) { /* no-op */ }

    override fun close() {
        closed.incrementAndGet()
    }
}

/**
 * Programmable stub session. Populate [execResponses] keyed by any
 * substring of the command the use-case will run; [openExec] picks the
 * first entry whose key appears in the incoming command string. Keeps
 * tests short — a 4-line shell snippet becomes `"command -v termx"`.
 */
class FakeSshSession(
    private val execResponses: Map<String, FakeExecChannel> = emptyMap(),
    private val sftpExistence: Map<String, Boolean> = emptyMap(),
) : SshSession {
    val execHistory = mutableListOf<String>()
    val closed = AtomicInteger(0)

    override suspend fun openShell(
        term: String,
        cols: Int,
        rows: Int,
        command: String?,
    ): PtyChannel = error("FakeSshSession does not support PTY channels")

    override suspend fun openExec(command: String): ExecChannel {
        execHistory += command
        val match = execResponses.entries.firstOrNull { command.contains(it.key) }
        return match?.value ?: FakeExecChannel(exitCodeValue = 0)
    }

    override suspend fun openSftp(): SftpClient = FakeSftpClient(sftpExistence)

    override fun close() {
        closed.incrementAndGet()
    }

    override suspend fun closeAsync() {
        close()
    }
}

class FakeSftpClient(private val existence: Map<String, Boolean>) : SftpClient {
    override suspend fun read(remotePath: String): ByteArray = ByteArray(0)
    override suspend fun write(remotePath: String, bytes: ByteArray) { /* no-op */ }
    override suspend fun list(remoteDir: String): List<String> = emptyList()
    override suspend fun exists(remotePath: String): Boolean =
        existence.entries.firstOrNull { remotePath.contains(it.key) }?.value ?: true
    override suspend fun rename(src: String, dst: String) { /* no-op */ }
    override fun close() { /* no-op */ }
}

/**
 * Subclass of [SshClient] that captures the [SshAuth] handed to `connect`
 * and short-circuits the real sshj transport. Set [sessionProvider] to
 * throw when you want to simulate a failed handshake.
 */
class FakeSshClient(
    private val sessionProvider: () -> SshSession = { FakeSshSession() },
) : SshClient() {
    var capturedAuth: SshAuth? = null
        private set
    var capturedTarget: SshTarget? = null
        private set
    val connectCount = AtomicInteger(0)

    override suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        timeoutMillis: Long,
    ): SshSession {
        capturedTarget = target
        capturedAuth = auth
        connectCount.incrementAndGet()
        return sessionProvider()
    }
}
