package dev.kuch.termx.libs.sshnative

/**
 * A live, authenticated SSH session. Owns one underlying TCP/SSH transport;
 * multiplex multiple channels over it.
 *
 * Closing the session closes every channel opened from it, which terminates
 * each channel's [kotlinx.coroutines.flow.Flow] producer.
 */
interface SshSession : AutoCloseable {
    /**
     * Open an interactive shell backed by a PTY of the given geometry.
     *
     * @param term `$TERM` advertised to the server
     * @param cols initial column count
     * @param rows initial row count
     * @param command optional command to exec in place of a plain login
     *   shell. When non-null, the PTY is allocated and then
     *   `session.exec(command)` runs it — the returned [PtyChannel]
     *   behaves identically (bytes, writes, resize) except the remote
     *   process tree is rooted at [command] instead of the user's login
     *   shell.
     */
    suspend fun openShell(
        term: String = "xterm-256color",
        cols: Int,
        rows: Int,
        command: String? = null,
    ): PtyChannel

    /** Run a single command; returns an [ExecChannel] exposing stdout/stderr/exit. */
    suspend fun openExec(command: String): ExecChannel

    /** Open an SFTP subsystem. */
    suspend fun openSftp(): SftpClient

    /** Non-suspending close for `use { }`-style. Always idempotent. */
    override fun close()

    /** Suspending alias of [close] — prefer this from coroutines. */
    suspend fun closeAsync()

    /**
     * Passive: is the underlying SSH transport still connected? Cheap, no
     * round-trip. After a clean channel close (the remote shell ran
     * `exit`) the transport stays up → `true`; after a transport death
     * (keepalive `CONNECTION_LOST`, TCP RST) it is down → `false`. Lets a
     * caller tell a deliberate logout from an involuntary drop. Defaults
     * to `true` so fakes that don't model it read as alive.
     */
    fun isTransportAlive(): Boolean = true

    /**
     * Active liveness probe: a bounded round-trip to the server. `true` =
     * a reply came back within [timeoutMs]; `false` = it timed out or the
     * transport is dead. Used on app resume to catch a socket that died
     * silently while backgrounded (the keepalive thread is frozen during
     * Doze and produces no EOF until poked). Defaults to `true` so fakes
     * that don't model it read as alive.
     */
    suspend fun probe(timeoutMs: Long = 5_000L): Boolean = true
}
