package dev.kuch.termx.libs.sshnative

/**
 * Minimal SFTP surface. Phase 4+ (termxd companion) uses this for reading
 * `~/.termx/sessions/` JSON session files and tailing `events.ndjson` when a plain
 * exec tail is insufficient.
 */
interface SftpClient : AutoCloseable {
    /** Read the entire file at [remotePath] into memory. */
    suspend fun read(remotePath: String): ByteArray

    /** Overwrite (create if missing) [remotePath] with [bytes]. */
    suspend fun write(remotePath: String, bytes: ByteArray)

    /** Directory listing as a list of names (no `.` / `..`). */
    suspend fun list(remoteDir: String): List<String>

    /** `true` if anything (file, dir, symlink) exists at [remotePath]. */
    suspend fun exists(remotePath: String): Boolean

    /**
     * Rename [src] to [dst] atomically on the server. Used to publish a
     * fully-written temp file into its final location so readers never see
     * a half-written payload (see `EventStreamClient.sendCommand` for the
     * companion-commands write path).
     */
    suspend fun rename(src: String, dst: String)

    override fun close()
}
