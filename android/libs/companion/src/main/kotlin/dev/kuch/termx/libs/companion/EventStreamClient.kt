package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.companion.events.CompanionCommand
import dev.kuch.termx.libs.companion.events.EventParser
import dev.kuch.termx.libs.companion.events.NdjsonBuffer
import dev.kuch.termx.libs.companion.events.SessionState
import dev.kuch.termx.libs.companion.events.TermxEvent
import dev.kuch.termx.libs.sshnative.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Streams VPS events to the phone by running `tail -F
 * ~/.termx/events.ndjson` inside the supplied [SshSession], framing bytes
 * into lines with [NdjsonBuffer], and decoding each line with [EventParser].
 *
 * Lifecycle and ownership:
 *  - The [SshSession] is owned by the caller. This client opens exec and
 *    SFTP channels over it but never closes the session itself.
 *  - [stream] is a cold Flow; nothing happens until a collector subscribes.
 *    It re-opens the tail forever with a 1 s backoff if the exec dies
 *    (network flap, log-rotate, tmux kill of a stray `tail`), until the
 *    collecting coroutine is cancelled.
 *
 * Resilience characteristics:
 *  - Transient exec failures are swallowed and logged to the [errors]
 *    SharedFlow so UI can show a glitch toast without cancelling the
 *    flow. [CancellationException] is rethrown as-is.
 *  - The event flow has an internal 4096-slot buffer with
 *    [BufferOverflow.DROP_OLDEST]: if a slow consumer falls behind, the
 *    oldest event is dropped in preference to blocking the tail. This is
 *    the right trade-off for a UI consumer — we'd rather miss old
 *    heartbeats than stall on the notification path.
 *
 * Path handling:
 *  - `tail` runs under a remote shell so `~` is expanded server-side for
 *    free. For [loadSessionRegistry] and [sendCommand] we need absolute
 *    paths (sshj's SFTP client doesn't expand `~`), so the client caches
 *    the resolved `$HOME` the first time one of those calls runs. See
 *    [resolveHomeDir].
 */
