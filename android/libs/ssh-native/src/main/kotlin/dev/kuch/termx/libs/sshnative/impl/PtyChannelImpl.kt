package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.PtyChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * sshj-backed [PtyChannel].
 *
 * [output] is a [channelFlow] that reads the shell's InputStream on
 * Dispatchers.IO and emits non-empty byte chunks as they arrive. The Flow
 * terminates cleanly when the remote end closes, or when the collector
 * cancels (which also tears down the channel).
 */
internal class PtyChannelImpl(
    private val session: Session,
    private val shell: Session.Shell,
) : PtyChannel {

    @Volatile private var closed = false

    override val output: Flow<ByteArray> = channelFlow {
        val input = shell.inputStream
        val buffer = ByteArray(8 * 1024)
        try {
            while (!closed) {
                val n = runCatching { input.read(buffer) }.getOrElse { -1 }
                if (n <= 0) break
                trySend(buffer.copyOf(n))
            }
        } finally {
            awaitClose {
                // Collector scope cancelled → close the underlying shell so the
                // session can reclaim the channel slot.
                runCatching { close() }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(bytes)
            shell.outputStream.flush()
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        try {
            shell.changeWindowDimensions(cols, rows, 0, 0)
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}
