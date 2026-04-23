package dev.kuch.termx.libs.sshnative

import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import java.io.InputStream
import java.io.OutputStream

/**
 * Runs an exec command through the host `/bin/sh -c <cmd>`. Good enough for
 * the `echo hello` sanity test; the CI image always has a POSIX sh.
 */
internal class ProcessShellWrapper(private val command: String) : Command {
    private var stdin: InputStream? = null
    private var stdout: OutputStream? = null
    private var stderr: OutputStream? = null
    private var callback: ExitCallback? = null
    private var process: Process? = null
    private var worker: Thread? = null

    override fun setInputStream(`in`: InputStream) { stdin = `in` }
    override fun setOutputStream(out: OutputStream) { stdout = out }
    override fun setErrorStream(err: OutputStream) { stderr = err }
    override fun setExitCallback(cb: ExitCallback) { callback = cb }

    override fun start(channel: ChannelSession, env: Environment) {
        val pb = ProcessBuilder("/bin/sh", "-c", command)
        val p = pb.start()
        process = p

        val out = stdout!!
        val err = stderr!!
        val cb = callback!!

        worker = Thread {
            try {
                val outT = Thread { p.inputStream.copyTo(out); out.flush() }
                val errT = Thread { p.errorStream.copyTo(err); err.flush() }
                outT.start(); errT.start()
                val code = p.waitFor()
                outT.join(); errT.join()
                cb.onExit(code)
            } catch (t: Throwable) {
                cb.onExit(1, t.message ?: "error")
            }
        }.also { it.start() }
    }

    override fun destroy(channel: ChannelSession) {
        runCatching { process?.destroyForcibly() }
        runCatching { worker?.interrupt() }
    }
}
