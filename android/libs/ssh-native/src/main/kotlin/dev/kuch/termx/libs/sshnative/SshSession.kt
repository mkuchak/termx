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
     */
    suspend fun openShell(
        term: String = "xterm-256color",
        cols: Int,
        rows: Int,
    ): PtyChannel

    /** Run a single command; returns an [ExecChannel] exposing stdout/stderr/exit. */
    suspend fun openExec(command: String): ExecChannel

    /** Open an SFTP subsystem. */
    suspend fun openSftp(): SftpClient

    /** Non-suspending close for `use { }`-style. Always idempotent. */
    override fun close()

    /** Suspending alias of [close] — prefer this from coroutines. */
    suspend fun closeAsync()
}
