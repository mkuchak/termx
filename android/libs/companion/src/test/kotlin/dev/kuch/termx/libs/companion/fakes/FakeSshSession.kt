package dev.kuch.termx.libs.companion.fakes

import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SftpClient
import dev.kuch.termx.libs.sshnative.SshSession
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Minimal fake implementations of the `:libs:ssh-native` surfaces used by
 * [dev.kuch.termx.libs.companion.EventStreamClient].
 *
 * The fakes are intentionally dumb: tests wire them up with canned
 * responses indexed by command string / path. That's enough for the
 * narrow behaviors we verify (stdout framing, reconnect, SFTP writes)
 * and keeps the surface area small.
 */

class FakeExecChannel(
    override val stdout: Flow<ByteArray> = emptyFlow(),
    override val stderr: Flow<ByteArray> = emptyFlow(),
    exitCode: Int = 0,
) : ExecChannel {
    override val exitCode: Deferred<Int> = CompletableDeferred(exitCode)
    val closed = AtomicInteger(0)
    val written = mutableListOf<ByteArray>()

    override suspend fun write(bytes: ByteArray) {
        written.add(bytes)
    }

    override fun close() {
        closed.incrementAndGet()
    }
}

class FakeSftpClient : SftpClient {
    val writes = ConcurrentHashMap<String, ByteArray>()
    val reads = ConcurrentHashMap<String, ByteArray>()
    val listings = ConcurrentHashMap<String, List<String>>()
    val existence = ConcurrentHashMap<String, Boolean>()
    val renames = mutableListOf<Pair<String, String>>()
    val closed = AtomicInteger(0)

    override suspend fun read(remotePath: String): ByteArray =
        reads[remotePath] ?: error("no canned read for $remotePath")

    override suspend fun write(remotePath: String, bytes: ByteArray) {
        writes[remotePath] = bytes
    }

    override suspend fun list(remoteDir: String): List<String> =
        listings[remoteDir] ?: error("no canned listing for $remoteDir")

    override suspend fun exists(remotePath: String): Boolean =
        existence[remotePath] ?: reads.containsKey(remotePath)

    override suspend fun rename(src: String, dst: String) {
        synchronized(renames) { renames.add(src to dst) }
        // Simulate an atomic rename: the bytes that were present at `src`
        // become readable at `dst`, and `src` ceases to exist.
        val bytes = writes.remove(src) ?: error("rename: no payload at $src")
        writes[dst] = bytes
    }

    override fun close() {
        closed.incrementAndGet()
    }
}

/**
 * Queues exec responses so successive `openExec` calls with the same
 * command consume one response each. Useful for simulating "first tail
 * fails, second succeeds" reconnect scenarios.
 */
class FakeSshSession : SshSession {
    private val execQueues = ConcurrentHashMap<String, ArrayDeque<() -> ExecChannel>>()
    private var sftpFactory: () -> SftpClient = { FakeSftpClient() }
    val execCallCounts = ConcurrentHashMap<String, AtomicInteger>()
    val closed = AtomicInteger(0)

    fun queueExec(command: String, response: () -> ExecChannel) {
        execQueues.getOrPut(command) { ArrayDeque() }.addLast(response)
    }

    fun setSftpFactory(factory: () -> SftpClient) {
        sftpFactory = factory
    }

    override suspend fun openShell(
        term: String,
        cols: Int,
        rows: Int,
        command: String?,
    ): PtyChannel = error("FakeSshSession does not support PTY channels")

    override suspend fun openExec(command: String): ExecChannel {
        execCallCounts.getOrPut(command) { AtomicInteger(0) }.incrementAndGet()
        val queue = execQueues[command]
            ?: error("no canned exec response for `$command`")
        val provider = if (queue.size > 1) queue.removeFirst() else queue.first()
        return provider()
    }

    override suspend fun openSftp(): SftpClient = sftpFactory()

    override fun close() {
        closed.incrementAndGet()
    }

    override suspend fun closeAsync() {
        close()
    }
}

/** Helper for building a single-shot stdout flow of preformatted bytes. */
fun bytesFlowOf(vararg chunks: String): Flow<ByteArray> =
    flowOf(*chunks.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray())
