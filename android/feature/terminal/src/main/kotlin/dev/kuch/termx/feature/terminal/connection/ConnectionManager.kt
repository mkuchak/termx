package dev.kuch.termx.feature.terminal.connection

import android.content.Context
import android.util.Log
import com.termux.terminal.MoshRemoteTerminalSession
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSession
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.remote.CompanionUpdateRepository
import dev.kuch.termx.core.data.remote.EventStreamRepository
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.core.data.session.ReconnectBroker
import dev.kuch.termx.core.data.session.SessionRegistry
import dev.kuch.termx.core.data.vault.SecretVault
import dev.kuch.termx.core.data.vault.VaultLockedException
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.feature.terminal.BuildConfig
import dev.kuch.termx.feature.terminal.SshSessionClient
import dev.kuch.termx.feature.terminal.buildStartupCommand
import dev.kuch.termx.libs.companion.writeAtomic
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshConnectResult
import dev.kuch.termx.libs.sshnative.MoshFailureReason
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import java.io.FileNotFoundException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thrown by `resolveConnection` when the selected server uses password auth
 * but no password is cached yet. The `connect()` flow catches this
 * specifically and surfaces a prompt dialog instead of an error banner.
 */
class PasswordRequiredException(
    val serverId: UUID,
    val serverLabel: String,
) : Exception("Password required for $serverLabel")

/**
 * Per-connection transport lifecycle, the manager-side replacement for
 * the status/error/awaitingPassword fields that used to live flattened
 * inside `TerminalUiState`. `TerminalViewModel` maps this back into
 * `TerminalUiState` for the screen; future consumers (the Moshi-style
 * home's live session cards) read it directly.
 */
sealed interface TransportState {
    /** A connect attempt is in flight. */
    data object Connecting : TransportState

    /**
     * The server uses password auth and neither the vault nor the
     * in-memory cache had a value — the UI should prompt. Carries the
     * PERSISTED server id (what `submitPassword` needs back) plus the
     * label for the dialog title.
     */
    data class AwaitingPassword(
        val serverId: UUID,
        val serverLabel: String,
    ) : TransportState

    /**
     * Transport is up. [session] is the emulator-bearing
     * [RemoteTerminalSession]/[MoshRemoteTerminalSession] the screen
     * binds its `TerminalView` to. [moshBacked] + [transportFallbackReason]
     * carry the truthful-transport surfacing (Tasks #27/#35): reason is
     * non-null only when the server requested mosh but the connection
     * came up over plain SSH.
     */
    data class Connected(
        val session: TerminalSession,
        val moshBacked: Boolean,
        val transportFallbackReason: String?,
    ) : TransportState

    /** Connect failed (or mosh-client died at startup); [message] is user-facing. */
    data class Error(val message: String) : TransportState

    /** No transport. Initial state, post-disconnect state, post-remote-exit state. */
    data object Disconnected : TransportState
}

/**
 * The single open shell of one connection. [outputJob] collects the
 * transport's byte stream into [emulator] until the channel ends or we
 * cancel it; tearing the session down cancels the job and closes the
 * transport handle.
 *
 * Either [pty] (sshj PTY) or [moshSession] (local mosh-client
 * process) is non-null — never both, never neither.
 *
 * [serverIdForRegistry] + [serverLabel] mirror what we pushed into
 * [SessionRegistry] on open so the matching unregister call can be
 * made from `cleanupQuietly` without another repo hit.
 */
internal data class SessionPty(
    val name: String,
    val pty: PtyChannel?,
    val moshSession: MoshSession?,
    val emulator: TerminalSession,
    val outputJob: Job,
    val serverIdForRegistry: UUID,
    val serverLabel: String,
) {
    init {
        require((pty == null) xor (moshSession == null)) {
            "SessionPty needs exactly one of pty or moshSession"
        }
    }
}

/**
 * One server's connection slot, keyed by [serverId] in
 * [ConnectionManager.connections].
 *
 * The slot is LONG-LIVED: it is created on the first `connect()` for a
 * server and reused across reconnects, so its [state] /[writeErrors]/
 * [transportNotices] flows are stable references a screen (or the
 * future home's session card) can collect once and keep. The live
 * transport handles below are swapped per attempt and nulled on
 * teardown.
 *
 * [serverId] is the REGISTRY id: the persisted `Server.id` when one
 * exists, or [ConnectionManager.FALLBACK_SERVER_ID] for the BuildConfig
 * test-server path — either way the [SessionRegistry] (and the
 * foreground service's notification) sees a consistent key.
 */
class TermxConnection internal constructor(
    val serverId: UUID,
) {
    internal val mutableState = MutableStateFlow<TransportState>(TransportState.Disconnected)

    /** Transport lifecycle for this server. See [TransportState]. */
    val state: StateFlow<TransportState> = mutableState.asStateFlow()

    /**
     * Transient PTY-write failures surfaced to the UI as a snackbar.
     * Pre-v1.1.13 these were swallowed by `runCatching` in
     * `writeToPty` — the user's keystrokes vanished into a stale SSH
     * connection with no feedback. Now we emit a one-line message
     * here; TerminalScreen (via the VM bridge) collects + shows a
     * snackbar with a "Reconnect" action.
     *
     * `extraBufferCapacity = 1` + DROP_OLDEST so a flurry of failures
     * coalesces into one snackbar instead of stacking, and we never
     * suspend the IO coroutine just to deliver an error.
     */
    internal val mutableWriteErrors = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val writeErrors: SharedFlow<String> = mutableWriteErrors.asSharedFlow()

    /**
     * One-shot transport notices, currently only "Connected via SSH —
     * mosh unavailable: <reason>" when a connect attempt that wanted
     * mosh landed on plain SSH instead. TerminalScreen (via the VM
     * bridge) collects this into a snackbar. Same one-slot DROP_OLDEST
     * shape as [mutableWriteErrors]: emitting never suspends the
     * connect path, and a reconnect storm coalesces into a single
     * message.
     */
    internal val mutableTransportNotices = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val transportNotices: SharedFlow<String> = mutableTransportNotices.asSharedFlow()

    /**
     * Cached display label for the active connection, used to tag the
     * session pushed into [SessionRegistry]. Refreshed from the Room row
     * on every connect.
     */
    var serverLabel: String = "termx"
        internal set

    /**
     * Persisted server id (null on the BuildConfig test-server path) —
     * what the Room-touching detached jobs (companion update offer,
     * lastConnected stamp) act against.
     */
    internal var persistedServerId: UUID? = null

    /** The single open shell for this connection, or null when down. */
    internal var shell: SessionPty? = null

    /** The primary sshj session. Null on the mosh-backed path. */
    internal var sshSession: SshSession? = null

    /**
     * Best-effort SECOND SSH connection opened only on the mosh-backed
     * path (see [ConnectionManager.startMoshSideChannel]). mosh's own
     * bootstrap SSH is closed unconditionally inside `MoshClientImpl`
     * and exposes no handle, so a mosh session has no live exec channel
     * of its own to tail `events.ndjson` over. This dedicated side
     * connection gives the event-stream + UnifiedPush-sync machinery
     * the live [SshSession] it needs while the terminal itself stays on
     * mosh. null on the plain-SSH path and whenever the side connect
     * hasn't landed (or failed — it is strictly best-effort).
     */
    internal var sideSession: SshSession? = null

    internal var connectJob: Job? = null
}

