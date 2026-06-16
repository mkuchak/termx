package dev.kuch.termx.feature.terminal.fakes

import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SftpClient
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Module-local SSH fakes for [dev.kuch.termx.feature.terminal.TerminalViewModel]
 * tests. Mirror the `:core:data` `FakeSsh` shape but add SFTP write/rename
 * recording so the mosh side-channel UnifiedPush-endpoint sync is
 * observable, and let a single [FakeSshClient] mint multiple distinct
 * [FakeSshSession]s (primary + side) across `connect` calls.
 *
 * Hand-rolled rather than MockK so the contract stays explicit and the
 * tests outlive mock-library churn — matches the convention in the rest
 * of the project's test tree.
 */

class FakeExecChannel(
    stdout: String = "",
    exitCodeValue: Int = 0,
) : ExecChannel {
    override val stdout: Flow<ByteArray> =
        if (stdout.isEmpty()) emptyFlow() else flowOf(stdout.toByteArray(Charsets.UTF_8))
    override val stderr: Flow<ByteArray> = emptyFlow()
    override val exitCode: Deferred<Int> = CompletableDeferred(exitCodeValue)

    override suspend fun write(bytes: ByteArray) { /* no-op */ }
    override fun close() { /* no-op */ }
}

/**
 * Records every exec command and every SFTP write/rename so tests can
 * assert what the mosh side channel did (mkdir + atomic endpoint write).
 *
 * `printf %s "$HOME"` resolves to `/home/test` so the endpoint path the
 * connection code builds is the deterministic
 * `/home/test/.termx/ntfy-endpoint`.
 *
 * `open` so ConnectionManager's cleanup-ordering test can interleave a
 * recording `close()` with a recording hub fake.
 */
open class FakeSshSession : SshSession {
    val execHistory = CopyOnWriteArrayList<String>()
    val sftpWrites = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    val sftpRenames = CopyOnWriteArrayList<Pair<String, String>>()
    val closed = AtomicInteger(0)

    /**
     * Liveness controls for the v1.7.4 auto-reconnect/resume-probe tests.
     * Default ALIVE so existing tests (clean-exit EOF, etc.) keep treating
     * a finished shell as a deliberate logout (no auto-reconnect). Flip
     * [transportAlive] to model a dropped transport at onShellFinished, or
     * [probeResult] to model a failed on-resume probe.
     */
    @Volatile var transportAlive: Boolean = true
    @Volatile var probeResult: Boolean = true
    val probeCount = AtomicInteger(0)

    override fun isTransportAlive(): Boolean = transportAlive

    override suspend fun probe(timeoutMs: Long): Boolean {
        probeCount.incrementAndGet()
        return probeResult
    }

    override suspend fun openShell(
        term: String,
        cols: Int,
        rows: Int,
        command: String?,
    ): PtyChannel = FakePtyChannel()

    override suspend fun openExec(command: String): ExecChannel {
        execHistory += command
        return if (command.contains("\$HOME")) {
            FakeExecChannel(stdout = "/home/test")
        } else {
            FakeExecChannel()
        }
    }

    override suspend fun openSftp(): SftpClient = FakeSftpClient(this)

    override fun close() {
        closed.incrementAndGet()
    }

    override suspend fun closeAsync() = close()
}

/**
 * Live-but-silent PTY for the plain-SSH path. `output` never emits and
 * never completes (a bare [MutableSharedFlow]) so the VM's output
 * collector parks instead of tripping the shell-finished teardown — the
 * session stays Connected for the duration of the test.
 */
class FakePtyChannel : PtyChannel {
    override val output: Flow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
    val closed = AtomicInteger(0)
    override suspend fun write(bytes: ByteArray) { /* no-op */ }
    override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
    override fun close() {
        closed.incrementAndGet()
    }
}

/** SFTP stub that forwards writes/renames back into the owning session. */
class FakeSftpClient(private val session: FakeSshSession) : SftpClient {
    override suspend fun read(remotePath: String): ByteArray = ByteArray(0)
    override suspend fun write(remotePath: String, bytes: ByteArray) {
        session.sftpWrites += remotePath to bytes
    }
    override suspend fun list(remoteDir: String): List<String> = emptyList()
    override suspend fun exists(remotePath: String): Boolean = true
    override suspend fun rename(src: String, dst: String) {
        session.sftpRenames += src to dst
    }
    override fun close() { /* no-op */ }
}

/**
 * [SshClient] override that hands out a fresh [FakeSshSession] per
 * `connect` (so the primary and the mosh side channel are distinguishable)
 * and records every session it minted. Set [failConnect] to simulate a
 * dead side-channel handshake — the production path must swallow it.
 *
 * Pass a [gate] to delay `connect` from returning until the test
 * completes the deferred — used to interpose a `disconnect()` between the
 * side-channel launch and its session landing (the torn-down-while-
 * connecting case).
 *
 * `open` + the [newSession] seam so tests can mint recording session
 * subclasses (e.g. the hub-unpublish-before-close ordering assertion)
 * without re-implementing the connect bookkeeping.
 */
open class FakeSshClient(
    var failConnect: Boolean = false,
    private val gate: Deferred<Unit>? = null,
) : SshClient() {
    val sessions = CopyOnWriteArrayList<FakeSshSession>()
    val connectCount = AtomicInteger(0)

    /** Override to substitute a recording/custom session per connect. */
    protected open fun newSession(): FakeSshSession = FakeSshSession()

    override suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        timeoutMillis: Long,
    ): SshSession {
        connectCount.incrementAndGet()
        gate?.await()
        if (failConnect) throw RuntimeException("simulated side-channel connect failure")
        val session = newSession()
        sessions += session
        return session
    }
}
