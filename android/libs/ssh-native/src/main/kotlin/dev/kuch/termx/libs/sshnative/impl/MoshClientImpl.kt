package dev.kuch.termx.libs.sshnative.impl

import android.content.Context
import android.util.Log
import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.MoshConnectResult
import dev.kuch.termx.libs.sshnative.MoshFailureReason
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raw mosh-client process wrapper.
 *
 * 1. Opens a short-lived SSH session (reused [SshClient]) and runs
 *    `LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i <bindIp>
 *    -p <portRange>`.
 * 2. Drains stdout AND stderr concurrently, scanning stdout for the
 *    canonical `MOSH CONNECT <port> <key>` line — see https://mosh.org.
 *    stderr is kept: if the handshake fails it is classified into a
 *    [MoshFailureReason] (missing UTF-8 locale and missing mosh-server
 *    are the two failures users can actually fix).
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
 * Closes everything and returns [MoshConnectResult.Failed] on the first
 * sign that the handshake isn't going to land in time.
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
        startupCommand: String? = null,
    ): MoshConnectResult {
        val handshake = when (
            val attempt =
                handshake(target, auth, bindIp, portRange, startupCommand, handshakeTimeoutMs)
        ) {
            is HandshakeAttempt.Failure -> return MoshConnectResult.Failed(attempt.reason)
            is HandshakeAttempt.Success -> attempt.handshake
        }

        val session = spawnMoshClient(target.host, handshake.port, handshake.key)
            // Local failure (binary missing from the APK, pty spawn error)
            // — not a remote stderr, so it lands in Other with a local
            // description rather than abusing one of the remote reasons.
            ?: return MoshConnectResult.Failed(
                MoshFailureReason.Other("local mosh-client failed to start"),
            )
        return MoshConnectResult.Success(session)
    }

    /**
     * Run `mosh-server new` and scan its stdout for the first MOSH CONNECT
     * line, with the whole attempt (SSH connect included) capped at
     * [timeoutMs]. On failure, classify stderr into a [MoshFailureReason].
     */
    private suspend fun handshake(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
        startupCommand: String?,
        timeoutMs: Long,
    ): HandshakeAttempt = withContext(Dispatchers.IO) {
        // The stderr transcript lives OUTSIDE the timeout scope: when the
        // budget expires mid-collect we still classify from whatever
        // mosh-server (or the remote shell) managed to say before the
        // cancellation. StringBuffer (synchronized) because the collector
        // job appends from another IO thread than the one reading it here.
        val stderr = StringBuffer()
        val handshake = try {
            withTimeoutOrNull(timeoutMs) {
                val session: SshSession = sshClient.connect(target, auth)
                try {
                    val cmd = moshServerCommand(bindIp, portRange, startupCommand)
                    session.openExec(cmd).use { exec -> scanForConnectLine(exec, stderr) }
                } finally {
                    runCatching { session.close() }
                }
            }
        } catch (c: CancellationException) {
            // Caller went away (tab closed, VM cleared) — propagate; this
            // is not a handshake verdict.
            throw c
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "mosh-server handshake failed", t)
            // The SSH bootstrap itself blew up (auth, unreachable host,
            // channel error). Surface only the message — never the sshj
            // type; the layering rule from Exceptions.kt applies here too.
            return@withContext HandshakeAttempt.Failure(
                MoshFailureReason.Other(
                    "ssh bootstrap failed: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }
        if (handshake != null) {
            HandshakeAttempt.Success(handshake)
        } else {
            HandshakeAttempt.Failure(classifyHandshakeFailure(stderr.toString()))
        }
    }

    /**
     * Drain stdout and stderr CONCURRENTLY while scanning stdout for the
     * MOSH CONNECT line.
     *
     * Reading both streams is load-bearing, not hygiene: sshj gives
     * stdout and stderr separate flow-control windows, so a remote that
     * writes enough to stderr while we only read stdout can deadlock the
     * channel — same rule as `execCapture` in core/data's
     * InstallCompanionUseCaseImpl. It is also what makes failure
     * classification possible at all: mosh-server's refusals (no UTF-8
     * locale) and the shell's "command not found" both go to stderr.
     */
    private suspend fun scanForConnectLine(
        exec: ExecChannel,
        stderr: StringBuffer,
    ): Handshake? {
        val stdout = StringBuilder()
        var handshake: Handshake? = null
        // Using a throwable-based short-circuit: `collect` is terminal,
        // there's no `collectUntil` primitive in the stdlib, and
        // re-subscribing isn't an option because we'd lose buffered
        // bytes. The exception unwinds through coroutineScope, which
        // also cancels the sibling stderr drain — by then we have what
        // we need.
        try {
            coroutineScope {
                launch {
                    exec.stderr.collect { chunk ->
                        stderr.append(String(chunk, Charsets.UTF_8))
                    }
                }
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
                // stdout hit EOF without a match (refusal, missing binary,
                // old mosh printing usage). coroutineScope now waits for
                // the stderr job to drain to EOF so the classifier sees
                // the full refusal text instead of a truncated race; the
                // caller's timeout backstops a stderr that never closes.
            }
        } catch (_: HandshakeFoundException) {
            // expected: signals we saw the MOSH CONNECT line
        }
        return handshake
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

    /** Internal handshake verdict; the public mapping happens in [tryConnect]. */
    private sealed interface HandshakeAttempt {
        data class Success(val handshake: Handshake) : HandshakeAttempt
        data class Failure(val reason: MoshFailureReason) : HandshakeAttempt
    }

    /** Private marker to short-circuit out of the exec stdout collect. */
    private object HandshakeFoundException : RuntimeException() {
        private fun readResolve(): Any = HandshakeFoundException
    }

    /** Alias used at the throw site to keep the name grammatical. */
    private val HandshakeFound: HandshakeFoundException get() = HandshakeFoundException

    internal companion object {
        const val LOG_TAG = "MoshClientImpl"
        const val MOSH_CLIENT_SO = "libmoshclient.so"
        val MOSH_CONNECT_REGEX = Regex("""MOSH CONNECT (\d+) (\S+)""")

        /** Cap on [MoshFailureReason.Other.detail] — enough for any real error line. */
        const val OTHER_DETAIL_MAX_CHARS = 200

        /**
         * Build the server-side bootstrap line that termx runs over SSH:
         * `LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i <bindIp>
         * -p <portRange>`.
         *
         * The LANG/LC_ALL prefix exists because `openExec` lands in a
         * non-login, non-interactive remote shell: minimal VPS images leave
         * it in the C/POSIX locale, and mosh-server hard-refuses to start
         * without a UTF-8 native locale ("needs a UTF-8 native locale").
         * C.UTF-8 over en_US.UTF-8 because it ships with glibc/musl out of
         * the box — no `locale-gen` step required on stock Debian/Ubuntu/
         * Alpine. It is a POSIX env-assignment prefix: it applies to the
         * mosh-server process only and MUST sit before the binary —
         * everything after ` -- ` belongs verbatim to the startup command.
         *
         * When [startupCommand] is non-null and non-blank, mosh-server is
         * told to run it instead of the login shell by appending
         * `-- <startupCommand>`. The command is appended VERBATIM: it
         * arrives already wrapped by the caller (e.g.
         * `${'$'}{SHELL:-/bin/sh} -lc '…'`) and the remote SSH login shell
         * that parses this whole line is what expands `${'$'}{SHELL}` and
         * strips the single quotes before mosh-server execvp's the argv.
         * Re-quoting or escaping here would break that. Pure + side-effect
         * free so it can be unit-tested on the JVM.
         */
        internal fun moshServerCommand(
            bindIp: String,
            portRange: String,
            startupCommand: String?,
        ): String {
            val base =
                "LANG=C.UTF-8 LC_ALL=C.UTF-8 mosh-server new -s -c 256 -i $bindIp -p $portRange"
            return if (!startupCommand.isNullOrBlank()) {
                "$base -- $startupCommand"
            } else {
                base
            }
        }

        /**
         * Classify a failed handshake from the remote exec's stderr
         * transcript. Pure + side-effect free so it can be unit-tested on
         * the JVM (same convention as [moshServerCommand]).
         *
         * Order matters: the UTF-8 probe runs BEFORE the "not found" probe
         * because locale failures often contain "not found" too (e.g.
         * `locale: charmap 'C.UTF-8' not found`) and would otherwise
         * misclassify as a missing mosh-server binary.
         */
        internal fun classifyHandshakeFailure(stderr: String): MoshFailureReason {
            val text = stderr.trim()
            return when {
                // mosh-server's hard refusal: "mosh-server needs a UTF-8
                // native locale to run." — plus setlocale/charmap noise
                // from the shell, all of which mentions UTF-8.
                text.contains("UTF-8", ignoreCase = true) ->
                    MoshFailureReason.MissingUtf8Locale
                // Covers both bash ("mosh-server: command not found") and
                // POSIX sh/dash ("mosh-server: not found").
                text.contains("not found", ignoreCase = true) ->
                    MoshFailureReason.MoshServerMissing
                text.isNotEmpty() ->
                    MoshFailureReason.Other(text.take(OTHER_DETAIL_MAX_CHARS))
                // Nothing on stderr and no MOSH CONNECT line: the attempt
                // simply ran out the clock (slow host, stalled exec).
                else -> MoshFailureReason.HandshakeTimeout
            }
        }

        // Reasonable default geometry for the initial pty size. The
        // composable-side emulator calls resize() with the real cell
        // count as soon as the AndroidView attaches, so these are just
        // "something sensible" for the ~1 frame before that happens.
        const val INITIAL_PTY_COLS = 80
        const val INITIAL_PTY_ROWS = 24
    }
}
