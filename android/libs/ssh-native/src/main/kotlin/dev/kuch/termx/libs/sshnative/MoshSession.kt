package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

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

    /**
     * Latest diagnostic snapshot of the mosh-client child process.
     *
     * `exitCode` is `null` while the process is still running and
     * flips to the integer status once it terminates. `head` is the
     * first ~1 KB of merged stdout+stderr captured at the time the
     * process exited — intended for remote debugging without requiring
     * logcat access. While the process is live, `head` is an empty
     * string.
     *
     * UI code watches this to distinguish "mosh exited immediately
     * with an error message" from a normal tear-down and surfaces a
     * human-readable reason to the user.
     */
    val diagnostic: StateFlow<MoshDiagnostic>

    override fun close()
}

/**
 * Snapshot of the mosh-client child process lifecycle + a head of its
 * merged output, used to explain why a mosh session terminated without
 * ever producing terminal output.
 *
 * See `MoshSession.diagnostic`.
 */
data class MoshDiagnostic(
    val exitCode: Int?,
    val elapsedMs: Long,
    val head: String,
)