/**
 * Process-wide owner of every live SSH/mosh transport — the
 * "Server-ownership refactor" the foreground service KDoc has been
 * pointing at. Owns the live [SshSession] (or [MoshSession]) plus the
 * single [PtyChannel] backing each connection's plain login shell;
 * `TerminalViewModel` is now a thin binder that delegates here and maps
 * [TermxConnection.state] into `TerminalUiState`.
 *
 * Each connection is one [SshSession] (or one [MoshSession]) = one
 * shell = one [SessionPty] holder. Closing the session via
 * `cleanupQuietly()` implicitly closes the [PtyChannel] under it; the
 * [SessionPty] holds the [RemoteTerminalSession] + emulator bound to
 * the on-screen `TerminalView`.
 *
 * SCOPE LIFETIME = PROCESS. [scope] is a plain
 * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` that is never
 * cancelled in production — connections outlive any screen/ViewModel.
 * SupervisorJob so one connection's pump dying can't take down its
 * siblings.
 *
 * LIFECYCLE SEMANTICS (Task #43 flip): a connection STARTS on
 * [connect] — server-row tap, password submit, snackbar/ErrorPane
 * retry, or the notification Reconnect action (via [ReconnectBroker])
 * — and ENDS only on:
 *
 *  - an explicit [disconnect] (the terminal screen's Disconnect button),
 *  - the notification "Disconnect all" action
 *    ([SessionRegistry.disconnectAllRequest] → [disconnectAll]),
 *  - the remote shell exiting (EOF / transport death → `onShellFinished`),
 *  - a failed connect attempt (→ [TransportState.Error]).
 *
 * Leaving the terminal screen, navigating home, or backgrounding the
 * app does NOT touch the transport — minimize-style semantics. The
 * [SessionRegistry] entry persists with the connection, which keeps
 * `TermxForegroundService` (and its Tier-1 event router) up while any
 * session is live, even with no terminal UI on screen. Both notification
 * collectors therefore live HERE (see `init`), not in a ViewModel —
 * they must work with zero screens alive.
 *
 * KNOWN BOUNDARY — PROCESS DEATH IS NOT SURVIVED. Swipe-kill, OOM kill,
 * or force-stop drops every transport; there is no resurrection on the
 * next launch (the "process-death session resurrection" roadmap item).
 * No Doze-special engineering either: the existing 30s sshj keepalive
 * (`SshConnector`) is the mitigation that keeps an idle link alive
 * through short app-standby windows, and mosh-backed sessions tolerate
 * roaming/idle natively.
 *
 * THREADING: the connect pipeline is launched on
 * `Dispatchers.Main.immediate` — NOT the scope's default dispatcher —
 * because the vendored Termux `TerminalSession` constructor creates a
 * no-arg `Handler()` bound to the constructing thread's looper
 * (TerminalSession.java:96); `openTab`/`openMoshTab` must therefore run
 * on the main thread, exactly as they did under `viewModelScope`. Byte
 * pumps and PTY writes hop to `Dispatchers.IO`, also as before.
 */
