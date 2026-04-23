package dev.kuch.termx.libs.sshnative

/**
 * Minimal SFTP surface. Phase 4+ (termxd companion) uses this for reading
 * `~/.termx/sessions/*.json` and tailing `events.ndjson` when a plain
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

    override fun close()
}
