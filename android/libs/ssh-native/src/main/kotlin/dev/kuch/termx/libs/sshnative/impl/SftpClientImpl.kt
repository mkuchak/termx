package dev.kuch.termx.libs.sshnative.impl

import dev.kuch.termx.libs.sshnative.SftpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream
import java.util.EnumSet

internal class SftpClientImpl(
    private val sftp: SFTPClient,
) : SftpClient {

    @Volatile private var closed = false

    override suspend fun read(remotePath: String): ByteArray = withContext(Dispatchers.IO) {
        check(!closed) { "SFTP closed" }
        try {
            sftp.open(remotePath, EnumSet.of(OpenMode.READ)).use { handle ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var offset = 0L
                while (true) {
                    val n = handle.read(offset, buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    offset += n
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun write(remotePath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(!closed) { "SFTP closed" }
        try {
            sftp.open(
                remotePath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC),
            ).use { handle ->
                handle.write(0, bytes, 0, bytes.size)
            }
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun list(remoteDir: String): List<String> = withContext(Dispatchers.IO) {
        check(!closed) { "SFTP closed" }
        try {
            sftp.ls(remoteDir)
                .map { it.name }
                .filter { it != "." && it != ".." }
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun exists(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        check(!closed) { "SFTP closed" }
        try {
            sftp.statExistence(remotePath) != null
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override suspend fun rename(src: String, dst: String) = withContext(Dispatchers.IO) {
        check(!closed) { "SFTP closed" }
        try {
            sftp.rename(src, dst)
        } catch (t: Throwable) {
            throw t.toSshException()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { sftp.close() }
    }
}
