package dev.kuch.termx.core.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.TmuxSession
import dev.kuch.termx.core.domain.repository.ExecResult
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.core.domain.repository.TmuxSessionRepository
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * `tmux ls`-backed implementation of [TmuxSessionRepository].
 *
 * Responsibilities:
 *
 *  1. Reuse a single [SshSession] per server (opening sshj is expensive
 *     — 150–400 ms even on a LAN). Sessions close automatically after
 *     60 s of idleness (no active [observeSessions] collectors), so a
 *     one-off tab-bar refresh doesn't leave a lingering transport.
 *  2. Poll `tmux ls` at a cadence that respects the foreground hint
 *     from [dev.kuch.termx.core.data.prefs.AppForegroundTracker]:
 *     30 s when the app is foregrounded, 5 min when backgrounded.
 *  3. Bolt on a Claude/node heuristic via `tmux list-panes -a` — one
 *     exec per poll, batched in Kotlin across all sessions.
 *  4. Expose failures (e.g. `tmux` not installed) via the [errors]
 *     SharedFlow so the UI can show a banner without the Flow itself
 *     terminating.
 *
 * Not responsible for:
 *  - auth'ing the session (that's `ServerRepository` + `SecretVault`)
 *  - rendering tabs (Task #26)
 *  - starting new tmux sessions (Task #26 will add a small helper)
 */
@Singleton
class TmuxSessionRepositoryImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val sshClient: SshClient,
) : TmuxSessionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class Cache(
        val session: SshSession,
        val mutex: Mutex = Mutex(),
        @Volatile var refCount: Int = 0,
        @Volatile var idleCloseJob: Job? = null,
    )

    private val caches = ConcurrentHashMap<UUID, Cache>()
    private val cacheMutex = Mutex()

    private val _errors = MutableSharedFlow<TmuxError>(
        replay = 0,
        extraBufferCapacity = 8,
    )
    val errors: SharedFlow<TmuxError> = _errors.asSharedFlow()

    override fun observeSessions(
        serverId: UUID,
        foregroundHint: Flow<Boolean>,
    ): Flow<List<TmuxSession>> = callbackFlow {
        acquireCache(serverId)
        val pollJob = launch {
            while (true) {
                val list = runCatching { refresh(serverId) }
                    .onFailure { t ->
                        if (t is TmuxError) _errors.tryEmit(t)
                        else _errors.tryEmit(TmuxError.TransportFailure(serverId, t))
                    }
                    .getOrElse { emptyList() }
                trySend(list)

                // Collect the current foreground value to pick the delay.
                // `first()` on a StateFlow resolves immediately, so switching
                // foreground/background flips the cadence at the *next* tick
                // rather than interrupting the current one — acceptable for
                // a 30 s / 5 min window.
                val isFg = runCatching { foregroundHint.first() }.getOrDefault(true)
                val nextInterval = if (isFg) FOREGROUND_POLL_MS else BACKGROUND_POLL_MS
                delay(nextInterval)
            }
        }
        awaitClose {
            pollJob.cancel()
            releaseCache(serverId)
        }
    }.distinctUntilChanged()

    override suspend fun refresh(serverId: UUID): List<TmuxSession> {
        val cache = acquireCache(serverId)
        try {
            return cache.mutex.withLock { runTmuxPoll(serverId, cache) }
        } finally {
            releaseCache(serverId)
        }
    }

    override suspend fun exec(serverId: UUID, cmd: String): ExecResult {
        val cache = acquireCache(serverId)
        try {
            return cache.mutex.withLock {
                cache.session.openExec(cmd).use { exec ->
                    val (out, err) = drainBoth(exec)
                    val code = exec.exitCode.await()
                    ExecResult(exitCode = code, stdout = out, stderr = err)
                }
            }
        } finally {
            releaseCache(serverId)
        }
    }

    private suspend fun runTmuxPoll(serverId: UUID, cache: Cache): List<TmuxSession> {
        val lsOutput = cache.session.openExec(LS_COMMAND).use { exec ->
            val (stdout, _) = drainBoth(exec)
            val exit = exec.exitCode.await()
            if (exit == 127) {
                throw TmuxError.TmuxNotFound(serverId)
            }
            if (exit != 0) {
                // No sessions = tmux exits 1 with "no server running"; treat
                // as empty rather than error.
                return emptyList()
            }
            stdout
        }
        val sessions = parseLsOutput(lsOutput)
        if (sessions.isEmpty()) return emptyList()

        // Claude detection: one exec for *all* panes across all sessions.
        val panesOutput = runCatching {
            cache.session.openExec(PANES_COMMAND).use { exec ->
                val (stdout, _) = drainBoth(exec)
                exec.exitCode.await()
                stdout
            }
        }.getOrDefault("")
        val sessionsWithClaude = panesOutput.lineSequence()
            .mapNotNull { line ->
                // Format: `<session>|<pane_current_command>`
                val idx = line.indexOf('|')
                if (idx < 0) null
                else line.substring(0, idx) to line.substring(idx + 1)
            }
            .filter { (_, cmd) ->
                val lower = cmd.lowercase()
                lower.contains("claude") || lower.contains("node")
            }
            .map { it.first }
            .toSet()

        return sessions.map { it.copy(claudeDetected = it.name in sessionsWithClaude) }
    }

    private fun parseLsOutput(raw: String): List<TmuxSession> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size < 4) return@mapNotNull null
                val name = parts[0]
                val activitySec = parts[1].toLongOrNull() ?: return@mapNotNull null
                val attached = parts[2] == "1"
                val windows = parts[3].toIntOrNull() ?: 0
                TmuxSession(
                    name = name,
                    activity = Instant.ofEpochSecond(activitySec),
                    attached = attached,
                    windowCount = windows,
                )
            }
            .toList()

    // -----------------------------------------------------------------
    // SshSession cache lifecycle
    // -----------------------------------------------------------------

    private suspend fun acquireCache(serverId: UUID): Cache = cacheMutex.withLock {
        val existing = caches[serverId]
        if (existing != null) {
            existing.refCount += 1
            existing.idleCloseJob?.cancel()
            existing.idleCloseJob = null
            return existing
        }
        val fresh = openSession(serverId)
        val cache = Cache(session = fresh, refCount = 1)
        caches[serverId] = cache
        cache
    }

    private fun releaseCache(serverId: UUID) {
        // Dispatch the actual release onto our scope so we can safely
        // take `cacheMutex` — this entry point is called from the
        // `callbackFlow.awaitClose` block (non-suspend) and from
        // `refresh`'s `finally` (suspend); scheduling on the scope is
        // uniform and keeps refCount arithmetic race-free with
        // `acquireCache`.
        scope.launch {
            cacheMutex.withLock {
                val cache = caches[serverId] ?: return@withLock
                cache.refCount -= 1
                if (cache.refCount > 0) return@withLock
                // Schedule a delayed close — a tab swap often releases
                // then re-acquires in quick succession; keep the
                // transport warm.
                cache.idleCloseJob?.cancel()
                cache.idleCloseJob = scope.launch {
                    delay(IDLE_CLOSE_MS)
                    cacheMutex.withLock {
                        val current = caches[serverId]
                        if (current === cache && current.refCount <= 0) {
                            caches.remove(serverId)
                            runCatching { current.session.close() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun openSession(serverId: UUID): SshSession = withContext(Dispatchers.IO) {
        val server = serverRepository.getById(serverId)
            ?: throw IllegalStateException("Server not found: $serverId")
        val knownHostsPath = appContext.filesDir.absolutePath + "/known_hosts"
        val target = SshTarget(
            host = server.host,
            port = server.port,
            username = server.username,
            knownHostsPath = knownHostsPath,
        )
        val auth: SshAuth = when (server.authType) {
            AuthType.KEY -> {
                val keyPairId = server.keyPairId
                    ?: throw IllegalStateException("Server has no key assigned")
                val keyPair = keyPairRepository.getById(keyPairId)
                    ?: throw IllegalStateException("Linked key not found")
                val bytes = try {
                    secretVault.load(keyPair.keystoreAlias)
                } catch (t: VaultLockedException) {
                    throw IllegalStateException("Vault is locked", t)
                } ?: throw IllegalStateException("Private key missing from vault")
                SshAuth.PublicKey(privateKeyPem = bytes, passphrase = null)
            }
            AuthType.PASSWORD -> throw IllegalStateException(
                "Password auth isn't wired yet",
            )
        }
        sshClient.connect(target, auth)
    }

    /**
     * Drain an [ExecChannel]'s stdout AND stderr concurrently, returning both
     * as UTF-8 strings. sshj gives each stream its own flow-control window;
     * a sequential drain (stdout to EOF, then stderr) can wedge the remote
     * process if it writes enough stderr to fill that window before its stdout
     * closes. All poll commands redirect stderr with `2>/dev/null` today,
     * but this keeps the contract safe regardless of caller hygiene.
     */
    private suspend fun drainBoth(exec: dev.kuch.termx.libs.sshnative.ExecChannel): Pair<String, String> =
        coroutineScope {
            val out = StringBuilder()
            val err = StringBuilder()
            val stdoutJob = launch {
                exec.stdout.collect { chunk -> out.append(String(chunk, Charsets.UTF_8)) }
            }
            val stderrJob = launch {
                exec.stderr.collect { chunk -> err.append(String(chunk, Charsets.UTF_8)) }
            }
            stdoutJob.join()
            stderrJob.join()
            out.toString() to err.toString()
        }

    private companion object {
        const val FOREGROUND_POLL_MS = 30_000L
        const val BACKGROUND_POLL_MS = 5L * 60_000L
        const val IDLE_CLOSE_MS = 60_000L

        // `-F` format string mirrors what §3 of the roadmap specifies.
        const val LS_COMMAND =
            "tmux ls -F '#{session_name}|#{session_activity}|#{session_attached}|#{session_windows}' 2>/dev/null"

        // `-a` walks panes across every session; formatting the session
        // name on each line lets us group in Kotlin without a second
        // round-trip per session.
        const val PANES_COMMAND =
            "tmux list-panes -a -F '#{session_name}|#{pane_current_command}' 2>/dev/null"
    }
}

