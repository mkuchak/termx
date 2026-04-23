package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.ExecChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream

/**
 * sshj-backed [ExecChannel]. Bridges the three sshj InputStreams (stdout,
 * stderr, exit status) into coroutine-native primitives.
 *
 * The exit code is resolved in a background coroutine that joins the
 * command; if joining throws, the `exitCode` Deferred completes
 * exceptionally so awaiters see the underlying failure.
 */
internal class ExecChannelImpl(
    private val session: Session,
    private val cmd: Session.Command,
) : ExecChannel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exit = CompletableDeferred<Int>()
    @Volatile private var closed = false

    init {
        scope.launch {
            try {
                cmd.join()
                exit.complete(cmd.exitStatus ?: -1)
            } catch (t: Throwable) {
                exit.completeExceptionally(t.toSshException())
            }
        }
    }

    override val stdout: Flow<ByteArray> = streamFlow(cmd.inputStream)
    override val stderr: Flow<ByteArray> = streamFlow(cmd.errorStream)

    override val exitCode: Deferred<Int> get() = exit

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            cmd.outputStream.write(bytes)
            cmd.outputStream.flush()
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { cmd.close() }
        runCatching { session.close() }
        if (!exit.isCompleted) exit.cancel(Job("ExecChannel closed"))
        scope.cancel()
    }

    private fun streamFlow(stream: InputStream): Flow<ByteArray> = channelFlow {
        val buf = ByteArray(8 * 1024)
        try {
            while (!closed) {
                val n = runCatching { stream.read(buf) }.getOrElse { -1 }
                if (n <= 0) break
                trySend(buf.copyOf(n))
            }
        } finally {
            awaitClose { /* upstream close handled via [close] */ }
        }
    }.flowOn(Dispatchers.IO)
}
