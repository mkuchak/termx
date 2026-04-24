package dev.kuch.termx.libs.sshnative.impl

import android.system.ErrnoException
import android.system.Os
import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * [MoshSession] backed by a local mosh-client child [Process].
 *
 * I/O shape mirrors [PtyChannelImpl]: [output] reads the process stdout
 * (which had stderr merged into it at [ProcessBuilder] construction
 * time) and pushes chunks into a [channelFlow]. We deliberately do
 * **not** wrap the producer in `try { ... } finally { awaitClose { } }`
 * — that's the deadlock fixed in c7ea47b for the sshj flows, and it
 * applies identically here.
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
     * We resolve and cache the PID at construction time so [resize]
     * doesn't have to re-walk reflection on every SIGWINCH. API 24+
     * (min is 28) makes [Process.pid] the cheap path.
     */
    private val pid: Long = resolvePid(proc)

    override val output: Flow<ByteArray> = channelFlow {
        val buf = ByteArray(8 * 1024)
        val stream = proc.inputStream
        while (!closed) {
            val n = runCatching { stream.read(buf) }.getOrElse { -1 }
            if (n <= 0) break
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
    }
}
