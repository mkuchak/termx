package dev.kuch.termx.libs.sshnative.impl

import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.transport.MoshTransport

/**
 * [MoshSession] backed by the pure-Kotlin `ssp-transport` library
 * (vendored as the `:mosh-transport` composite build).
 *
 * v1.1.21 replaces the v1.1.20 native-binary `MoshSessionImpl` (which
 * fork/exec'd `libmoshclient.so` under a PTY) with this JVM-only
 * adapter. Same [MoshSession] interface, same `MoshDiagnostic` shape
 * — every consumer (TerminalViewModel, MoshPreflight, MoshExitMessage)
 * keeps working unchanged.
 *
 * The library exposes a callback-based API; we bridge to our existing
 * `Flow<ByteArray>` shape via an UNLIMITED Channel and translate the
 * library's `onDisconnect(cleanExit)` into a final [MoshDiagnostic]
 * write so [MoshExitMessage] can format the snackbar copy the user
 * has been seeing since v1.1.16.
 */
internal class SspMoshSessionImpl(
    host: String,
    port: Int,
    keyBase64: String,
    initialCols: Int,
    initialRows: Int,
) : MoshSession {

    /**
     * Owned scope so [close] can deterministically tear the transport's
     * receive + send loops down. Caller doesn't have to thread a scope
     * through `MoshClient.tryConnect`.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Callback-to-Flow bridge. `Channel.UNLIMITED` because the consumer
     * (the terminal emulator) is generally faster than UDP bandwidth;
     * a bounded buffer would risk dropping bytes on a stutter rather
     * than corrupting the screen. Closed in `onDisconnect` so the
     * collector completes cleanly.
     */
    private val outputCh = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    override val output: Flow<ByteArray> = outputCh.receiveAsFlow()

    private val _diagnostic = MutableStateFlow(MoshDiagnostic(exitCode = null, elapsedMs = 0L, head = ""))
    override val diagnostic: StateFlow<MoshDiagnostic> = _diagnostic.asStateFlow()

    private val startMs = System.currentTimeMillis()
    private val capturingLogger = CapturingMoshLogger()

    private val transport: MoshTransport = MoshTransport(
        serverIp = host,
        port = port,
        key = keyBase64,
        onOutput = { buf, off, len ->
            // Library hands us a fresh ByteArray (it's the protobuf
            // hostbytes payload, not a reused buffer), but we slice
            // anyway in case that contract ever changes.
            outputCh.trySend(buf.copyOfRange(off, off + len))
        },
        onDisconnect = { cleanExit ->
            val elapsed = System.currentTimeMillis() - startMs
            _diagnostic.value = mapDisconnect(cleanExit, elapsed, capturingLogger.lastError)
            outputCh.close()
        },
        logger = capturingLogger,
        initialCols = initialCols,
        initialRows = initialRows,
    )

    init {
        // Haven bug #73 fix: mosh strips DECSET 1 from the wire, so
        // arrow keys break inside vim/mutt/less unless we synthesise
        // DECCKM ON locally before any server output arrives.
        outputCh.trySend(DECCKM_ON_BYTES.copyOf())
        transport.start(scope)
    }

    override suspend fun write(bytes: ByteArray) {
        transport.sendInput(bytes)
    }

    override suspend fun resize(cols: Int, rows: Int) {
        transport.resize(cols, rows)
    }

    override fun close() {
        runCatching { transport.close() }
        scope.cancel()
        runCatching { outputCh.close() }
    }

    private companion object {
        /** `ESC [ ? 1 h` — DECCKM ON, Application Cursor Keys mode. */
        val DECCKM_ON_BYTES: ByteArray = byteArrayOf(0x1B, 0x5B, 0x3F, 0x31, 0x68)
    }
}

/**
 * Pure-logic mapping from the library's `onDisconnect(cleanExit)`
 * boolean to our existing [MoshDiagnostic] shape. Extracted so tests
 * can hit every branch without having to instantiate the transport
 * (which opens a real UDP socket on construction).
 *
 *  - **cleanExit=true** → server silence longer than `SESSION_DEAD_MS`
 *    (15s in our fork). Either the remote shell ended cleanly and
 *    `mosh-server` stopped sending, or the UDP path is blocked/lossy
 *    enough that we never received a frame back. Mapped to exitCode=0
 *    so [MoshExitMessage] picks the "exited cleanly … UDP path may
 *    be blocked" copy.
 *  - **cleanExit=false** → socket-create failure or receive-loop
 *    crash. The `lastError` text from [CapturingMoshLogger] carries
 *    whatever the transport logged on the way out. Mapped to
 *    exitCode=1 so [MoshExitMessage] picks the non-zero "exited after
 *    Xs (exit 1): <reason>" copy.
 */
internal fun mapDisconnect(
    cleanExit: Boolean,
    elapsedMs: Long,
    lastError: String,
): MoshDiagnostic = MoshDiagnostic(
    exitCode = if (cleanExit) 0 else 1,
    elapsedMs = elapsedMs,
    head = if (cleanExit) {
        "Mosh server stopped responding — no UDP traffic from server within the session-dead window. " +
            "VPS firewall may be blocking UDP ports 60000-61000, or the remote shell ended."
    } else {
        lastError.ifBlank { "Mosh transport error" }
    },
)

/**
 * [MoshLogger] that mirrors verbose/debug output to logcat AND
 * captures the most-recent error message + throwable so
 * [SspMoshSessionImpl] can stuff it into [MoshDiagnostic.head] when
 * the transport's `onDisconnect(cleanExit = false)` fires (which
 * carries no reason on its own).
 */
internal class CapturingMoshLogger : MoshLogger {

    @Volatile
    var lastError: String = ""
        private set

    override fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    override fun e(tag: String, msg: String, throwable: Throwable?) {
        val cause = throwable?.message?.takeIf { it.isNotBlank() }
        lastError = if (cause != null) "$msg: $cause" else msg
        Log.w(tag, msg, throwable)
    }
}
