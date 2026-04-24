package dev.kuch.termx.libs.sshnative.impl

import android.content.Context
import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raw mosh-client process wrapper.
 *
 * 1. Opens a short-lived SSH session (reused [SshClient]) and runs
 *    `mosh-server new -s -c 256 -i <bindIp> -p <portRange>`.
 * 2. Drains stdout looking for the canonical `MOSH CONNECT <port> <key>`
 *    line — see https://mosh.org. Anything else (banner chatter, usage
 *    output from an old mosh, errors) is discarded.
 * 3. Closes the exec + SSH session — mosh-server double-forks and
 *    detaches, so the UDP listener stays up.
 * 4. Spawns `libmoshclient.so` from the APK's native lib dir UNDER A
 *    PSEUDO-TERMINAL (see [NativePty]) with `MOSH_KEY` in the
 *    environment and `LD_LIBRARY_PATH` pointing at the same dir so the
 *    50+ bundled `lib*_mosh.so` deps resolve. A real pty is mandatory
 *    — a ProcessBuilder-style pipe makes mosh-client abort at startup
 *    with "tcgetattr: Inappropriate ioctl for device".
 * 5. Wraps the resulting (masterFd, pid) in a [MoshSessionImpl].
 *
 * Closes everything and returns `null` on the first sign that the
 * handshake isn't going to land in time.
 */
internal class MoshClientImpl(
    private val context: Context,
    private val sshClient: SshClient,
) {

    suspend fun tryConnect(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
        handshakeTimeoutMs: Long,
    ): MoshSession? {
        val handshake = withTimeoutOrNull(handshakeTimeoutMs) {
            handshake(target, auth, bindIp, portRange)
        } ?: return null

        return spawnMoshClient(target.host, handshake.port, handshake.key)
    }

    /**
     * Drain `mosh-server new` stdout for the first MOSH CONNECT line.
     * Returns null if the command exits without emitting one.
     */
    private suspend fun handshake(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
    ): Handshake? = withContext(Dispatchers.IO) {
        val session: SshSession = sshClient.connect(target, auth)
        try {
            val cmd = "mosh-server new -s -c 256 -i $bindIp -p $portRange"
            session.openExec(cmd).use { exec ->
                val stdout = StringBuilder()
                var handshake: Handshake? = null
                // Using a throwable-based short-circuit: `collect` is
                // terminal, there's no `collectUntil` primitive in the
                // stdlib, and re-subscribing isn't an option because
                // we'd lose buffered bytes. The exception cancels the
                // sshj exec's stdout flow cleanly.
                try {
                    exec.stdout.collect { chunk ->
                        stdout.append(String(chunk, Charsets.UTF_8))
                        val match = MOSH_CONNECT_REGEX.find(stdout)
                        if (match != null) {
                            val port = match.groupValues[1].toIntOrNull()
                            val key = match.groupValues[2].trim()
                            if (port != null && key.isNotEmpty()) {
                                handshake = Handshake(port, key)
                                throw HandshakeFound
                            }
                        }
                    }
                } catch (_: HandshakeFoundException) {
                    // expected: signals we saw the MOSH CONNECT line
                }
                handshake
            }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "mosh-server handshake failed", t)
            null
        } finally {
            runCatching { session.close() }
        }
    }

    /**
     * Start `libmoshclient.so <host> <port>` under a pseudo-terminal
     * (see [NativePty]) with `MOSH_KEY` in env and the native lib dir
     * on `LD_LIBRARY_PATH`.
     *
     * Previously launched via [ProcessBuilder], which wires the child's
     * stdio as pipes — mosh-client's early `tcgetattr(STDIN_FILENO, …)`
     * then returns ENOTTY and the client aborts in ~150 ms. A pty
     * gives it a real terminal to inspect.
     *
     * Initial pty geometry is 80×24; the emulator calls `resize` with
     * the real cell count right after binding, which is what actually
     * propagates to the remote via TIOCSWINSZ → SIGWINCH.
     */
    private suspend fun spawnMoshClient(
        host: String,
        port: Int,
        key: String,
    ): MoshSession? = withContext(Dispatchers.IO) {
        val binDir = context.applicationInfo.nativeLibraryDir
        val moshBin = File(binDir, MOSH_CLIENT_SO)
        if (!moshBin.exists()) {
            Log.w(LOG_TAG, "mosh-client binary missing at ${moshBin.absolutePath}")
            return@withContext null
        }
        try {
            // Extract the bundled minimal terminfo tree so ncurses'
            // setupterm() can resolve `xterm-256color`/`xterm`/`vt100`/
            // `dumb`. Stock Android has no /usr/share/terminfo, which
            // is why mosh-client used to exit in <100 ms with a silent
            // "Cannot find termcap entry" write to stderr.
            val terminfoDir = TerminfoInstaller.ensureInstalled(context)
            val env = mapOf(
                "TERMINFO" to terminfoDir.absolutePath,
                "TERM" to "xterm-256color",
                "MOSH_KEY" to key,
                "LD_LIBRARY_PATH" to binDir,
                "HOME" to context.filesDir.absolutePath,
                "LANG" to "en_US.UTF-8",
                "LC_ALL" to "en_US.UTF-8",
                // Populate PATH so libc can resolve helper lookups if
                // mosh ever shells out. Unlikely but cheap.
                "PATH" to "/system/bin:/system/xbin",
            )
            val argv = arrayOf(moshBin.absolutePath, host, port.toString())
            val spawn = NativePty.spawn(
                path = moshBin.absolutePath,
                argv = argv,
                envp = env,
                rows = INITIAL_PTY_ROWS,
                cols = INITIAL_PTY_COLS,
            )
            MoshSessionImpl(spawn.master, spawn.pid)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "failed to spawn mosh-client", t)
            null
        }
    }

    private data class Handshake(val port: Int, val key: String)

    /** Private marker to short-circuit out of the exec stdout collect. */
    private object HandshakeFoundException : RuntimeException() {
        private fun readResolve(): Any = HandshakeFoundException
    }

    /** Alias used at the throw site to keep the name grammatical. */
    private val HandshakeFound: HandshakeFoundException get() = HandshakeFoundException

    private companion object {
        const val LOG_TAG = "MoshClientImpl"
        const val MOSH_CLIENT_SO = "libmoshclient.so"
        val MOSH_CONNECT_REGEX = Regex("""MOSH CONNECT (\d+) (\S+)""")

        // Reasonable default geometry for the initial pty size. The
        // composable-side emulator calls resize() with the real cell
        // count as soon as the AndroidView attaches, so these are just
        // "something sensible" for the ~1 frame before that happens.
        const val INITIAL_PTY_COLS = 80
        const val INITIAL_PTY_ROWS = 24
    }
}