@Singleton
class ConnectionManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val keyPairRepository: KeyPairRepository,
    private val secretVault: SecretVault,
    private val passwordCache: PasswordCache,
    private val alertPreferences: AlertPreferences,
    private val sessionRegistry: SessionRegistry,
    private val reconnectBroker: ReconnectBroker,
    private val eventStreamHub: EventStreamHub,
    private val eventStreamRepository: EventStreamRepository,
    private val companionUpdateRepository: CompanionUpdateRepository,
    private val moshClient: MoshClient,
    private val sshClient: SshClient,
) {

    /**
     * Process-lifetime scope; never cancelled in production (see class
     * KDoc). `internal` so unit tests can cancel it in teardown and not
     * leak pumps/timeouts into the next test class's swapped Main
     * dispatcher.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connections = MutableStateFlow<Map<UUID, TermxConnection>>(emptyMap())

    init {
        // Notification "Disconnect all" action. Used to be collected by
        // every live TerminalViewModel (each tearing down its own
        // session); with manager-owned lifecycles it must work with NO
        // screen alive, so the single collector lives here for the
        // whole process. Main.immediate to keep teardown on the same
        // thread the connect pipeline uses (and that the old VM
        // collector ran on).
        scope.launch(Dispatchers.Main.immediate) {
            sessionRegistry.disconnectAllRequest.collect {
                disconnectAll()
            }
        }
        // Notification "Reconnect" action (posted on `termx.disconnect`
        // notifications by ReconnectActionReceiver → ReconnectBroker).
        // Keyed by registry server id; the all-zeros sentinel maps back
        // to a null persisted id so the BuildConfig test-server path
        // redials correctly too. Must work with no terminal screen
        // alive — the disconnect notification usually IS the only UI.
        scope.launch(Dispatchers.Main.immediate) {
            reconnectBroker.requests.collect { serverId ->
                disconnect(serverId)
                connect(serverId.takeUnless { it == FALLBACK_SERVER_ID })
            }
        }
    }

    /**
     * Every connection slot ever created this process, keyed by registry
     * server id. Slots persist after disconnect (state
     * [TransportState.Disconnected]) so per-connection flows stay stable
     * across reconnects; consumers that only want LIVE connections filter
     * on [TermxConnection.state].
     */
    val connections: StateFlow<Map<UUID, TermxConnection>> = _connections.asStateFlow()

    /**
     * Start a connection for [serverId] (null = BuildConfig test-server
     * path), BIND-IF-ALIVE: when the slot is already live — Connecting,
     * AwaitingPassword, or Connected — the call returns the slot
     * UNTOUCHED. No second transport is dialed, no state is reset, and
     * a Connected slot keeps the exact same emulator instance, so
     * re-entering the terminal screen for a live server rebinds the
     * view to the existing session (scrollback and all) instantly.
     * Only a Disconnected/Error slot dials fresh.
     *
     * Returns the server's [TermxConnection] slot so the caller can
     * bind its flows synchronously — on the fresh-dial path the
     * Connecting transition has already happened by the time this
     * returns, so a binder never observes the slot's stale pre-connect
     * state.
     */
    fun connect(serverId: UUID?): TermxConnection {
        val conn = slotFor(serverId ?: FALLBACK_SERVER_ID)
        val s = conn.state.value
        if (s is TransportState.Connecting ||
            s is TransportState.Connected ||
            s is TransportState.AwaitingPassword
        ) {
            return conn
        }

        conn.connectJob?.cancel()
        conn.mutableState.value = TransportState.Connecting
        // Main.immediate, not the scope's Default: openTab/openMoshTab
        // construct TerminalSession subclasses whose base-class field
        // initializer needs the current thread to own a Looper. See the
        // THREADING note in the class KDoc.
        conn.connectJob = scope.launch(Dispatchers.Main.immediate) {
            runCatching { openSession(conn, serverId) }
                .onFailure { t ->
                    if (t is PasswordRequiredException) {
                        conn.mutableState.value = TransportState.AwaitingPassword(
                            serverId = t.serverId,
                            serverLabel = t.serverLabel,
                        )
                        return@onFailure
                    }
                    Log.e(LOG_TAG, "connect failed", t)
                    cleanupQuietly(conn)
                    conn.mutableState.value = TransportState.Error(
                        t.message ?: "connection failed",
                    )
                }
        }
        return conn
    }

    /**
     * Invoked (via the VM) by the terminal's password prompt dialog.
     * Caches the password in-memory for this process's lifetime,
     * persists it into the vault (self-healing — see below), and retries
     * [connect] for the same server.
     *
     * Persisting here — not only in the Add/Edit sheet — is what stops
     * the "app asks for the password on every restart" case. Without
     * this call the password lives only in [PasswordCache], which dies
     * with the process; the prompt then fires again on every cold
     * start even though the user already answered it yesterday.
     *
     * Self-heal: older versions of [AddEditServerViewModel.save]
     * incorrectly nuked `server.passwordAlias` on any edit with a
     * blank password field (fixed in the same commit as this
     * function). For users whose Room rows still carry that damage,
     * we mint a fresh `"password-$serverId"` alias on the fly, write
     * the password under it, AND upsert the server row so future
     * cold-start `resolveConnection` paths pick it up. The prompt
     * never reappears after a single successful submission.
     *
     * Drops an [TransportState.AwaitingPassword] state back to
     * Disconnected BEFORE retrying: [connect] treats AwaitingPassword
     * as live (bind-if-alive) and would otherwise return the prompt
     * slot untouched instead of dialing with the fresh password.
     */
    fun submitPassword(serverId: UUID, password: String): TermxConnection {
        passwordCache.put(serverId, password)
        _connections.value[serverId]?.mutableState?.update {
            if (it is TransportState.AwaitingPassword) TransportState.Disconnected else it
        }
        scope.launch(Dispatchers.IO) {
            val server = runCatching { serverRepository.getById(serverId) }.getOrNull()
                ?: return@launch
            val alias = server.passwordAlias ?: "password-$serverId"
            val storeResult = runCatching {
                secretVault.store(alias, password.toByteArray(Charsets.UTF_8))
            }
            storeResult.onFailure {
                Log.w(LOG_TAG, "persisting prompted password failed", it)
            }
            if (storeResult.isSuccess && server.passwordAlias != alias) {
                runCatching {
                    serverRepository.upsert(server.copy(passwordAlias = alias))
                }.onFailure {
                    Log.w(LOG_TAG, "heal-updating server.passwordAlias failed", it)
                }
            }
        }
        return connect(serverId)
    }

    /** User cancelled the password prompt — just drop back to Disconnected. */
    fun cancelPasswordPrompt(serverId: UUID) {
        _connections.value[serverId]?.mutableState?.update {
            if (it is TransportState.AwaitingPassword) TransportState.Disconnected else it
        }
    }

    /** Clear a one-shot connect error once the UI has rendered it. */
    fun clearError(serverId: UUID) {
        _connections.value[serverId]?.mutableState?.update {
            if (it is TransportState.Error) TransportState.Disconnected else it
        }
    }

    /**
     * Forward raw bytes to [serverId]'s shell PTY. Used by the
     * extra-keys toolbar and the Volume-Down=Ctrl binding (via the VM).
     */
    fun writeToPty(serverId: UUID, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val conn = _connections.value[serverId] ?: return
        val shell = conn.shell ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell.pty?.write(bytes)
                shell.moshSession?.write(bytes)
            }.onFailure { t ->
                Log.w(LOG_TAG, "pty write failed", t)
                // Best-effort UI signal: don't suspend here, just emit.
                conn.mutableWriteErrors.tryEmit(
                    "Send failed — connection may have dropped.",
                )
            }
        }
    }

    /**
     * Tear [serverId]'s connection down. Idempotent — a second call (or
     * a call with nothing live) finds no transport handles and no-ops.
     * An [TransportState.Error]/[TransportState.AwaitingPassword] state
     * survives the transition (matching the old VM behavior where
     * `disconnect()` kept the `error`/`awaitingPassword` fields), so an
     * error pane / prompt isn't dismissed by a background disconnect.
     */
    fun disconnect(serverId: UUID) {
        val conn = _connections.value[serverId] ?: return
        conn.connectJob?.cancel()
        conn.connectJob = null
        cleanupQuietly(conn)
        conn.mutableState.update {
            if (it is TransportState.Error || it is TransportState.AwaitingPassword) {
                it
            } else {
                TransportState.Disconnected
            }
        }
    }

    /**
     * Tear down EVERY connection — the notification "Disconnect all"
     * action (collected from [SessionRegistry.disconnectAllRequest] in
     * `init`). Emptying the registry as a side effect is what makes
     * `TermxForegroundService` stop itself. Per-slot [disconnect] is
     * idempotent, so already-dead slots in the map are no-ops.
     */
    fun disconnectAll() {
        _connections.value.keys.forEach { disconnect(it) }
    }

    @Synchronized
    private fun slotFor(serverId: UUID): TermxConnection {
        _connections.value[serverId]?.let { return it }
        val conn = TermxConnection(serverId)
        _connections.update { it + (serverId to conn) }
        return conn
    }

    private suspend fun openSession(conn: TermxConnection, serverId: UUID?) {
        conn.persistedServerId = serverId
        val (target, auth) = resolveConnection(serverId)
        val server = serverId?.let { serverRepository.getById(it) }
        // conn.serverId (the slot key) already equals
        // `server?.id ?: FALLBACK_SERVER_ID` — registry/hub keys keep the
        // exact shape the VM minted. Only the label needs the Room row.
        conn.serverLabel = server?.label ?: "Test server"

        // Optional "run a command on connect" — same wire string for both
        // transports (SSH execs it with a PTY; the mosh path appends it
        // after `mosh-server new … --`). null when disabled/blank, which
        // falls back to a plain login shell on either path.
        val startup = buildStartupCommand(
            server?.startupCommandEnabled == true,
            server?.startupCommand ?: "",
        )

        // Phase 3 mosh race. If the Server row opts into mosh, try the
        // mosh-server handshake first with an 8s cap; on any failure we
        // fall through to the sshj PTY path below WITHIN THE SAME connect
        // attempt ("mosh first, then ssh automatically"), remembering a
        // short human-readable reason so the UI can say why.
        var moshFallbackReason: String? = null
        if (server?.useMosh == true) {
            val result = runCatching {
                moshClient.tryConnectDetailed(
                    target = target,
                    auth = auth,
                    handshakeTimeoutMs = MOSH_HANDSHAKE_TIMEOUT_MS,
                    startupCommand = startup,
                )
            }.onFailure { Log.w(LOG_TAG, "mosh tryConnectDetailed threw", it) }
                .getOrElse { t ->
                    MoshConnectResult.Failed(
                        MoshFailureReason.Other(t.message ?: "mosh connect error"),
                    )
                }

            when (result) {
                is MoshConnectResult.Success -> {
                    // LIVENESS GATE: a successful handshake only proves the
                    // `MOSH CONNECT` line crossed the SSH/TCP channel. When
                    // UDP 60000-60010 is firewalled on the VPS — the single
                    // most common real-world mosh failure — mosh-client
                    // spawns fine and then waits forever for datagrams that
                    // never arrive; pre-gate the app reported success on a
                    // dead connection. Require first remote output bytes
                    // within [MOSH_FIRST_OUTPUT_TIMEOUT_MS] before declaring
                    // the transport live. The deferred is completed by the
                    // byte pump's first emission (observation only — bytes
                    // continue to the emulator untouched).
                    val firstOutput = CompletableDeferred<Unit>()
                    val shell = openMoshTab(conn, result.session, firstOutput)
                    val live = withTimeoutOrNull(MOSH_FIRST_OUTPUT_TIMEOUT_MS) {
                        firstOutput.await()
                    } != null
                    if (live) {
                        touchLastConnected(server.id)
                        conn.mutableState.value = TransportState.Connected(
                            session = shell.emulator,
                            moshBacked = true,
                            transportFallbackReason = null,
                        )
                        // mosh is up AND verifiably alive. Only now open the
                        // DEDICATED, best-effort side SSH connection so the
                        // event-stream router can tail `events.ndjson` and
                        // the UnifiedPush endpoint gets synced — neither of
                        // which the mosh transport can carry (its bootstrap
                        // SSH is already closed). Deliberately ordered AFTER
                        // the liveness gate so a fallback never has to tear
                        // down a half-started side channel. Must never block
                        // or fail the terminal: fully fire-and-forget. See
                        // [startMoshSideChannel].
                        startMoshSideChannel(conn, target, auth)
                        return
                    }
                    Log.i(
                        LOG_TAG,
                        "mosh produced no output within ${MOSH_FIRST_OUTPUT_TIMEOUT_MS}ms " +
                            "(UDP likely filtered); falling back to ssh",
                    )
                    teardownMoshShellForFallback(conn, shell)
                    moshFallbackReason = FALLBACK_REASON_NO_UDP
                }
                is MoshConnectResult.Failed -> {
                    moshFallbackReason = describeMoshFailure(result.reason)
                    Log.i(LOG_TAG, "mosh handshake failed ($moshFallbackReason); falling back to ssh")
                }
            }
        }

        // Primary login transport goes through the injected [sshClient].
        // `connect` builds a fresh SSHClient under the hood, so reusing
        // the same instance for the (best-effort) mosh side channel is
        // safe — the injection is purely a unit-test seam.
        val sshSession = sshClient.connect(target, auth)
        conn.sshSession = sshSession

        // Publish the live session so `EventNotificationRouter` (and any
        // future consumer) can tail `events.ndjson` without coordinating
        // with this manager directly. Re-publishing is idempotent — hub
        // state is keyed by server id so reconnecting replaces the prior
        // entry.
        eventStreamHub.publish(
            serverId = conn.serverId,
            serverLabel = conn.serverLabel,
            client = eventStreamRepository.clientFor(sshSession),
        )

        // Tier-2 push: write the phone's UnifiedPush endpoint to
        // `~/.termx/ntfy-endpoint` so the VPS watcher knows where to POST
        // agent-done. UnifiedPush registration can land with no live
        // session, so we (re)write on every SSH connect — it's idempotent.
        // Best-effort: launched off the connect path and fully swallowing
        // failures so a slow/unwritable VPS can never block or break the
        // session coming up.
        syncUnifiedPushEndpoint(sshSession)

        // OPT-2 (Task #32): best-effort on-connect companion update offer.
        // Like the endpoint sync above this is fully detached and swallows
        // every failure — it only ever drives the CompanionUpdateRepository
        // StateFlow (the server-list banner), never the terminal transport.
        maybeOfferCompanionUpdate(serverId, sshSession)

        val shell = openTab(conn, attachCommand = startup)
        server?.id?.let { touchLastConnected(it) }
        conn.mutableState.value = TransportState.Connected(
            session = shell.emulator,
            moshBacked = false,
            transportFallbackReason = moshFallbackReason,
        )
        if (moshFallbackReason != null) {
            // One-shot surfacing of the silent-until-now mosh→SSH
            // fallback; the persistent subtitle is driven by
            // [TransportState.Connected.transportFallbackReason] above.
            conn.mutableTransportNotices.tryEmit(
                "Connected via SSH — mosh unavailable: $moshFallbackReason",
            )
        }
    }

    /**
     * Map a classified [MoshFailureReason] onto the short human string
     * shown in the fallback subtitle + snackbar. The liveness-gate
     * failure ([FALLBACK_REASON_NO_UDP]) is mapped at its call site —
     * it is detected here in the manager, not by the handshake
     * classifier.
     */
    private fun describeMoshFailure(reason: MoshFailureReason): String = when (reason) {
        MoshFailureReason.MissingUtf8Locale -> "VPS missing UTF-8 locale"
        MoshFailureReason.MoshServerMissing -> "mosh-server not installed"
        MoshFailureReason.HandshakeTimeout -> "handshake timeout"
        is MoshFailureReason.Other -> reason.detail.take(120).ifBlank { "mosh failed to start" }
    }

    /**
     * Tear down a mosh shell that failed the first-output liveness gate
     * so the SAME connect attempt can continue down the sshj path.
     * Mirror of [onShellFinished] minus the state write — `openSession`
     * still owns the Connecting → Connected transition, and no hub entry
     * exists yet because [startMoshSideChannel] is ordered after the
     * gate.
     *
     * ACCEPTED TRADE: the mosh-server we started keeps running on the
     * VPS, orphaned. We deliberately do NOT reach back over SSH to
     * pkill it — a pattern-based kill could take down the user's other
     * live mosh sessions (other devices, other terminals). Stock
     * mosh-server exits on its own after ~60s with no first client
     * contact, so the orphan is short-lived.
     */
    private fun teardownMoshShellForFallback(conn: TermxConnection, shell: SessionPty) {
        conn.shell = null
        shell.outputJob.cancel()
        runCatching { shell.moshSession?.close() }
        notifyTabEmulatorFinished(shell.emulator)
        sessionRegistry.unregister(shell.serverIdForRegistry, shell.name)
    }

    /**
     * Open a DEDICATED, best-effort side SSH connection for the
     * mosh-backed path and wire it into the event-stream + UnifiedPush
     * machinery.
     *
     * Why this exists: when the terminal comes up over mosh, there is no
     * live [SshSession] to tail `~/.termx/events.ndjson` over. mosh's own
     * bootstrap SSH is closed unconditionally by `MoshClientImpl` and the
     * resulting [MoshSession] exposes no SSH handle, so agent-finished /
     * idle / permission notifications and the Tier-2 UnifiedPush endpoint
     * sync would never happen on the DEFAULT (mosh) path. A second,
     * independent SSH connection — cheap, `connect` builds a fresh
     * SSHClient — gives the session-agnostic [EventStreamClient] (which
     * has its own `retryWhen` reconnect) and [syncUnifiedPushEndpoint] the
     * live session they need.
     *
     * Strictly best-effort and fully detached on [scope]: it must NEVER
     * block or break the mosh terminal coming up. A failed connect is
     * swallowed to [Log.w]; the terminal stays Connected+moshBacked,
     * just without Tier-1 notifications.
     *
     * KNOWN LIMITATION (deliberate follow-up — do not fix here): this side
     * SSH is not as roam-resilient as mosh. [EventStreamClient.retryWhen]
     * recovers exec-level hiccups (log-rotate, a killed stray `tail`) but
     * not full transport death, so after a network flap the side
     * connection can go quiet until the next explicit reconnect. A
     * supervised relaunch of this channel is a planned follow-up.
     */
    private fun startMoshSideChannel(conn: TermxConnection, target: SshTarget, auth: SshAuth) {
        // Persisted server id (null on the BuildConfig test-server path) —
        // captured here so the detached companion check below targets the
        // right Room row even if a reconnect swaps `persistedServerId`
        // meanwhile.
        val persistedServerId = conn.persistedServerId
        scope.launch(Dispatchers.Main.immediate) {
            val session = runCatching { sshClient.connect(target, auth) }
                .onFailure { Log.w(LOG_TAG, "mosh side-channel SSH connect failed", it) }
                .getOrNull() ?: return@launch

            // Torn-down-while-connecting guard. The mosh connect could have
            // raced a disconnect() (or a reconnect) while this side SSH was
            // still handshaking. If the mosh shell is gone, close the
            // orphan and bail WITHOUT publishing — otherwise we'd leak an
            // entry that cleanupQuietly() already ran past. (The old VM
            // also compared `currentServerRegistryId` against the id it
            // captured at launch; connections are now keyed per server, so
            // [conn] IS that comparison.)
            if (conn.shell?.moshSession == null) {
                runCatching { session.close() }
                return@launch
            }

            conn.sideSession = session
            eventStreamHub.publish(
                serverId = conn.serverId,
                serverLabel = conn.serverLabel,
                client = eventStreamRepository.clientFor(session),
            )
            syncUnifiedPushEndpoint(session)
            // OPT-2 (Task #32): same best-effort companion update offer as the
            // plain-SSH path, run over this dedicated side session so it fires
            // on the mosh transport too.
            maybeOfferCompanionUpdate(persistedServerId, session)
        }
    }

    /**
     * Fire-and-forget write of `lastConnected = now()` for [serverId].
     * Runs off the UI-state-update path so a slow/failing Room write can't
     * block surfacing "Connected" — the server card on the list just keeps
     * its prior stamp (or "never connected") if the write drops.
     */
    private fun touchLastConnected(serverId: UUID) {
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { serverRepository.updateLastConnected(serverId, Instant.now()) }
                .onFailure { Log.w(LOG_TAG, "updateLastConnected failed for $serverId", it) }
        }
    }

    /**
     * Best-effort sync of the UnifiedPush (Tier 2) endpoint to the VPS.
     *
     * The VPS watcher reads `~/.termx/ntfy-endpoint` to learn where to POST
     * the agent-done push. UnifiedPush registration on the phone can happen
     * with no live SSH session, so the canonical moment to publish the
     * endpoint is here, on each connect — the write is idempotent.
     *
     * Mechanics mirror the companion write path exactly (see
     * `EventStreamClient.resolveHomeDir` / `sendCommand`): resolve `$HOME`
     * with `printf %s "$HOME"` because sshj's SFTP layer doesn't expand
     * `~`, ensure `~/.termx` exists, then publish the endpoint via the
     * shared atomic temp-file-plus-rename [writeAtomic] so the watcher
     * never observes a torn read.
     *
     * Strictly best-effort: it runs on a detached [scope] launch and
     * swallows every failure to [Log.w]. Nothing here may block or fail
     * the connection — a missing companion dir, an unwritable home, or a
     * dropped SFTP channel just leaves the prior endpoint file in place.
     */
    private fun syncUnifiedPushEndpoint(session: SshSession) {
        scope.launch(Dispatchers.Main.immediate) {
            runCatching {
                if (!alertPreferences.unifiedPushEnabled.first()) return@launch
                val endpoint = alertPreferences.unifiedPushEndpoint.first()
                if (endpoint.isBlank()) return@launch

                val home = resolveRemoteHome(session)
                val dir = "$home/.termx"
                // Ensure the parent dir exists so the atomic rename has a
                // home even on a VPS where the companion isn't installed yet.
                runCatching {
                    val mkdir = session.openExec("mkdir -p \"$dir\"")
                    try {
                        mkdir.exitCode.await()
                    } finally {
                        runCatching { mkdir.close() }
                    }
                }
                val sftp = session.openSftp()
                try {
                    sftp.writeAtomic("$dir/ntfy-endpoint", endpoint.toByteArray(Charsets.UTF_8))
                } finally {
                    runCatching { sftp.close() }
                }
            }.onFailure { Log.w(LOG_TAG, "UnifiedPush endpoint sync failed", it) }
        }
    }

    /**
     * Best-effort, NON-INTRUSIVE on-connect companion (termxd) update offer —
     * OPT-2 (Task #32). The setup wizard is otherwise the only place that
     * checks the VPS-side companion version, so an already-configured server
     * never learns about a companion update; this surfaces a one-tap
     * "Install / Later" offer on reconnect.
     *
     * Mechanics mirror [syncUnifiedPushEndpoint] EXACTLY: a fully detached
     * [scope] launch that swallows every failure to [Log.w] and NEVER
     * touches the connection's transport state. The result is pushed only
     * through [CompanionUpdateRepository]'s StateFlow, which the server-list
     * banner observes. The repo itself gates on a per-server 24h TTL + the
     * per-(server, tag) skip memory, so reconnects in the window don't
     * re-probe and dismissed offers stay quiet.
     *
     * No-op on the BuildConfig test-server path ([serverId] is null) — there's
     * no persisted Server row for the install use-case to act against.
     */
    private fun maybeOfferCompanionUpdate(serverId: UUID?, session: SshSession) {
        if (serverId == null) return
        scope.launch(Dispatchers.Main.immediate) {
            runCatching { companionUpdateRepository.maybeOfferUpdate(serverId, session) }
                .onFailure { Log.w(LOG_TAG, "companion update check failed", it) }
        }
    }

    /**
     * Resolve the authenticated user's `$HOME` on [session]. sshj's SFTP
     * layer doesn't expand `~`, so every phone-originated SFTP path has to
     * go through an absolute path; `printf` (not `echo`) avoids baking a
     * trailing newline into it. Mirrors `EventStreamClient.resolveHomeDir`.
     */
    private suspend fun resolveRemoteHome(session: SshSession): String {
        val exec = session.openExec("printf %s \"\$HOME\"")
        return try {
            val sb = StringBuilder()
            exec.stdout.collect { chunk -> sb.append(chunk.toString(Charsets.UTF_8)) }
            exec.exitCode.await()
            sb.toString().trim().ifEmpty {
                throw IllegalStateException("Remote \$HOME resolved to empty string")
            }
        } finally {
            runCatching { exec.close() }
        }
    }

    /**
     * The remote shell exited (user typed `exit`, transport dropped).
     * Tear down the WHOLE transport and transition to Disconnected so
     * the UI can prompt a reconnect.
     *
     * GUARDED BY IDENTITY, not by null: the finish callback is a
     * main-looper post ([RemoteTerminalSession.onRemoteSessionClosed]),
     * so it can land AFTER the shell it belongs to was already replaced
     * by a newer one on the same long-lived slot — mosh→ssh liveness
     * fallback ([teardownMoshShellForFallback] closes the dead mosh
     * shell, then `openSession` installs the ssh shell in the SAME
     * attempt) and disconnect→reconnect have exactly this shape. A bare
     * `conn.shell == null` check passed for the NEW shell and let the
     * stale callback tear down a freshly connected transport (caught by
     * ConnectionManagerBehaviorTest's liveness-fallback case landing on
     * Disconnected under full-suite load). Comparing the finished
     * session against the CURRENT shell's emulator makes stale
     * callbacks no-ops while keeping the late-EOF-after-disconnect
     * idempotency (shell is null → identity check fails → return).
     *
     * FULL teardown via [cleanupQuietly] — not just the PTY/mosh handle
     * — is a Task #44 fix. The pre-#44 code (inherited verbatim from the
     * old TerminalViewModel) closed only `shell.pty`/`shell.moshSession`
     * here, leaving [TermxConnection.sshSession]/[TermxConnection.sideSession]
     * open and the [EventStreamHub] entry published: after `exit` the
     * app held an idle sshj connection (keepalive thread + TCP) that no
     * registry entry or foreground service knew about, and the next
     * `connect()` — state is Disconnected, so it dials fresh —
     * overwrote `conn.sshSession` in [openSession] WITHOUT closing the
     * old one, leaking one transport per EOF→reconnect cycle. (The
     * notification-Reconnect path was immune only because [disconnect]
     * runs first there.) [cleanupQuietly] also keeps the load-bearing
     * hub-unpublish-BEFORE-session-close order on this path
     * (PROJECT_KNOWLEDGE §17 item 15).
     */
    private fun onShellFinished(conn: TermxConnection, finished: TerminalSession) {
        if (conn.shell?.emulator !== finished) return
        cleanupQuietly(conn)
        // Keep an Error written by the mosh early-exit post-mortem (the
        // pump's diagnostic branch) — under the old flat UiState the
        // `error` field survived this transition because the copy only
        // touched status/activeSession.
        conn.mutableState.update {
            if (it is TransportState.Error) it else TransportState.Disconnected
        }
    }

    /**
     * Open the shell PTY, wiring bytes in both directions into a fresh
     * [RemoteTerminalSession], and stash it as [TermxConnection.shell].
     * Caller updates transport state. [attachCommand] is the optional
     * remote command to exec instead of the login shell — always null on
     * the plain shell path today.
     */
    private suspend fun openTab(conn: TermxConnection, attachCommand: String?): SessionPty {
        val sshSession = conn.sshSession ?: throw IllegalStateException("No live ssh session")
        val channel = sshSession.openShell(
            cols = INITIAL_COLS,
            rows = INITIAL_ROWS,
            command = attachCommand,
        )

        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = { finished ->
                // Remote shell exited (user typed `exit`, transport
                // dropped). Tear the session down — identity-guarded
                // against stale posts (see onShellFinished).
                onShellFinished(conn, finished)
            },
        )
        val emulator = RemoteTerminalSession(
            client = sessionClient,
            onInputBytes = { bytes ->
                scope.launch(Dispatchers.IO) {
                    runCatching { channel.write(bytes) }
                        .onFailure { Log.w(LOG_TAG, "pty write failed", it) }
                }
            },
            onResize = { cols, rows ->
                scope.launch(Dispatchers.IO) {
                    runCatching { channel.resize(cols, rows) }
                        .onFailure { Log.w(LOG_TAG, "pty resize failed", it) }
                }
            },
        )

        // Byte pump now runs in the manager's process-lifetime scope (was
        // viewModelScope). Thread-safety holds: `feedRemoteBytes` posts
        // every append to the main-looper handler internally, so emulator
        // mutation stays on the main thread no matter which dispatcher
        // collects the flow. Suspending `send` semantics end to end —
        // never `trySend`; frame drops corrupt full-screen TUI repaints.
        val outputJob = scope.launch(Dispatchers.IO) {
            runCatching {
                channel.output.collect { chunk ->
                    emulator.feedRemoteBytes(chunk)
                }
            }.onFailure { t -> Log.w(LOG_TAG, "pty output ended", t) }
            emulator.onRemoteSessionClosed()
        }

        val shell = SessionPty(
            name = DEFAULT_TAB,
            pty = channel,
            moshSession = null,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = conn.serverId,
            serverLabel = conn.serverLabel,
        )
        conn.shell = shell
        sessionRegistry.register(
            serverId = shell.serverIdForRegistry,
            serverLabel = shell.serverLabel,
            tabName = shell.name,
        )
        return shell
    }

    /**
     * Wire a live [MoshSession] into the single shell.
     *
     * Resize + keypress bytes go straight into mosh-client's stdin /
     * SIGWINCH path. Output is piped from the child process stdout
     * into the emulator by [SessionPty.outputJob].
     *
     * [firstOutput] is completed on the pump's FIRST emission — the
     * liveness signal `openSession` gates the mosh→Connected transition
     * on. Pure observation: completing a [CompletableDeferred] is
     * idempotent and the chunk continues into the emulator untouched.
     * The flow keeps its suspending-`send` semantics end to end (never
     * `trySend` — frame drops corrupt full-screen TUI repaints; see the
     * load-bearing comments in `MoshSessionImpl`/`PtyChannelImpl`).
     */
    private fun openMoshTab(
        conn: TermxConnection,
        session: MoshSession,
        firstOutput: CompletableDeferred<Unit>,
    ): SessionPty {
        val sessionClient = SshSessionClient(
            context = appContext,
            onSessionFinished = { finished ->
                // Identity-guarded: a dead mosh shell's finish post must
                // not tear down the ssh shell that replaced it (see
                // onShellFinished).
                onShellFinished(conn, finished)
            },
        )
        val emulator = MoshRemoteTerminalSession(
            client = sessionClient,
            onInputBytes = { bytes ->
                scope.launch(Dispatchers.IO) {
                    runCatching { session.write(bytes) }
                        .onFailure { Log.w(LOG_TAG, "mosh write failed", it) }
                }
            },
            onResize = { cols, rows ->
                scope.launch(Dispatchers.IO) {
                    runCatching { session.resize(cols, rows) }
                        .onFailure { Log.w(LOG_TAG, "mosh resize failed", it) }
                }
            },
        )

        // Same scope/threading contract as the sshj pump in [openTab]:
        // manager scope, Dispatchers.IO, feedRemoteBytes posts to main.
        val outputJob = scope.launch(Dispatchers.IO) {
            runCatching {
                session.output.collect { chunk ->
                    // Liveness signal first (idempotent no-op after the
                    // first chunk), then the byte continues to the
                    // emulator — observe, never consume.
                    firstOutput.complete(Unit)
                    emulator.feedRemoteBytes(chunk)
                }
            }.onFailure { t -> Log.w(LOG_TAG, "mosh output ended", t) }
            emulator.onRemoteSessionClosed()
            // mosh-client stdout EOF ⇒ the child process either exited
            // cleanly (user typed `exit` on the remote) or died during
            // startup. Distinguish via the diagnostic snapshot: a
            // non-zero code within the early-exit window means we
            // should tell the user *why* rather than silently dropping
            // them on a useless Reconnect button.
            val diag = session.diagnostic.value
            if (diag.exitCode != null && diag.exitCode != 0 && diag.elapsedMs < MOSH_EARLY_EXIT_WINDOW_MS) {
                val reason = extractReadableReason(diag.head)
                conn.mutableState.value = TransportState.Error(
                    "mosh-client exited after ${diag.elapsedMs}ms (exit ${diag.exitCode}): $reason",
                )
            }
        }

        val shell = SessionPty(
            name = DEFAULT_TAB,
            pty = null,
            moshSession = session,
            emulator = emulator,
            outputJob = outputJob,
            serverIdForRegistry = conn.serverId,
            serverLabel = conn.serverLabel,
        )
        conn.shell = shell
        sessionRegistry.register(
            serverId = shell.serverIdForRegistry,
            serverLabel = shell.serverLabel,
            tabName = shell.name,
        )
        return shell
    }

    /**
     * Pull the most useful single line out of mosh-client's captured
     * stderr head. ncurses prints `Error opening terminal: ...` or the
     * classic `Cannot find termcap entry for ...`; mosh itself prints
     * things like `mosh-client: ...`. We prefer a line matching any of
     * those markers, falling back to the first non-empty line, then to
     * a size-capped flattened form.
     */
    private fun extractReadableReason(head: String): String {
        if (head.isBlank()) return "no output captured"
        val lines = head.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val preferred = lines.firstOrNull { line ->
            line.contains("termcap", ignoreCase = true) ||
                line.contains("terminal", ignoreCase = true) ||
                line.startsWith("mosh", ignoreCase = true) ||
                line.contains("error", ignoreCase = true)
        }
        val best = preferred ?: lines.firstOrNull() ?: head.replace('\n', ' ').trim()
        return best.take(200)
    }

    /**
     * Decide which host + credentials to use.
     *
     * For a non-null [serverId] we resolve the Server row and (for
     * key auth) unlock the private bytes from the vault. Missing rows,
     * missing keys, or a locked vault throw — the outer
     * `runCatching` in [connect] turns them into a user-facing error.
     *
     * For a null [serverId] we fall back to the Phase 1 env-var path.
     */
    private suspend fun resolveConnection(serverId: UUID?): Pair<SshTarget, SshAuth> {
        val knownHostsPath = appContext.filesDir.absolutePath + "/known_hosts"

        if (serverId != null) {
            val server = serverRepository.getById(serverId)
                ?: throw IllegalStateException("Server not found")

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
                        throw IllegalStateException("Vault is locked — unlock to connect", t)
                    } ?: throw IllegalStateException("Private key missing from vault")
                    SshAuth.PublicKey(privateKeyPem = bytes, passphrase = null)
                }
                AuthType.PASSWORD -> {
                    // Vault first, in-memory cache second. Vault wins so a
                    // cold-start reconnect uses the persisted password; the
                    // cache only matters for unsaved wizard drafts or
                    // password-entered-but-not-yet-saved flows.
                    // VaultLockedException is treated as "missing" — the
                    // PasswordRequiredException path re-prompts, and the
                    // app's biometric unlock flow handles the rest.
                    val fromVault = server.passwordAlias?.let { alias ->
                        runCatching { secretVault.load(alias) }
                            .getOrNull()
                            ?.let { bytes -> String(bytes, Charsets.UTF_8) }
                    }
                    val password = fromVault
                        ?: passwordCache.get(server.id)
                        ?: throw PasswordRequiredException(server.id, server.label)
                    SshAuth.Password(password)
                }
            }
            return target to auth
        }

        val host = BuildConfig.TEST_SERVER_HOST
        val user = BuildConfig.TEST_SERVER_USER
        val port = BuildConfig.TEST_SERVER_PORT
        if (host.isBlank() || user.isBlank()) {
            throw IllegalStateException("No test server configured")
        }
        val keyBytes = readTestKey()
            ?: throw IllegalStateException("No test server configured")
        val target = SshTarget(host = host, port = port, username = user, knownHostsPath = knownHostsPath)
        val auth = SshAuth.PublicKey(privateKeyPem = keyBytes, passphrase = null)
        return target to auth
    }

    private fun cleanupQuietly(conn: TermxConnection) {
        conn.shell?.let { shell ->
            shell.outputJob.cancel()
            runCatching { shell.pty?.close() }
            runCatching { shell.moshSession?.close() }
            notifyTabEmulatorFinished(shell.emulator)
            sessionRegistry.unregister(shell.serverIdForRegistry, shell.name)
        }
        conn.shell = null
        // Drop the hub entry before closing the session so any router
        // subscriber cancels its collection first and doesn't get a
        // transient exec failure on the way out. This single unpublish
        // covers the mosh path too — the side channel publishes under the
        // same registry key.
        eventStreamHub.unpublish(conn.serverId)
        runCatching { conn.sshSession?.close() }
        conn.sshSession = null
        // Tear down the mosh side channel (no-op on the plain-SSH path
        // where it was never opened). Closing the session ends the
        // EventStreamClient's tail exec for free.
        runCatching { conn.sideSession?.close() }
        conn.sideSession = null
    }

    /**
     * Both [RemoteTerminalSession] and [MoshRemoteTerminalSession]
     * expose [onRemoteSessionClosed] but don't share a common base
     * method for it — the parent [TerminalSession] doesn't declare the
     * hook. A tiny when-branch keeps the call site in [onShellFinished] /
     * [cleanupQuietly] readable without forcing a shared mini-interface.
     */
    private fun notifyTabEmulatorFinished(emulator: TerminalSession) {
        when (emulator) {
            is RemoteTerminalSession -> emulator.onRemoteSessionClosed()
            is MoshRemoteTerminalSession -> emulator.onRemoteSessionClosed()
        }
    }

    private fun readTestKey(): ByteArray? = runCatching {
        appContext.assets.open(TEST_KEY_ASSET).use { it.readBytes() }
    }.getOrElse { t ->
        if (t is FileNotFoundException) null else throw t
    }

    companion object {
        private const val LOG_TAG = "ConnectionManager"
        private const val TEST_KEY_ASSET = "test-key.pem"
        private const val DEFAULT_TAB = "shell"
        private const val INITIAL_COLS = 80
        private const val INITIAL_ROWS = 24

        /**
         * Phase 3 mosh race window. If `mosh-server new` on the VPS
         * doesn't print `MOSH CONNECT <port> <key>` within this budget,
         * we abandon the UDP path and fall back to sshj. 8 seconds
         * covers a fresh VPS cold-start plus firewall pinhole-probe.
         */
        const val MOSH_HANDSHAKE_TIMEOUT_MS: Long = 8_000L

        /**
         * Liveness gate: the mosh session must produce its FIRST remote
         * output bytes within this window after a successful handshake.
         *
         * WHY 3s: mosh-server pushes the initial screen state immediately
         * on first UDP contact, so a healthy link delivers bytes within
         * roughly one round-trip — 3 seconds is generous. WHY the gate
         * exists: the `MOSH CONNECT` line travels over SSH/TCP, so the
         * handshake "succeeds" even when UDP 60000-60010 is firewalled on
         * the VPS (the most common real-world mosh failure); the local
         * mosh-client then waits forever and the user got a frozen
         * terminal that claimed to be connected. The 2s early-exit window
         * in `MoshSessionImpl` only catches process EXITS — this catches
         * hangs.
         */
        const val MOSH_FIRST_OUTPUT_TIMEOUT_MS: Long = 3_000L

        /**
         * Fallback reason shown when the liveness gate trips: handshake
         * OK over TCP, zero bytes over UDP. Names the exact port range
         * because opening it is the fix in almost every case.
         */
        const val FALLBACK_REASON_NO_UDP =
            "no UDP response — check firewall: allow 60000-60010/udp"

        /**
         * Window within which a mosh-client exit is treated as a
         * startup failure rather than a normal user-initiated exit.
         * Matches the companion in `MoshSessionImpl` so the log and
         * the UI post-mortem agree on what counts as "immediate".
         */
        const val MOSH_EARLY_EXIT_WINDOW_MS: Long = 2_000L

        /**
         * Stable sentinel UUID for BuildConfig test-server connections
         * that have no persisted [dev.kuch.termx.core.domain.model.Server]
         * row. Keeps the [SessionRegistry] key shape uniform.
         */
        val FALLBACK_SERVER_ID: UUID =
            UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