class EventStreamClient(
    private val session: SshSession,
    private val parser: EventParser = EventParser(),
) : AutoCloseable {

    private val _errors = MutableSharedFlow<Throwable>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    /**
     * Glitches that don't terminate the stream (exec died and will be
     * retried, malformed frame, etc.). Optional to consume — Phase 7 will
     * use this to surface connection banners.
     */
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    private val homeMutex = Mutex()

    @Volatile
    private var cachedHome: String? = null

    /**
     * Cold flow of decoded events. Re-subscribing starts a fresh tail.
     *
     * Transport model:
     *  - One `tail -F ~/.termx/events.ndjson` over SSH exec per attempt.
     *    bytes are framed into lines by [NdjsonBuffer] and decoded by
     *    [EventParser] which buckets malformed lines into
     *    [TermxEvent.Unknown] rather than throwing.
     *  - The inner [flow] completes when stdout completes (remote tail
     *    killed) or throws when `openExec` / the transport fails.
     *
     * Reconnect:
     *  - [retryWhen] restarts the inner flow after any non-[CancellationException]
     *    failure with a 1 s backoff; the failing cause is surfaced to
     *    [errors] so UI layers can show a transient banner. Cancellation
     *    by the collector is the only clean exit.
     *
     * Backpressure:
     *  - A [buffer] sits between the emitter and the collector with
     *    [BufferOverflow.DROP_OLDEST]. Capacity is sized so that a slow
     *    UI consumer cannot stall the tail — preferring to drop old
     *    heartbeats over blocking the SSH producer is the right trade-off
     *    on a mobile notification path. See [STREAM_BUFFER_SLOTS] for the
     *    cap derivation.
     */
    fun stream(): Flow<TermxEvent> = flow {
        val exec = session.openExec(TAIL_COMMAND)
        try {
            val buffer = NdjsonBuffer()
            exec.stdout.collect { chunk ->
                val text = chunk.toString(Charsets.UTF_8)
                for (line in buffer.append(text)) {
                    parser.decodeLine(line)?.let { emit(it) }
                }
            }
            buffer.flushRemaining()?.let { tail ->
                parser.decodeLine(tail)?.let { emit(it) }
            }
        } finally {
            runCatching { exec.close() }
        }
    }.retryWhen { cause, _ ->
        if (cause is CancellationException) return@retryWhen false
        _errors.tryEmit(cause)
        delay(RECONNECT_DELAY_MS)
        true
    }.buffer(capacity = STREAM_BUFFER_SLOTS, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * One-shot read of the session registry in `~/.termx/sessions/`.
     *
     * Returns a best-effort list: files that fail to parse are dropped
     * silently (with the error reported to [errors]) rather than failing
     * the whole call. The common case is tab-bar refresh, where a single
     * corrupt file shouldn't blank out the UI.
     */
    suspend fun loadSessionRegistry(): List<SessionState> = withContext(Dispatchers.IO) {
        val home = resolveHomeDir()
        val dir = "$home/.termx/sessions"
        val sftp = session.openSftp()
        try {
            val names = runCatching { sftp.list(dir) }.getOrElse {
                _errors.tryEmit(it)
                return@withContext emptyList()
            }.filter { it.endsWith(".json") }

            names.mapNotNull { filename ->
                runCatching {
                    val bytes = sftp.read("$dir/$filename")
                    REGISTRY_JSON.decodeFromString<SessionState>(
                        bytes.toString(Charsets.UTF_8),
                    )
                }.onFailure { _errors.tryEmit(it) }.getOrNull()
            }
        } finally {
            runCatching { sftp.close() }
        }
    }

    /**
     * Drop a [CompanionCommand] into `~/.termx/commands/<id>.json`.
     *
     * Writes are atomic from the reader's point of view: the payload
     * lands in a uniquely-named temp sibling first, then SFTP rename
     * publishes it under the canonical filename. termxd polls the
     * commands dir for `<id>.json` files, so it can only ever observe
     * a fully-written payload — never a torn mid-write read. See
     * [writeAtomic] for the full protocol.
     */
    suspend fun sendCommand(cmd: CompanionCommand) = withContext(Dispatchers.IO) {
        val home = resolveHomeDir()
        val path = "$home/.termx/commands/${cmd.id}.json"
        val bytes = COMMAND_JSON
            .encodeToString(CompanionCommand.serializer(), cmd)
            .toByteArray(Charsets.UTF_8)
        val sftp = session.openSftp()
        try {
            sftp.writeAtomic(path, bytes)
        } finally {
            runCatching { sftp.close() }
        }
    }

    /**
     * Resolve the authenticated user's `$HOME` and cache it for the
     * lifetime of the client. sshj's SFTP layer doesn't expand `~`, so
     * every SFTP path that originates from the phone has to go through
     * an absolute path — and the canonical absolute path depends on
     * which user we authenticated as on the remote.
     *
     * We use a `printf` rather than `echo` to avoid a trailing newline
     * being baked into the path. One-time cost: ~20 ms on a warm session.
     */
    private suspend fun resolveHomeDir(): String {
        cachedHome?.let { return it }
        return homeMutex.withLock {
            cachedHome?.let { return it }
            val exec = session.openExec("printf %s \"\$HOME\"")
            val resolved = try {
                val sb = StringBuilder()
                exec.stdout.collect { chunk -> sb.append(chunk.toString(Charsets.UTF_8)) }
                exec.exitCode.await()
                sb.toString().trim().ifEmpty {
                    throw IllegalStateException("Remote \$HOME resolved to empty string")
                }
            } finally {
                runCatching { exec.close() }
            }
            cachedHome = resolved
            resolved
        }
    }

    /**
     * No resources of our own to release — the [SshSession] belongs to
     * the caller. Defined only so callers can treat the client
     * uniformly with other `AutoCloseable` objects.
     */
    override fun close() {
        // no-op
    }

    private companion object {
        // `tail -F` (capital F) handles rotation: if termxd moves the log
        // aside, tail will reopen the new file by name rather than stick
        // to the rotated inode. `--lines=0` ensures we only see new
        // events after subscribe — historic backfill is a different API.
        const val TAIL_COMMAND = "tail -F --lines=0 ~/.termx/events.ndjson"
        const val RECONNECT_DELAY_MS = 1_000L

        // Cap the buffered event payload at roughly 1 MiB: a typical
        // event is ~256 bytes of JSON, and 4096 slots gives us ~1 MiB
        // of head-room before [BufferOverflow.DROP_OLDEST] starts
        // discarding the oldest entry to make room for new ones.
        const val STREAM_BUFFER_SLOTS = 4096

        // Forgiving decoder: a new termxd release can add fields without
        // breaking older phones.
        val REGISTRY_JSON: Json = Json {
            ignoreUnknownKeys = true
        }

        // Strict encoder with a `type` discriminator so the Go side sees
        // the same wire format it emits for events.
        val COMMAND_JSON: Json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
        }
    }
}
