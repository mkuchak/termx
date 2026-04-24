package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.Flow

/**
 * A live mosh session backed by a local mosh-client child process.
 *
 * Mirrors the shape of [PtyChannel]: a cold-ish byte [output] flow, a
 * [write] sink for stdin, and a [resize] call that forwards a SIGWINCH
 * to the local mosh-client PID (mosh-client picks up the new dimensions
 * and propagates them across the UDP link to mosh-server).
 *
 * Unlike [SshSession], a mosh session is single-channel by design — one
 * process, one PTY, one UDP tuple. Tabs that want a second remote shell
 * either piggy-back on tmux inside the same mosh session or open a
 * fresh [MoshSession] from a fresh [MoshClient.tryConnect].
 */
interface MoshSession : AutoCloseable {
    /** Cold Flow of raw bytes emitted by the local mosh-client stdout. */
    val output: Flow<ByteArray>

    /** Forward keypresses / paste bytes into the mosh-client stdin. */
    suspend fun write(bytes: ByteArray)

    /** Send SIGWINCH to the mosh-client PID so it refreshes its terminal size. */
    suspend fun resize(cols: Int, rows: Int)

    override fun close()
}
