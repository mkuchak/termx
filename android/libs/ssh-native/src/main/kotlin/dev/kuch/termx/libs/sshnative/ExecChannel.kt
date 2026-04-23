package dev.kuch.termx.libs.sshnative

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * A non-interactive command execution channel.
 *
 * `stdout` and `stderr` are cold Flows; each completes when its stream ends.
 * [exitCode] resolves once the remote process exits. Awaiting `exitCode`
 * after both streams complete is safe and gives the process's return status.
 */
interface ExecChannel : AutoCloseable {
    val stdout: Flow<ByteArray>
    val stderr: Flow<ByteArray>
    val exitCode: Deferred<Int>

    /** Pipe bytes into the remote command's stdin (use for simple feeds; no TTY). */
    suspend fun write(bytes: ByteArray)

    override fun close()
}
