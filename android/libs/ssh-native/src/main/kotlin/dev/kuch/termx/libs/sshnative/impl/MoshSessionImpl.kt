package dev.kuch.termx.libs.sshnative.impl

import android.os.ParcelFileDescriptor
import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * [MoshSession] backed by a mosh-client child spawned under a
 * pseudo-terminal (see [NativePty]).
 *
 * The earlier implementation used [ProcessBuilder], which wires
 * stdin/stdout/stderr as pipes — mosh-client then aborts at startup
 * with "tcgetattr: Inappropriate ioctl for device" because it can't
 * save the tty state. Launching under a pty fixes that; as a side
 * effect stdout and stderr are merged (a pty slave has a single
 * write side), which is fine for this transport — startup errors
 * still surface in the emulator, and the head-buffer diagnostic
 * below still captures the first kilobyte for the
 * [diagnostic] snapshot.
 *
 * Ownership:
 *  - [masterFd] is the original ParcelFileDescriptor handed back by
 *    [NativePty.spawn]. Its integer fd is the canonical handle used
 *    for TIOCSWINSZ. The I/O streams below use dup'd fds so each
 *    can be closed independently.
 *  - [pid] is the child's pid; we wait on it on a dedicated daemon
 *    thread (blocking waitpid doesn't play well with cancellable
 *    coroutines) and publish the decoded exit status via
 *    [diagnostic].
 *
 * [resize] uses [NativePty.setWindowSize] — TIOCSWINSZ on the master
 * makes the kernel deliver SIGWINCH to the slave's foreground process
 * group automatically, so there's no separate signal path here.
 *
 * [close] sends SIGTERM, waits briefly, then SIGKILL. All dup'd fds
 * plus the master are closed; the exit watcher thread unblocks as
 * soon as waitpid observes the child terminate and publishes the
 * final [diagnostic] snapshot on its way out.
 */
