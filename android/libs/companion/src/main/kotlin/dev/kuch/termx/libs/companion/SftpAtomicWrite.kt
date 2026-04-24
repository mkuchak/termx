package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.sshnative.SftpClient
import java.util.UUID

/**
 * Write [bytes] to [path] on the remote in a way that readers on the VPS
 * (typically termxd, polling the commands directory) can never observe a
 * partially-written file.
 *
 * The protocol is the classic temp-file-plus-rename dance:
 *  1. Write the full payload to a unique sibling path
 *     (see [TEMP_SUFFIX_SEPARATOR] — a dot-prefixed suffix containing a
 *     random UUID so two concurrent writers can't collide).
 *  2. Issue an SFTP rename from the temp path to the final path. On POSIX
 *     file systems rename-within-the-same-directory is atomic: termxd
 *     either sees the old file (or nothing) or the fully-written new one,
 *     never a torn in-progress write.
 *
 * Failure mode: if the write lands but the rename fails, the temp file is
 * left on disk. It's harmless — termxd only looks at files matching the
 * `<uuid>.json` pattern in the commands dir, and a stale `.tmp.<uuid>`
 * entry never matches. We intentionally do NOT try to clean it up from
 * here: the next successful write cycle proves the SFTP channel is
 * healthy, and a cron-style cleanup belongs in termxd itself.
 */
suspend fun SftpClient.writeAtomic(path: String, bytes: ByteArray) {
    val tmp = "$path$TEMP_SUFFIX_SEPARATOR${UUID.randomUUID()}"
    write(tmp, bytes)
    rename(tmp, path)
}

/**
 * Suffix separator for atomic-write temp files. Kept as a constant so
 * tests can assert against the exact shape of the temp path without
 * hard-coding the dot-prefix convention in multiple places.
 */
const val TEMP_SUFFIX_SEPARATOR: String = ".tmp."
