package dev.kuch.termx.libs.sshnative.impl

import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [MoshSession] backed by a local mosh-client child [Process].
 *
 * I/O shape mirrors [PtyChannelImpl]: [output] reads the process
 * stdout and pushes chunks into a [channelFlow]. Stderr is drained on
 * a dedicated daemon thread into a bounded "head" buffer — we keep the
 * first 1 KB for diagnostics so "mosh-client died instantly with
 * Cannot find termcap entry" stops looking like a silent disconnect.
 * We deliberately do NOT wrap the producer in
 * `try { ... } finally { awaitClose { } }` — that's the deadlock fixed
 * in c7ea47b for the sshj flows, and it applies identically here.
 *
 * [resize] propagates a SIGWINCH to the mosh-client PID via
 * [android.system.Os.kill]. mosh-client re-reads its controlling TTY
 * dimensions on SIGWINCH and sends a window-change message over UDP to
 * mosh-server.
 *
 * [close] tries a clean `destroy()` first (SIGTERM on Android's
 * ProcessImpl), waits briefly, then escalates to `destroyForcibly()` —
 * same pattern as the Termux session-kill path.
 */
internal class MoshSessionImpl(
    private val proc: Process,
) : MoshSession {

    @Volatile private var closed = false

    /**
     * Wall-clock at construction so we can compute "exited within N ms"
     * for the early-exit heuristic without threading a clock around.
     */
    private val startTimeMs: Long = System.currentTimeMillis()

    /**
     * Growing buffer of the first bytes seen on stdout+stderr, capped
     * at [HEAD_CAP]. We surface this via [diagnostic] on early exit so
     * the user sees ncurses / mosh error text instead of nothing.
     */
    private val headBuf = ByteArray(HEAD_CAP)
    @Volatile private var headLen: Int = 0

    /** Latest diagnostic snapshot; updated when the process terminates. */
    private val _diagnostic = MutableStateFlow(
        MoshDiagnostic(exitCode = null, elapsedMs = 0L, head = ""),
    )
    override val diagnostic: StateFlow<MoshDiagnostic> = _diagnostic.asStateFlow()

    /**
     * Background scope for the stderr drainer + exit watcher. Cancelled
     * from [close] so both threads exit promptly.
     */
    private val auxScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * We resolve and cache the PID at construction time so [resize]
     * doesn't have to re-walk reflection on every SIGWINCH. API 24+
     * (min is 28) makes [Process.pid] the cheap path.
     */
    private val pid: Long = resolvePid(proc)

    init {
        startStderrDrainer()
        startExitWatcher()
    }

    override val output: Flow<ByteArray> = channelFlow {
        val buf = ByteArray(8 * 1024)
        val stream = proc.inputStream
        while (!closed) {
            val n = runCatching { stream.read(buf) }.getOrElse { -1 }
            if (n <= 0) break
            appendHead(buf, n)
            trySend(buf.copyOf(n))
        }
        // Natural EOF: mosh-client exited or the stream errored. Let
        // the producer return, channelFlow auto-closes the channel,
        // the collector completes. See c7ea47b for the anti-pattern.
    }.flowOn(Dispatchers.IO)

    override suspend fun write(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        try {
            proc.outputStream.write(bytes)
            proc.outputStream.flush()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "mosh stdin write failed", t)
        }
    }

    override suspend fun resize(cols: Int, rows: Int): Unit = withContext(Dispatchers.IO) {
        if (pid <= 0L) return@withContext
        try {
            // SIGWINCH=28 on Linux/Android-arm. mosh-client responds by
            // re-reading its TTY size; we don't set the TTY ourselves —
            // the child inherits our stdio, so the phone-side terminal
            // emulator's own size change is what propagates anyway.
            Os.kill(pid.toInt(), SIGWINCH)
        } catch (t: ErrnoException) {
            Log.w(LOG_TAG, "SIGWINCH to $pid failed", t)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "resize failed", t)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { proc.outputStream.close() }
        runCatching { proc.destroy() }
        // Give mosh-client ~200ms to exit cleanly after SIGTERM, then
        // force-kill. We're on an IO pool, so a short block is fine.
        try {
            if (!proc.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                runCatching { proc.destroyForcibly() }
            }
        } catch (_: InterruptedException) {
            runCatching { proc.destroyForcibly() }
        }
        runCatching { auxScope.cancel() }
    }

    /**
     * Append up to [n] bytes of [src] into [headBuf], stopping once we
     * hit [HEAD_CAP]. Thread-safe through the single writer invariant:
     * stdout is read on the output flow thread, stderr on the drainer
     * thread, and they both call this — we serialize via `synchronized`.
     */
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
     * Drain stderr on a dedicated daemon thread so stderr bytes never
     * block stdout. Bytes are copied into [headBuf] for the exit
     * diagnostic; we don't otherwise forward them to the emulator —
     * that would turn a startup error like "Cannot find termcap
     * entry for xterm-256color" into terminal garbage rather than a
     * human-readable post-mortem.
     */
    private fun startStderrDrainer() {
        thread(name = "mosh-stderr-$pid", isDaemon = true) {
            val buf = ByteArray(4 * 1024)
            val stream = proc.errorStream
            while (!closed) {
                val n = runCatching { stream.read(buf) }.getOrElse { -1 }
                if (n <= 0) break
                appendHead(buf, n)
            }
        }
    }

    /**
     * Watch for process termination on an aux coroutine. When the
     * child exits, compute elapsed wall-clock + snapshot the captured
     * head bytes and publish to [diagnostic]. If the exit was fast
     * and non-zero, log a WARN so the "mosh disconnects immediately"
     * class of bugs leaves a trail in logcat as well.
     */
    private fun startExitWatcher(): Job = auxScope.launch {
        val code = runCatching { proc.waitFor() }.getOrElse { -1 }
        val elapsed = System.currentTimeMillis() - startTimeMs
        val headSnapshot = synchronized(headBuf) {
            String(headBuf, 0, headLen, Charsets.UTF_8)
        }
        _diagnostic.value = MoshDiagnostic(
            exitCode = code,
            elapsedMs = elapsed,
            head = headSnapshot,
        )
        if (elapsed < EARLY_EXIT_WINDOW_MS && code != 0) {
            Log.w(
                LOG_TAG,
                "mosh-client exited in ${elapsed}ms code=$code head=${headSnapshot.take(200)}",
            )
        }
    }

    /**
     * Resolve the process PID.
     *
     * Android's `android.jar` desugared stubs for `java.lang.Process` do
     * not expose `pid()` directly from Kotlin (the method is present on
     * the runtime class but the SDK stubs mask it behind an
     * `@RequiresApi(26)`-style gate that KGP doesn't see at compile
     * time). We dispatch by reflection on
     * `java.lang.Process.pid` (preferred on API 28+), falling back to
     * the private `pid` field that Android's `ProcessImpl` has carried
     * since Jelly Bean for older hosts / mocks.
     */
    private fun resolvePid(p: Process): Long {
        try {
            val m = Process::class.java.getMethod("pid")
            val result = m.invoke(p)
            if (result is Long) return result
            if (result is Int) return result.toLong()
        } catch (_: Throwable) {
            // fall through to the private-field path
        }
        return try {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(p).toLong()
        } catch (_: Throwable) {
            -1L
        }
    }

    private companion object {
        const val LOG_TAG = "MoshSessionImpl"
        const val SIGWINCH = 28

        /** Max bytes of stdout+stderr retained for the exit diagnostic. */
        const val HEAD_CAP = 1024

        /**
         * Anything under this is considered "died immediately" — within
         * this window an exitCode != 0 is almost certainly a startup
         * failure (missing terminfo, failed dlopen) rather than a
         * user-initiated exit.
         */
        const val EARLY_EXIT_WINDOW_MS = 2_000L
    }
}
