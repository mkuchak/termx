package dev.kuch.termx.feature.terminal.connection

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.remote.EventStreamRepository
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.core.data.session.ReconnectBroker
import dev.kuch.termx.core.data.session.SessionRegistry
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.feature.terminal.fakes.FakeKeyPairRepository
import dev.kuch.termx.feature.terminal.fakes.FakeMoshClient
import dev.kuch.termx.feature.terminal.fakes.FakeMoshSession
import dev.kuch.termx.feature.terminal.fakes.FakeSecretVault
import dev.kuch.termx.feature.terminal.fakes.FakeServerRepository
import dev.kuch.termx.feature.terminal.fakes.FakeSshClient
import dev.kuch.termx.feature.terminal.fakes.FakeSshSession
import dev.kuch.termx.feature.terminal.fakes.QuiescentMainDispatcherRule
import dev.kuch.termx.libs.companion.EventStreamClient
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.PtyChannel
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Task #44: the dedicated behavioral suite for the ownership refactor
 * (Tasks #42/#43) — the regressions this guards against (leaked
 * transports, wrong teardown order, dead reconnect) are exactly the
 * silent kind this repo has been bitten by before. It complements,
 * never duplicates, [ConnectionManagerTest] (plain-SSH manager
 * contracts (a)–(f)) and the two `TerminalViewModelMosh*Test` files
 * (mosh behavior pinned end-to-end THROUGH the VM):
 *
 *  (1) mosh success at the MANAGER level: slot Connected(moshBacked),
 *      side channel published under the SAME key the registry uses;
 *  (2) mosh liveness failure: same-attempt SSH fallback with the UDP
 *      reason ON THE SLOT, and EXACTLY ONE hub publication (no stray
 *      side channel left behind by the torn-down mosh attempt);
 *  (3) back-nav semantics on the MOSH path: the connection outlives
 *      any screen/binder; a rebind connect() hands back the SAME
 *      emulator instance with no second mosh race;
 *  (4) the password prompt → submit → vault-persist + alias-self-heal
 *      → Connected pipeline (nothing else covers submitPassword);
 *  (5) THE NEW CAPABILITY: two concurrent connections with independent
 *      slots — tearing one down leaves the other untouched;
 *  (6) writeToPty failure surfacing on writeErrors with the one-slot
 *      DROP_OLDEST coalescing contract;
 *  (7) remote-shell EOF: FULL transport teardown (the Task #44 leak
 *      fix) with the load-bearing unpublish-before-close order;
 *  (8) reconnect after EOF reuses the long-lived slot with a FRESH
 *      transport and no leak of the dead one.
 *
 * Setup matches the sibling suites: Robolectric + real-time polling
 * (byte pumps and the liveness wait live on Dispatchers.IO / real
 * delays, which a virtual clock can't drive), shared looper-backed
 * Main + scope quiescence via [QuiescentMainDispatcherRule]. UNLIKE
 * the siblings, [waitUntil] also idles the Robolectric MAIN looper:
 * the remote-EOF path posts `onSessionFinished` to
 * `Looper.getMainLooper()` (RemoteTerminalSession.onRemoteSessionClosed),
 * and under Robolectric's paused looper that post never runs while the
 * test thread busy-sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConnectionManagerBehaviorTest {

    @get:Rule
    val mainRule = QuiescentMainDispatcherRule()

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val serverId = UUID.fromString("55555555-5555-5555-5555-555555555555")

    @Before
    fun resetPrefs() = runBlocking {
        // DataStore files survive across tests in the same Robolectric
        // VM; keep UnifiedPush off so no test does endpoint writes.
        AlertPreferences(appContext).setUnifiedPushEnabled(false)
        AlertPreferences(appContext).setUnifiedPushEndpoint("")
    }

    private fun server(
        id: UUID = serverId,
        label: String = "prod-1",
        host: String = "vps.example",
        useMosh: Boolean = false,
    ) = Server(
        id = id,
        label = label,
        host = host,
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        keyPairId = null,
        passwordAlias = null,
        groupId = null,
        useMosh = useMosh,
        lastConnected = null,
        pingMs = null,
    )

    private fun newManager(
        servers: FakeServerRepository,
        hub: EventStreamHub,
        registry: SessionRegistry,
        sshClient: FakeSshClient,
        moshClient: MoshClient? = null,
        secretVault: FakeSecretVault = FakeSecretVault(),
        passwordCache: PasswordCache = PasswordCache().apply { put(serverId, "pw") },
        foregroundTracker: AppForegroundTracker = AppForegroundTracker(),
    ): ConnectionManager = ConnectionManager(
        appContext = appContext,
        serverRepository = servers,
        keyPairRepository = FakeKeyPairRepository(),
        secretVault = secretVault,
        passwordCache = passwordCache,
        alertPreferences = AlertPreferences(appContext),
        sessionRegistry = registry,
        reconnectBroker = ReconnectBroker(),
        eventStreamHub = hub,
        eventStreamRepository = EventStreamRepository(EventStreamClientFactory()),
        companionUpdateRepository = mockk(relaxed = true),
        moshClient = moshClient ?: FakeMoshClient(appContext, sshClient, session = null),
        sshClient = sshClient,
        appForegroundTracker = foregroundTracker,
    ).also {
        // Tiny so the no-first-output liveness-failure path falls back in
        // ~0.3s instead of burning the production 15s window in real time.
        it.firstOutputTimeoutMs = 300L
        mainRule.track(it)
    }

    /**
     * Busy-wait on a predicate, draining the Robolectric main looper on
     * every spin (see class KDoc — the EOF callback is a main-looper
     * post that would otherwise never run). Deadline is a generous 12s;
     * the liveness-failure case burns the real-time first-output window,
     * which these tests shrink to 300ms via [newManager]'s
     * `firstOutputTimeoutMs` override (production is 15s).
     */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** [EventStreamHub] that records every publish key for key-shape asserts. */
    private open class RecordingHub : EventStreamHub() {
        val publishes = CopyOnWriteArrayList<Pair<UUID, String>>()
        override fun publish(serverId: UUID, serverLabel: String, client: EventStreamClient) {
            publishes += serverId to serverLabel
            super.publish(serverId, serverLabel, client)
        }
    }

    // ── (1) mosh success: manager-level state + hub/registry KEY SHAPE ──
    //
    // Tier-1 already pins (through the VM) that a hub entry appears; what
    // nothing pinned is that the side channel publishes under the SAME
    // key the registry entry uses — the contract `cleanupQuietly`'s
    // single `unpublish(conn.serverId)` silently depends on. A drifted
    // key (e.g. a fresh UUID per side channel) would still pass the
    // Tier-1 "an entry exists" assert but leak the hub entry on every
    // teardown.
    @Test
    fun moshSuccess_slotConnectedMoshBacked_sideChannelPublishedUnderRegistryKey() {
        val servers = FakeServerRepository().apply { put(server(useMosh = true)) }
        val hub = RecordingHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val moshClient = FakeMoshClient(appContext, sshClient, FakeMoshSession())
        val manager = newManager(servers, hub, registry, sshClient, moshClient = moshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected && hub.publishes.isNotEmpty() }

        val connected = conn.state.value
        assertTrue("slot must be Connected, was $connected", connected is TransportState.Connected)
        assertTrue((connected as TransportState.Connected).moshBacked)
        assertNull(connected.transportFallbackReason)
        // THE key-shape contract: one publication, keyed exactly like the
        // registry entry (and the slot itself).
        assertEquals(listOf(serverId to "prod-1"), hub.publishes.toList())
        assertEquals(setOf(serverId to "shell"), registry.entries.value.keys)
        assertSame(conn, manager.connections.value[serverId])
        // The side channel is the only SSH dial on the mosh path.
        assertEquals(1, sshClient.connectCount.get())
    }

    // ── (2) mosh liveness failure (no first output within the window:
    //        a slow-cold-starting client or genuinely filtered UDP):
    //        fallback reason ON THE SLOT, exactly one hub publication ──
    //
    // The fallback suite pins the same scenario through the VM's mapped
    // uiState; this pins the manager's own slot state plus a hole in
    // that coverage: that the abandoned mosh attempt leaves NO stray
    // side-channel publication behind — only the ssh-path one exists.
    // (The production ordering guarantee is that the side channel starts
    // strictly AFTER the liveness gate passes.)
    @Test
    fun moshLivenessFailure_sameAttemptSshFallback_slotCarriesFallbackReason_noStrayPublication() {
        val servers = FakeServerRepository().apply { put(server(useMosh = true)) }
        val hub = RecordingHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val moshSession = FakeMoshSession(emitFirstOutput = false)
        val moshClient = FakeMoshClient(appContext, sshClient, moshSession)
        val manager = newManager(servers, hub, registry, sshClient, moshClient = moshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val connected = conn.state.value
        assertTrue("slot must be Connected, was $connected", connected is TransportState.Connected)
        assertFalse(
            "fallback connection must not be mosh-backed",
            (connected as TransportState.Connected).moshBacked,
        )
        assertEquals(ConnectionManager.FALLBACK_REASON_NO_FIRST_OUTPUT, connected.transportFallbackReason)
        // The copy-pasteable diagnostic blob is attached so the user can
        // retrieve the on-device linker/exit detail without adb (gotcha #32).
        assertNotNull("fallback must carry a mosh diagnostic blob", connected.transportFallbackDetail)
        assertTrue(connected.transportFallbackDetail!!.contains("termx mosh diagnostics"))
        // Same attempt: one mosh try, dead mosh session closed.
        assertEquals(1, moshClient.tryConnectCount.get())
        assertTrue("dead mosh session must be closed", moshSession.closed.get() >= 1)
        // EXACTLY the ssh-path publication — no side channel was started
        // for the abandoned mosh attempt, so exactly one dial too.
        assertEquals(listOf(serverId to "prod-1"), hub.publishes.toList())
        assertEquals(1, sshClient.connectCount.get())
        // And the registry holds only the ssh tab (no orphan mosh tab).
        assertEquals(setOf(serverId to "shell"), registry.entries.value.keys)
    }

    // ── (3) back-nav semantics on the MOSH path ──
    //
    // Task #43's whole point: the screen/VM going away does NOTHING to
    // the transport (minimize semantics) — there is no reference to
    // release and no call to make, which is itself the contract. A later
    // connect() (re-entering the terminal) must rebind to the same slot
    // and hand back the SAME emulator-bearing session instance without a
    // second mosh race or a duplicate side channel. ConnectionManagerTest
    // (d) pins this for plain SSH; the mosh path goes through entirely
    // different code (openMoshTab + startMoshSideChannel).
    @Test
    fun backNav_moshConnectionSurvivesScreenDeath_rebindYieldsSameEmulatorNoRedial() {
        val servers = FakeServerRepository().apply { put(server(useMosh = true)) }
        val hub = RecordingHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val moshClient = FakeMoshClient(appContext, sshClient, FakeMoshSession())
        val manager = newManager(servers, hub, registry, sshClient, moshClient = moshClient)

        val conn = manager.connect(serverId)
        // Wait for the side channel too so "no duplicate publish" below
        // can't pass vacuously by racing ahead of the first one.
        waitUntil { conn.state.value is TransportState.Connected && hub.publishes.size == 1 }
        val emulator = (conn.state.value as TransportState.Connected).session

        // "Screen goes away": deliberately nothing to do — no unbind
        // call exists and the manager holds the transport. The slot must
        // still be live and observable process-wide.
        assertSame(conn, manager.connections.value[serverId])
        assertTrue(conn.state.value is TransportState.Connected)

        // Re-entering the terminal screen = a second connect().
        val rebound = manager.connect(serverId)

        assertSame("rebind must return the same slot", conn, rebound)
        assertSame(
            "rebind must yield the SAME emulator instance (scrollback and all)",
            emulator,
            (rebound.state.value as TransportState.Connected).session,
        )
        assertEquals("no second mosh race", 1, moshClient.tryConnectCount.get())
        assertEquals("no second side channel", 1, sshClient.connectCount.get())
        assertEquals("no duplicate hub publication", 1, hub.publishes.size)
        assertEquals(setOf(serverId to "shell"), registry.entries.value.keys)
    }

    // ── (4) password prompt → submit → persist + self-heal → Connected ──
    //
    // submitPassword is otherwise untested. Two persistence contracts
    // ride on it: the prompted password must land in the vault under
    // `password-$serverId` (else the prompt reappears on every cold
    // start), and a Room row whose passwordAlias was nulled by the old
    // AddEditServer bug must be healed via upsert.
    @Test
    fun passwordFlow_promptsOnEmptyVaultAndCache_submitPersistsHealsAndConnects() {
        // passwordAlias = null models the damaged row; the helper's
        // default cache is replaced with an EMPTY one so resolveConnection
        // has nowhere to find a password.
        val servers = FakeServerRepository().apply { put(server()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val vault = FakeSecretVault()
        val manager = newManager(
            servers,
            hub,
            registry,
            sshClient,
            secretVault = vault,
            passwordCache = PasswordCache(),
        )

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.AwaitingPassword }

        val prompt = conn.state.value
        assertTrue("must prompt, was $prompt", prompt is TransportState.AwaitingPassword)
        assertEquals(serverId, (prompt as TransportState.AwaitingPassword).serverId)
        assertEquals("prod-1", prompt.serverLabel)
        assertEquals("no dial may happen without credentials", 0, sshClient.connectCount.get())

        val retried = manager.submitPassword(serverId, "hunter2")

        assertSame("retry must reuse the same slot", conn, retried)
        waitUntil { conn.state.value is TransportState.Connected }
        assertTrue(conn.state.value is TransportState.Connected)

        // Vault persistence under the minted alias (the IO write races
        // the reconnect — poll it).
        val alias = "password-$serverId"
        waitUntil { runBlocking { vault.exists(alias) } }
        assertArrayEquals(
            "prompted password must be persisted to the vault",
            "hunter2".toByteArray(Charsets.UTF_8),
            runBlocking { vault.load(alias) },
        )
        // Self-heal: the nulled passwordAlias row must be upserted with
        // the minted alias so cold-start resolveConnection finds it.
        waitUntil { servers.upserts.isNotEmpty() }
        assertEquals(alias, servers.upserts.last().passwordAlias)
        assertEquals(serverId, servers.upserts.last().id)
    }

    /**
     * [FakeSshClient] that additionally indexes minted sessions by
     * target host. The two-server test needs to know WHICH fake session
     * belongs to which server, and the concurrent connect pipelines make
     * the order of [FakeSshClient.sessions] nondeterministic.
     */
    private class HostTrackingSshClient : FakeSshClient() {
        val sessionsByHost = ConcurrentHashMap<String, FakeSshSession>()
        override suspend fun connect(
            target: SshTarget,
            auth: SshAuth,
            timeoutMillis: Long,
        ): SshSession {
            val session = super.connect(target, auth, timeoutMillis) as FakeSshSession
            sessionsByHost[target.host] = session
            return session
        }
    }

    // ── (5) THE NEW CAPABILITY: two concurrent, independent connections ──
    //
    // Pre-#42 a single VM owned a single transport, so "two servers at
    // once" was structurally impossible. ConnectionManagerTest (e) tears
    // BOTH down via disconnect-all; what nothing pinned is independence:
    // tearing ONE down must leave the other's transport, registry entry,
    // hub entry, and state-flow INSTANCE completely untouched.
    @Test
    fun twoServers_concurrentSlots_tearingOneDownLeavesTheOtherLive() {
        val secondId = UUID.fromString("66666666-6666-6666-6666-666666666666")
        val servers = FakeServerRepository().apply {
            put(server(host = "vps-1.example"))
            put(server(id = secondId, label = "prod-2", host = "vps-2.example"))
        }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = HostTrackingSshClient()
        val manager = newManager(
            servers,
            hub,
            registry,
            sshClient,
            passwordCache = PasswordCache().apply {
                put(serverId, "pw")
                put(secondId, "pw")
            },
        )

        val conn1 = manager.connect(serverId)
        val conn2 = manager.connect(secondId)
        waitUntil {
            conn1.state.value is TransportState.Connected &&
                conn2.state.value is TransportState.Connected
        }

        // Both registered, independent slots and state flows.
        assertNotSame(conn1, conn2)
        assertEquals(
            setOf(serverId to "shell", secondId to "shell"),
            registry.entries.value.keys,
        )
        assertEquals(setOf(serverId, secondId), hub.clients.value.keys)
        val survivorState = conn1.state.value

        manager.disconnect(secondId)

        assertTrue(conn2.state.value is TransportState.Disconnected)
        // The survivor is UNTOUCHED: same state instance (not just an
        // equal Connected), live transport, registry + hub entries kept.
        assertSame(survivorState, conn1.state.value)
        assertEquals(setOf(serverId to "shell"), registry.entries.value.keys)
        assertEquals(setOf(serverId), hub.clients.value.keys)
        assertEquals(
            "torn-down server's transport must be closed",
            1,
            sshClient.sessionsByHost.getValue("vps-2.example").closed.get(),
        )
        assertEquals(
            "survivor's transport must NOT be closed",
            0,
            sshClient.sessionsByHost.getValue("vps-1.example").closed.get(),
        )
    }

    /**
     * Live-but-broken PTY: parks like the stock fake (so the session
     * stays Connected) but every write throws — the stale-connection
     * case writeErrors exists for (pre-v1.1.13 keystrokes vanished
     * silently into exactly this).
     */
    private class FailingWritePty : PtyChannel {
        val writeAttempts = AtomicInteger(0)
        override val output: Flow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override suspend fun write(bytes: ByteArray) {
            writeAttempts.incrementAndGet()
            throw IOException("simulated stale connection")
        }
        override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
        override fun close() { /* no-op */ }
    }

    // ── (6) writeToPty failure → writeErrors, one-slot DROP_OLDEST ──
    //
    // The real semantics being locked (not a strawman): writeErrors has
    // replay=0 + extraBufferCapacity=1 + DROP_OLDEST, so (i) an ACTIVE
    // collector sees failures, and (ii) failures emitted while a
    // collector is busy coalesce — at most ONE is buffered, so a flurry
    // produces one queued snackbar, not a stack. (A collector that
    // subscribes only after the failures sees nothing at all — replay=0
    // — which is why the test parks a live collector rather than
    // collecting late.)
    @Test
    fun writeFailure_surfacesOnWriteErrors_andFlurryCoalescesToOneBufferedSlot() {
        val pty = FailingWritePty()
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = object : FakeSshSession() {
                override suspend fun openShell(
                    term: String,
                    cols: Int,
                    rows: Int,
                    command: String?,
                ): PtyChannel = pty
            }
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val manager = newManager(servers, EventStreamHub(), SessionRegistry(), sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val fast = CopyOnWriteArrayList<String>()
        val parked = CopyOnWriteArrayList<String>()
        val release = CompletableDeferred<Unit>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        // FAST collector: keeps up with every emission — both an
        // assertion target and the observability handle that tells us a
        // tryEmit has definitely happened before we emit the next one.
        collectScope.launch { conn.writeErrors.collect { fast += it } }
        // PARKED collector: suspends inside the collect block after its
        // first value, modelling a busy UI. Items emitted while it is
        // parked land in the one-slot buffer.
        collectScope.launch {
            conn.writeErrors.collect {
                parked += it
                release.await()
            }
        }

        // Failure #1: surfaces on BOTH collectors; parked then blocks.
        manager.writeToPty(serverId, byteArrayOf(0x01))
        waitUntil { fast.size == 1 && parked.size == 1 }
        assertTrue(fast.first().contains("Send failed"))

        // Failures #2 and #3 while parked is busy. Sequenced via the
        // fast collector so each tryEmit provably lands before the next:
        // #2 fills the one buffered slot, #3 DROP_OLDESTs it out.
        manager.writeToPty(serverId, byteArrayOf(0x02))
        waitUntil { fast.size == 2 }
        manager.writeToPty(serverId, byteArrayOf(0x03))
        waitUntil { fast.size == 3 }
        assertEquals(3, pty.writeAttempts.get())

        // Unpark: the parked collector drains the buffer — which must
        // hold exactly ONE coalesced failure, not both.
        release.complete(Unit)
        waitUntil { parked.size >= 2 }
        assertEquals(
            "a flurry while busy must coalesce into the single buffered slot",
            2,
            parked.size,
        )
        assertEquals(3, fast.size)
        collectScope.cancel()
    }

    /**
     * PTY whose output stream EOFs when the test completes [eof] —
     * the remote shell exiting (`exit` / transport death). Until then it
     * parks like the stock fake so the session stays Connected.
     */
    private class EofPty : PtyChannel {
        val eof = CompletableDeferred<Unit>()
        override val output: Flow<ByteArray> = flow { eof.await() }
        override suspend fun write(bytes: ByteArray) { /* no-op */ }
        override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
        override fun close() { /* no-op */ }
    }

    /** Session minting an [EofPty], optionally recording close() ordering. */
    private class EofSshSession(
        private val events: CopyOnWriteArrayList<String>? = null,
    ) : FakeSshSession() {
        val shellPty = EofPty()
        override suspend fun openShell(
            term: String,
            cols: Int,
            rows: Int,
            command: String?,
        ): PtyChannel = shellPty
        override fun close() {
            events?.add("session.close")
            super.close()
        }
    }

    // ── (7) remote-shell EOF: FULL teardown, unpublish before close ──
    //
    // Locks the Task #44 leak fix. Pre-fix, onShellFinished closed only
    // the PTY handle: `conn.sshSession` stayed open (an idle sshj
    // keepalive + TCP no registry entry or foreground service knew
    // about) and the hub entry dangled. Now EOF must behave like a full
    // disconnect: Disconnected slot, registry entry gone, hub entry gone
    // — and the §17-item-15 order (unpublish strictly BEFORE the session
    // close) must hold on THIS path too, not just on disconnect().
    @Test
    fun shellEof_tearsDownWholeTransport_unpublishesHubBeforeClosingSession() {
        val events = CopyOnWriteArrayList<String>()
        val hub = object : EventStreamHub() {
            override fun unpublish(serverId: UUID) {
                events += "hub.unpublish"
                super.unpublish(serverId)
            }
        }
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = EofSshSession(events)
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val registry = SessionRegistry()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val session = sshClient.sessions.single() as EofSshSession

        // The remote shell exits: the PTY output stream completes.
        session.shellPty.eof.complete(Unit)
        waitUntil { conn.state.value is TransportState.Disconnected }

        assertTrue(conn.state.value is TransportState.Disconnected)
        assertTrue("registry entry must be removed on EOF", registry.entries.value.isEmpty())
        assertTrue("hub entry must be dropped on EOF", hub.clients.value.isEmpty())
        assertEquals(
            "EOF teardown must keep the load-bearing order",
            listOf("hub.unpublish", "session.close"),
            events.toList(),
        )
        assertEquals("the dead transport must be closed exactly once", 1, session.closed.get())
        // The long-lived slot itself survives for reconnect (stable flows).
        assertSame(conn, manager.connections.value[serverId])
    }

    // ── (8) reconnect AFTER EOF: same slot, fresh transport, no leak ──
    //
    // The other reconnect entry points are pinned elsewhere (broker path
    // in ConnectionManagerTest (f)); this is the EOF→tap-reconnect cycle
    // that used to leak one sshj transport per iteration: connect() sees
    // Disconnected and dials fresh, and pre-fix would overwrite the
    // still-open old session reference without closing it.
    @Test
    fun reconnectAfterEof_reusesLongLivedSlot_dialsFreshTransport() {
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = EofSshSession()
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstEmulator = (conn.state.value as TransportState.Connected).session
        val firstSession = sshClient.sessions.single() as EofSshSession

        firstSession.shellPty.eof.complete(Unit)
        waitUntil { conn.state.value is TransportState.Disconnected }
        assertTrue("EOF must close the dead transport", firstSession.closed.get() >= 1)

        // User taps reconnect — slot is Disconnected, so this dials.
        val rebound = manager.connect(serverId)

        assertSame("reconnect must reuse the long-lived slot", conn, rebound)
        waitUntil {
            conn.state.value is TransportState.Connected && sshClient.sessions.size == 2
        }
        assertEquals("reconnect must dial a fresh transport", 2, sshClient.connectCount.get())
        val secondSession = sshClient.sessions[1]
        assertNotSame(firstSession, secondSession)
        assertEquals("the fresh transport must stay open", 0, secondSession.closed.get())
        assertNotSame(
            "a fresh dial builds a fresh emulator (the dead one was finished)",
            firstEmulator,
            (conn.state.value as TransportState.Connected).session,
        )
        // Registry + hub repopulated under the same key.
        assertEquals(setOf(serverId to "shell"), registry.entries.value.keys)
        assertEquals(setOf(serverId), hub.clients.value.keys)
    }

    // ── (9) involuntary drop (transport dead) on EOF → AUTO-RECONNECT ──
    //
    // v1.7.4: when the detecting keepalive declares a half-open link dead,
    // the channel EOFs while the SSH transport is already down. That is an
    // INVOLUNTARY drop (not a deliberate `exit`), so the manager must dial
    // a fresh transport on its own and land back on Connected — no user
    // tap. Distinguished from a clean exit by SshSession.isTransportAlive.
    @Test
    fun involuntaryDrop_transportDead_autoReconnectsToFreshTransport() {
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = EofSshSession()
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val manager = newManager(servers, EventStreamHub(), SessionRegistry(), sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstSession = sshClient.sessions.single() as EofSshSession

        // Model a transport DEATH (not a clean logout): the SSH transport
        // is already dead by the time the shell channel EOFs.
        firstSession.transportAlive = false
        firstSession.shellPty.eof.complete(Unit)

        // The drop is involuntary → auto-reconnect dials a fresh transport
        // and returns to Connected with no user action.
        waitUntil { sshClient.sessions.size == 2 && conn.state.value is TransportState.Connected }
        assertEquals("involuntary drop must auto-reconnect", 2, sshClient.connectCount.get())
        assertTrue(conn.state.value is TransportState.Connected)
        assertNotSame("auto-reconnect must dial a fresh transport", firstSession, sshClient.sessions[1])
    }

    // ── (10) clean remote exit (transport alive) → NO auto-reconnect ──
    //
    // The mirror of (9): a deliberate `exit`/agent-done closes only the
    // channel; the SSH transport stays connected. That must stay
    // Disconnected — auto-reconnecting a logout would make sessions
    // impossible to end.
    @Test
    fun cleanExit_transportAlive_doesNotAutoReconnect() {
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = EofSshSession()
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val manager = newManager(servers, EventStreamHub(), SessionRegistry(), sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstSession = sshClient.sessions.single() as EofSshSession

        // transportAlive defaults true → a clean logout.
        firstSession.shellPty.eof.complete(Unit)
        waitUntil { conn.state.value is TransportState.Disconnected }

        // Let any erroneous auto-reconnect have a chance to fire, then
        // assert it did not.
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("clean exit must stay Disconnected", conn.state.value is TransportState.Disconnected)
        assertEquals("clean exit must NOT auto-reconnect", 1, sshClient.connectCount.get())
    }

    // ── (11) on-resume liveness probe FAILS → auto-reconnect ──
    //
    // The real cure for the reported bug: a socket that died while the app
    // was backgrounded produces no EOF (the keepalive is frozen in Doze).
    // On returning to the foreground the manager round-trips every live
    // slot; a failed probe is an involuntary drop → auto-reconnect.
    @Test
    fun resumeProbeFailure_autoReconnects() {
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = FakeSshSession()
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val tracker = AppForegroundTracker()
        val manager = newManager(
            servers, EventStreamHub(), SessionRegistry(), sshClient,
            foregroundTracker = tracker,
        )

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstSession = sshClient.sessions.single()

        // The socket died silently while backgrounded: the live session's
        // probe now reports dead.
        firstSession.probeResult = false

        // App returns to the foreground → resume sweep probes → dead →
        // involuntary drop → auto-reconnect to a fresh (healthy) session.
        tracker.onStart(mockk(relaxed = true))
        waitUntil { sshClient.sessions.size == 2 && conn.state.value is TransportState.Connected }

        assertTrue("the resume probe must have run", firstSession.probeCount.get() >= 1)
        assertEquals("a failed resume probe must auto-reconnect", 2, sshClient.connectCount.get())
        assertTrue(conn.state.value is TransportState.Connected)
        assertNotSame(firstSession, sshClient.sessions[1])
    }

    // ── (12) on-resume probe SUCCEEDS → slot untouched ──
    //
    // A healthy session must not be churned on every foreground: the probe
    // runs, succeeds, and the exact same Connected state instance survives.
    @Test
    fun resumeProbeSuccess_staysConnected_noReconnect() {
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = FakeSshSession()
        }
        val servers = FakeServerRepository().apply { put(server()) }
        val tracker = AppForegroundTracker()
        val manager = newManager(
            servers, EventStreamHub(), SessionRegistry(), sshClient,
            foregroundTracker = tracker,
        )

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val session = sshClient.sessions.single()
        val connectedState = conn.state.value

        // Healthy link: probe succeeds (default). Resume must not churn it.
        tracker.onStart(mockk(relaxed = true))
        waitUntil { session.probeCount.get() >= 1 }
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }

        assertSame("a healthy slot must be untouched on resume", connectedState, conn.state.value)
        assertEquals("no reconnect on a healthy resume", 1, sshClient.connectCount.get())
    }
}
