package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.PtyChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.InputStream
import java.io.OutputStream

/**
 * sshj-backed [PtyChannel].
 *
 * Either [shell] or [command] is non-null — never both, never neither.
 * The two modes share behavior: bytes flow from the underlying
 * InputStream into the [output] Flow, writes fan into the underlying
 * OutputStream, and window-size changes hit sshj's
 * `changeWindowDimensions`. The [session] owns the PTY; closing either
 * mode closes the session and reclaims the channel slot.
 *
 * [output] is a [channelFlow] that reads on Dispatchers.IO and emits
 * non-empty byte chunks as they arrive. The Flow terminates cleanly
 * when the remote end closes, or when the collector cancels (which
 * also tears down the channel).
 */
internal class PtyChannelImpl(
    private val session: Session,
    private val shell: Session.Shell? = null,
    private val command: Session.Command? = null,
) : PtyChannel {

    init {
        require((shell == null) xor (command == null)) {
            "PtyChannelImpl requires exactly one of shell or command"
        }
    }

    private val input: InputStream = shell?.inputStream ?: command!!.inputStream
    private val outputStream: OutputStream = shell?.outputStream ?: command!!.outputStream

    @Volatile private var closed = false

    override val output: Flow<ByteArray> = channelFlow {
        val buffer = ByteArray(8 * 1024)
        while (!closed) {
            val n = runCatching { input.read(buffer) }.getOrElse { -1 }
            if (n <= 0) break
            // `send` (not `trySend`) so the producer suspends when the
            // collector falls behind. With trySend the default RENDEZVOUS
            // channel silently drops frames when the emulator coroutine
            // is slower than the shell — a tmux full-screen repaint
            // would lose cells and the UI would look stale forever. A
            // suspending producer is the correct back-pressure story for
            // a stream where "every byte matters".
            send(buffer.copyOf(n))
        }
        // Natural termination: EOF on the shell's stdout → producer returns →
        // channel auto-closes → collector completes. Downstream cancel is
        // covered by send throwing CancellationException. The underlying
        // shell is closed via [close], invoked from TerminalViewModel's
        // disposal path.
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            outputStream.write(bytes)
            outputStream.flush()
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun resize(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
        try {
            // sshj's `changeWindowDimensions` is declared on `Session.Shell`
            // rather than the base `Session`. The concrete `SessionChannel`
            // backing an exec-with-PTY also implements `Session.Shell` (the
            // PTY lives on the channel, not the subsystem), so we can safely
            // cast in both the startShell and exec cases.
            val windowSink = shell ?: (session as Session.Shell)
            windowSink.changeWindowDimensions(cols, rows, 0, 0)
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { shell?.close() }
        runCatching { command?.close() }
        runCatching { session.close() }
    }
}
