package dev.kuch.termx.libs.companion

import dev.kuch.termx.libs.companion.events.ApprovalResponse
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
 *    (network flap, log-rotate, a killed stray `tail`), until the
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
 *    free. For the SFTP-based calls ([loadSessionRegistry],
 *    [respondToApproval], [appendAllowlistRule], [sendCommand]) we need
 *    absolute paths (sshj's SFTP client doesn't expand `~`), so the client
 *    caches the resolved `$HOME` the first time one of those calls runs.
 *    See [resolveHomeDir].
 *
 * Open for tests only: feature modules fake this client with hand-rolled
 * subclasses (the repo convention — no mock library), so the class and the
 * members those fakes override are `open`.
 */
open class EventStreamClient(
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
    open fun stream(): Flow<TermxEvent> = flow {
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
     * One-shot read of `~/.termx/diffs/<diffId>.json`.
     *
     * Returned bytes are the raw JSON payload termxd's PostToolUse hook
     * wrote; the caller deserializes into a diff view model. Not folded
     * into a typed `Diff` model here because the phone's diff viewer
     * tolerates missing/extra fields independently of the event-stream
     * schema cadence.
     */
    suspend fun loadDiff(diffId: String): ByteArray = withContext(Dispatchers.IO) {
        val home = resolveHomeDir()
        val path = "$home/.termx/diffs/$diffId.json"
        val sftp = session.openSftp()
        try {
            sftp.read(path)
        } finally {
            runCatching { sftp.close() }
        }
    }

    /**
     * Resolve a pending PreToolUse permission request by writing
     * `~/.termx/approvals/<requestId>.res.json` — the exact file
     * `termx _hook-pretooluse` (termxd/cmd/hook_pretooluse.go) polls
     * every 100 ms while it blocks Claude, default-denying after 30 s.
     *
     * Wire schema: [ApprovalResponse] — field names locked to the Go
     * `approvalResponse` struct; see that class's KDoc for the contract
     * and `src/test/resources/approvals-golden/` for the byte-exact
     * fixtures.
     *
     * The write goes through [writeAtomic] (unique temp sibling + SFTP
     * rename). That atomicity is load-bearing: the hook's poll loop
     * stat+reads the canonical path on a timer, and rename-in-place is
     * the only reason it can never observe a torn, half-written JSON —
     * it either sees nothing or the complete payload.
     *
     * "Always approve" callers pass [ApprovalResponse.Decision.ALLOW]
     * here (the hook treats anything other than approve/allow as deny
     * and persists nothing) and separately call [appendAllowlistRule]
     * to make the approval stick for future invocations.
     */
    open suspend fun respondToApproval(
        requestId: String,
        decision: ApprovalResponse.Decision,
        reason: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        val home = resolveHomeDir()
        val path = "$home/.termx/approvals/$requestId.res.json"
        val payload = RESPONSE_JSON.encodeToString(
            ApprovalResponse.serializer(),
            ApprovalResponse(decision = decision, reason = reason),
        )
        // Trailing newline matches termxd's own writeJSONAtomic convention
        // (and keeps `cat` output tidy when debugging on the VPS).
        val bytes = (payload + "\n").toByteArray(Charsets.UTF_8)
        val sftp = session.openSftp()
        try {
            sftp.writeAtomic(path, bytes)
        } finally {
            runCatching { sftp.close() }
        }
    }

    /**
     * Append a regex rule to `~/.termx/allowlist.txt` so future tool
     * calls matching `<tool_name>|<cmd-or-path>` bypass the broker (the
     * matcher is `allowlistMatches` in termxd/cmd/hook_pretooluse.go;
     * one regex per line, `#` comments and blank lines ignored).
     *
     * Read-modify-write: fetch the current file (missing file == empty),
     * no-op if [pattern] is already present (idempotent — a double-tap on
     * "Always approve" must not duplicate rules), otherwise re-publish the
     * whole file via [writeAtomic] so the hook never reads a torn file.
     *
     * The Go side persists nothing on approval, so this phone-side append
     * is the ONLY persistence behind "Always approve". Callers treat it
     * as best-effort: the decision write ([respondToApproval]) is the
     * critical half and must not be gated on this one succeeding.
     */
    open suspend fun appendAllowlistRule(pattern: String): Unit = withContext(Dispatchers.IO) {
        val home = resolveHomeDir()
        val path = "$home/.termx/allowlist.txt"
        val sftp = session.openSftp()
        try {
            val current = runCatching { sftp.read(path).toString(Charsets.UTF_8) }
                .getOrDefault("") // missing file → start fresh
            val alreadyPresent = current.lineSequence().any { it.trim() == pattern }
            if (alreadyPresent) return@withContext
            val updated = buildString {
                append(current)
                if (current.isNotEmpty() && !current.endsWith("\n")) append('\n')
                append(pattern)
                append('\n')
            }
            sftp.writeAtomic(path, updated.toByteArray(Charsets.UTF_8))
        } finally {
            runCatching { sftp.close() }
        }
    }

    /**
     * Drop a [CompanionCommand] into `~/.termx/commands/<id>.json`.
     *
     * ⚠ Nothing on the VPS consumes this directory today — termxd never
     * grew a commands poller (`CommandsDir` is only ever mkdir'd). The
     * live permission path is [respondToApproval], which writes the
     * `.res.json` the PreToolUse hook actually polls. This method and the
     * [CompanionCommand] schema are retained for forward-compat with a
     * future server-side consumer (repo convention: decisions are
     * superseded, not erased).
     *
     * Writes are atomic from the reader's point of view: the payload
     * lands in a uniquely-named temp sibling first, then SFTP rename
     * publishes it under the canonical filename, so any future consumer
     * can only ever observe a fully-written payload — never a torn
     * mid-write read. See [writeAtomic] for the full protocol.
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

        // Encoder for approval responses. Deliberately the default Json:
        // `encodeDefaults = false` drops a null `reason`, matching the Go
        // struct's `json:"reason,omitempty"` (hook_pretooluse.go) so both
        // sides agree byte-for-byte on the no-reason payload.
        val RESPONSE_JSON: Json = Json
    }
}
