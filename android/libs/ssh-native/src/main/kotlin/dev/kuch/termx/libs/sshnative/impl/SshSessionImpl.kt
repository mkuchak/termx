package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SftpClient
import dev.kuch.termx.libs.sshnative.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode

/**
 * sshj-backed [SshSession].
 *
 * sshj is not thread-safe for concurrent channel-open operations on the
 * same transport; a [Mutex] serializes `openShell` / `openExec` / `openSftp`.
 * Reads and writes on an already-open channel are independent and don't
 * hold the mutex.
 */
internal class SshSessionImpl(
    private val client: SSHClient,
) : SshSession {

    private val openLock = Mutex()
    private val children = mutableListOf<AutoCloseable>()
    @Volatile private var closed = false

    override suspend fun openShell(term: String, cols: Int, rows: Int, command: String?): PtyChannel =
        withContext(Dispatchers.IO) {
            openLock.withLock {
                check(!closed) { "Session closed" }
                try {
                    val session = client.startSession()
                    session.allocatePTY(term, cols, rows, 0, 0, emptyMap<PTYMode, Int>())
                    val pty = if (command == null) {
                        val shell = session.startShell()
                        PtyChannelImpl(session, shell = shell, command = null)
                    } else {
                        // Exec-with-PTY. The PTY was allocated on the same
                        // sshj Session right above, so the remote process
                        // gets a real TTY — interactive full-screen programs
                        // like vim behave correctly.
                        val cmd = session.exec(command)
                        PtyChannelImpl(session, shell = null, command = cmd)
                    }
                    children += pty
                    pty
                } catch (t: Throwable) {
                    throw t.toSshException()
                }
            }
        }

    override suspend fun openExec(command: String): ExecChannel =
        withContext(Dispatchers.IO) {
            openLock.withLock {
                check(!closed) { "Session closed" }
                try {
                    val session = client.startSession()
                    val cmd = session.exec(command)
                    val exec = ExecChannelImpl(session, cmd)
                    children += exec
                    exec
                } catch (t: Throwable) {
                    throw t.toSshException()
                }
            }
        }

    override suspend fun openSftp(): SftpClient = withContext(Dispatchers.IO) {
        openLock.withLock {
            check(!closed) { "Session closed" }
            try {
                val sftp = client.newSFTPClient()
                val wrapper = SftpClientImpl(sftp)
                children += wrapper
                wrapper
            } catch (t: Throwable) {
                throw t.toSshException()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(children) {
            children.forEach { runCatching { it.close() } }
            children.clear()
        }
        runCatching { client.disconnect() }
    }

    override suspend fun closeAsync() = withContext(Dispatchers.IO) { close() }

    /**
     * Passive — sshj flips `isConnected` to false the moment the transport
     * dies (keepalive `CONNECTION_LOST`, RST), but keeps it true when only
     * a channel closed (remote `exit`). See [SshSession.isTransportAlive].
     */
    override fun isTransportAlive(): Boolean = !closed && client.isConnected

    /**
     * Authoritative bounded round-trip: a trivial remote exec. A half-open
     * socket never returns the channel-open reply, so [withTimeoutOrNull]
     * elapses and we report dead. Reuses the well-tested exec path rather
     * than poking sshj's keepalive internals. See [SshSession.probe].
     */
    override suspend fun probe(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (closed || !client.isConnected) return@withContext false
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                val exec = openExec("true")
                try {
                    exec.exitCode.await()
                } finally {
                    runCatching { exec.close() }
                }
                true
            }.getOrDefault(false)
        } ?: false
    }
}
