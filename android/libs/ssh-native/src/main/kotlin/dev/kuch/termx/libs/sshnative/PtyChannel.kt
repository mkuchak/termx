package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.Flow

/**
 * An interactive shell channel with an allocated PTY.
 *
 * [output] is a cold Flow: each collector gets bytes from the underlying
 * shell as they arrive. The Flow completes when the channel closes (either
 * locally via [close] / session-close, or remotely when the shell exits).
 */
interface PtyChannel : AutoCloseable {
    /** Cold Flow of raw bytes from the shell's stdout+stderr (merged by the PTY). */
    val output: Flow<ByteArray>

    /** Forward key presses / paste buffer bytes into the shell's stdin. */
    suspend fun write(bytes: ByteArray)

    /** Send a window-size change (SIGWINCH) to the PTY. */
    suspend fun resize(cols: Int, rows: Int)

    override fun close()
}