internal class MoshSessionImpl(
    private val masterFd: ParcelFileDescriptor,
    private val pid: Int,
) : MoshSession {

    @Volatile private var closed = false

    private val startTimeMs: Long = System.currentTimeMillis()

    private val headBuf = ByteArray(HEAD_CAP)
    @Volatile private var headLen: Int = 0

    private val _diagnostic = MutableStateFlow(
        MoshDiagnostic(exitCode = null, elapsedMs = 0L, head = ""),
    )
    override val diagnostic: StateFlow<MoshDiagnostic> = _diagnostic.asStateFlow()

    /**
     * Dup the master fd for the reader and writer so they own
     * independent kernel handles. Without the dup, closing one
     * stream would yank the fd out from under the other.
     */
    private val readPfd: ParcelFileDescriptor = masterFd.dup()
    private val writePfd: ParcelFileDescriptor = masterFd.dup()

    private val inputStream = ParcelFileDescriptor.AutoCloseInputStream(readPfd)
    private val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)

    init {
        startExitWatcher()
    }

    override val output: Flow<ByteArray> = channelFlow {
        val buf = ByteArray(8 * 1024)
        while (!closed) {
            val n = runCatching { inputStream.read(buf) }.getOrElse { -1 }
            if (n <= 0) break
            appendHead(buf, n)
            // `send` (not `trySend`) so the producer suspends when the
            // collector falls behind. Dropping bytes would corrupt the
            // emulator's scroll region — same rationale as PtyChannelImpl.
            send(buf.copyOf(n))
        }
        // Natural EOF: mosh-client exited or the pty closed. Let the
        // channelFlow unwind so the collector completes cleanly.
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        // We used to swallow IOException here and only log it. That made
        // the symptom from v1.1.12 invisible: when the mosh-client pipe
        // breaks (process exit, broken UDP path), writes silently
        // disappear and the user wonders why their PTT Send did nothing.
        // Surface failures so the caller can react (snackbar +
        // reconnect). Issue 2A, v1.1.13.
        try {
            outputStream.write(bytes)
            outputStream.flush()
        } catch (t: IOException) {
            Log.w(LOG_TAG, "mosh stdin write failed", t)
            throw t
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "mosh stdin write unexpected error", t)
            throw t
        }
    }

    override suspend fun resize(cols: Int, rows: Int): Unit = withContext(Dispatchers.IO) {
        try {
            NativePty.setWindowSize(masterFd.fd, rows, cols)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "TIOCSWINSZ to pty master failed", t)
        }
    }

    override fun close() {
        if (closed) return
        closed = true

        // Try a polite SIGTERM first so mosh-client can tear down the
        // remote session gracefully; escalate to SIGKILL if it ignores us.
        runCatching { NativePty.sendSignal(pid, NativePty.SIGTERM) }
        Thread.sleep(200)
        runCatching { NativePty.sendSignal(pid, NativePty.SIGKILL) }

        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { masterFd.close() }
    }

    private fun appendHead(src: ByteArray, n: Int) {
        if (headLen >= HEAD_CAP || n <= 0) return
        synchronized(headBuf) {
            val room = HEAD_CAP - headLen
            if (room <= 0) return
            val take = if (n < room) n else room
            System.arraycopy(src, 0, headBuf, headLen, take)
            headLen += take
        }
    }

    /**
     * Dedicated daemon thread around the blocking [NativePty.waitPid]
     * call. We can't host this on a coroutine because cancellation
     * wouldn't interrupt native waitpid — close() delivers SIGKILL,
     * which is what actually unblocks this thread.
     */
    private fun startExitWatcher() {
        thread(name = "mosh-waitpid-$pid", isDaemon = true) {
            val code = runCatching { NativePty.waitPid(pid) }.getOrElse { -1 }
            val elapsed = System.currentTimeMillis() - startTimeMs
            val ptyHead = synchronized(headBuf) {
                String(headBuf, 0, headLen, Charsets.UTF_8)
            }
            // bionic's dynamic linker writes failure messages
            // ("library X not found", "cannot locate symbol Y",
            // "p_align too small for kernel page size", …) and the
            // kernel's tombstone DEBUG output go to logcat — NOT to
            // the child's stdout/stderr. Our PTY-master capture sees
            // only what the running child writes. If the child dies
            // pre-main (SIGSEGV in <50 ms during dynamic linking or
            // a static C++ ctor), [ptyHead] is empty and we used to
            // surface "no output captured" to the user with no
            // actionable detail. v1.1.16: we now also pull the last
            // few hundred lines of logcat for this app's UID,
            // filtered to the dead child's pid, and append the
            // matching linker/DEBUG/libc lines so the snackbar
            // actually says what bionic complained about.
            val combinedHead = if (code != 0 && elapsed < EARLY_EXIT_WINDOW_MS) {
                val linkerLog = captureLinkerLogcat(pid).take(LINKER_LOG_CAP)
                if (linkerLog.isNotBlank()) {
                    if (ptyHead.isBlank()) linkerLog
                    else "$ptyHead\n--- linker log ---\n$linkerLog"
                } else ptyHead
            } else ptyHead
            _diagnostic.value = MoshDiagnostic(
                exitCode = code,
                elapsedMs = elapsed,
                head = combinedHead,
            )
            if (elapsed < EARLY_EXIT_WINDOW_MS && code != 0) {
                Log.w(
                    LOG_TAG,
                    "mosh-client exited in ${elapsed}ms code=$code head=${combinedHead.take(200)}",
                )
            }
        }
    }

    /**
     * Drain `logcat -d` (dump-and-exit) for the linker / native-crash
     * tags and filter to the dead child's pid. We can read this app's
     * UID's logcat lines without `READ_LOGS` permission since API 16,
     * and the pid filter keeps us strictly within our own child's
     * output.
     *
     * Returns "" on any failure — logcat may be unavailable on
     * specific OEM ROMs or under SELinux denial; the caller falls
     * back to the existing "no output captured" message.
     */
    private fun captureLinkerLogcat(deadPid: Int): String = runCatching {
        val proc = ProcessBuilder(
            "logcat",
            "-d",
            "-t", "500",
            "linker:V",
            "linker64:V",
            "DEBUG:V",
            "libc:V",
            "crash_dump:V",
            "*:S",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        runCatching { proc.waitFor() }
        // logcat default format is:
        //   MM-DD HH:MM:SS.MMM   PID  TID PRIORITY TAG    : MESSAGE
        // Filter by " $pid " (whitespace-bounded) so we don't false-
        // match a longer pid that happens to contain ours as a substring.
        val pidPattern = " $deadPid "
        output.lineSequence()
            .filter { it.contains(pidPattern) }
            .joinToString("\n")
    }.getOrElse { "" }

    private companion object {
        const val LOG_TAG = "MoshSessionImpl"

        /** Max bytes retained for the exit diagnostic. */
        const val HEAD_CAP = 1024

        /** Under this, an exitCode != 0 is almost certainly a startup failure. */
        const val EARLY_EXIT_WINDOW_MS = 2_000L

        /** Max characters of logcat output appended to the diagnostic. */
        const val LINKER_LOG_CAP = 4_000
    }
}
