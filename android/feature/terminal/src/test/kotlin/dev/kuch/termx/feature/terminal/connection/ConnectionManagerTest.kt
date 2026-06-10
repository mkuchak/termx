package dev.kuch.termx.feature.terminal.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.prefs.PasswordCache
import dev.kuch.termx.core.data.remote.EventStreamRepository
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.core.data.session.ReconnectBroker
import dev.kuch.termx.core.data.session.SessionRegistry
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.feature.terminal.fakes.FakeKeyPairRepository
import dev.kuch.termx.feature.terminal.fakes.FakeMoshClient
import dev.kuch.termx.feature.terminal.fakes.FakeSecretVault
import dev.kuch.termx.feature.terminal.fakes.FakeServerRepository
import dev.kuch.termx.feature.terminal.fakes.FakeSshClient
import dev.kuch.termx.feature.terminal.fakes.FakeSshSession
import dev.kuch.termx.feature.terminal.fakes.QuiescentMainDispatcherRule
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Direct coverage for [ConnectionManager] — the Task #42 extraction.
 * The mosh race/liveness/side-channel behavior is pinned end-to-end
 * through the VM in [TerminalViewModelMoshTier1Test]/
 * [TerminalViewModelMoshFallbackTest]; this file pins the manager's own
 * contracts on the plain-SSH path:
 *
 *  (a) connect happy path: registry entry + hub entry under the
 *      server's id, Connected state on the slot;
 *  (b) cleanup ordering: `eventStreamHub.unpublish` strictly BEFORE
 *      `session.close` (PROJECT_KNOWLEDGE §17 item 15 — reversed order
 *      makes router collectors die on transient exec errors). Asserted
 *      with recording fakes interleaving into one event list;
 *  (c) disconnect is idempotent — a double call neither throws nor
 *      double-closes.
 *
 * Plus the Task #43 lifecycle-flip contracts:
 *
 *  (d) connect is BIND-IF-ALIVE — a second connect for a Connected
 *      server returns the same slot/transport untouched, no redial;
 *  (e) the notification "Disconnect all" signal
 *      ([SessionRegistry.disconnectAllRequest]) tears down EVERY
 *      connection and empties the registry — collected by the manager
 *      itself, no ViewModel involved;
 *  (f) the notification Reconnect action ([ReconnectBroker]) redials
 *      the matching server with NO ViewModel constructed.
 *
 * Same Robolectric + real-time polling setup as the VM tests: the byte
 * pump lives on Dispatchers.IO, which a virtual clock can't drive.
 * Main-dispatcher setup + manager-scope quiescence live in the shared
 * [QuiescentMainDispatcherRule] (see its KDoc for the threading story).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConnectionManagerTest {

    @get:Rule
    val mainRule = QuiescentMainDispatcherRule()

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val serverId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Before
    fun resetPrefs() = runBlocking {
        // DataStore files survive across tests in the same Robolectric
        // VM; keep UnifiedPush off so no test does endpoint writes.
        AlertPreferences(appContext).setUnifiedPushEnabled(false)
        AlertPreferences(appContext).setUnifiedPushEndpoint("")
    }

    private fun sshServer(
        id: UUID = serverId,
        label: String = "prod-1",
    ) = Server(
        id = id,
        label = label,
        host = "vps.example",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        keyPairId = null,
        passwordAlias = null,
        groupId = null,
        useMosh = false,
        lastConnected = null,
        pingMs = null,
    )

    private fun newManager(
        servers: FakeServerRepository,
        hub: EventStreamHub,
        registry: SessionRegistry,
        sshClient: FakeSshClient,
        reconnectBroker: ReconnectBroker = ReconnectBroker(),
        passwordCache: PasswordCache = PasswordCache().apply { put(serverId, "pw") },
    ): ConnectionManager = ConnectionManager(
        appContext = appContext,
        serverRepository = servers,
        keyPairRepository = FakeKeyPairRepository(),
        secretVault = FakeSecretVault(),
        passwordCache = passwordCache,
        alertPreferences = AlertPreferences(appContext),
        sessionRegistry = registry,
        reconnectBroker = reconnectBroker,
        eventStreamHub = hub,
        eventStreamRepository = EventStreamRepository(EventStreamClientFactory()),
        companionUpdateRepository = mockk(relaxed = true),
        moshClient = FakeMoshClient(appContext, sshClient, session = null),
        sshClient = sshClient,
    ).also { mainRule.track(it) }

    /** Busy-wait on a predicate up to ~5s; the IO-hopping paths are fast. */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    // (a) Happy path: one registry entry and one hub entry under the
    //     server id; the slot lands Connected with the emulator attached
    //     and the slot is exposed through [ConnectionManager.connections].
    @Test
    fun connectHappyPath_registersRegistryAndHub() {
        val servers = FakeServerRepository().apply { put(sshServer()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val connected = conn.state.value
        assertTrue("slot must be Connected, was $connected", connected is TransportState.Connected)
        // Plain-SSH path: not mosh-backed, no fallback reason, and the
        // Connected state carries the emulator-bearing session the
        // screen binds its TerminalView to.
        assertEquals(false, (connected as TransportState.Connected).moshBacked)
        assertEquals(null, connected.transportFallbackReason)
        assertEquals("prod-1", conn.serverLabel)
        // Registry: exactly one live tab, keyed (serverId, "shell").
        assertEquals(1, registry.entries.value.size)
        val entry = registry.entries.value[serverId to "shell"]
        assertTrue("registry entry must be keyed by (serverId, shell)", entry != null)
        assertEquals("prod-1", entry!!.serverLabel)
        // Hub: published under the same id, with the label for the router.
        val hubEntry = hub.clients.value[serverId]
        assertTrue("hub entry must be published under the server id", hubEntry != null)
        assertEquals("prod-1", hubEntry!!.serverLabel)
        // The slot is observable process-wide.
        assertSame(conn, manager.connections.value[serverId])
        // Exactly one SSH connect on the plain path (no side channel).
        assertEquals(1, sshClient.connectCount.get())
    }

    // (b) THE LOAD-BEARING ORDER (PROJECT_KNOWLEDGE §17 item 15): hub
    //     unpublish must happen BEFORE the session closes so router
    //     collectors cancel first instead of dying on a transient exec
    //     error mid-teardown.
    @Test
    fun disconnect_unpublishesHubBeforeClosingSession() {
        val events = CopyOnWriteArrayList<String>()
        val hub = object : EventStreamHub() {
            override fun unpublish(serverId: UUID) {
                events += "hub.unpublish"
                super.unpublish(serverId)
            }
        }
        val sshClient = object : FakeSshClient() {
            override fun newSession(): FakeSshSession = object : FakeSshSession() {
                override fun close() {
                    events += "session.close"
                    super.close()
                }
            }
        }
        val servers = FakeServerRepository().apply { put(sshServer()) }
        val registry = SessionRegistry()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        manager.disconnect(serverId)

        assertEquals(
            "hub unpublish must strictly precede session close",
            listOf("hub.unpublish", "session.close"),
            events.toList(),
        )
        assertTrue(conn.state.value is TransportState.Disconnected)
        assertTrue(registry.entries.value.isEmpty())
        assertTrue(hub.clients.value.isEmpty())
    }

    // (c) Double-disconnect: second call finds no transport handles and
    //     no-ops — no throw, no double-close, registry/hub stay empty.
    @Test
    fun disconnect_twice_isIdempotent() {
        val servers = FakeServerRepository().apply { put(sshServer()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val session = sshClient.sessions.first()

        manager.disconnect(serverId)
        manager.disconnect(serverId)

        assertEquals("session must be closed exactly once", 1, session.closed.get())
        assertTrue(conn.state.value is TransportState.Disconnected)
        assertTrue(registry.entries.value.isEmpty())
        assertTrue(hub.clients.value.isEmpty())
        // The slot survives for reuse (stable flows across reconnects).
        assertSame(conn, manager.connections.value[serverId])
    }

    // (d) BIND-IF-ALIVE (Task #43): a second connect() while Connected
    //     must NOT dial a second transport, reset state, or swap the
    //     emulator — re-entering the terminal screen for a live server
    //     rebinds to the existing session untouched.
    @Test
    fun connect_whileConnected_bindsExistingTransport_noRedial() {
        val servers = FakeServerRepository().apply { put(sshServer()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val manager = newManager(servers, hub, registry, sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstState = conn.state.value as TransportState.Connected

        val rebound = manager.connect(serverId)

        // Same slot, same state INSTANCE (not just equal — untouched),
        // same emulator-bearing session, and exactly one dial total.
        assertSame("second connect must return the same slot", conn, rebound)
        assertSame(
            "state must be untouched by a bind-if-alive connect",
            firstState,
            rebound.state.value,
        )
        assertSame(
            "the emulator instance must be the original one",
            firstState.session,
            (rebound.state.value as TransportState.Connected).session,
        )
        assertEquals(
            "a live connection must not be redialed",
            1,
            sshClient.connectCount.get(),
        )
        // Registry/hub unchanged: still exactly one entry each.
        assertEquals(1, registry.entries.value.size)
        assertEquals(1, hub.clients.value.size)
    }

    // (e) "Disconnect all" (notification action → registry flow) tears
    //     down EVERY connection: collected by the manager's own init —
    //     no ViewModel exists in this test — and leaves the registry/hub
    //     empty so the foreground service stops itself.
    @Test
    fun disconnectAllRequest_tearsDownEveryConnection() {
        val secondServerId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val servers = FakeServerRepository().apply {
            put(sshServer())
            put(sshServer(id = secondServerId, label = "prod-2"))
        }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val manager = newManager(
            servers,
            hub,
            registry,
            sshClient,
            passwordCache = PasswordCache().apply {
                put(serverId, "pw")
                put(secondServerId, "pw")
            },
        )

        val conn1 = manager.connect(serverId)
        val conn2 = manager.connect(secondServerId)
        waitUntil {
            conn1.state.value is TransportState.Connected &&
                conn2.state.value is TransportState.Connected
        }
        assertEquals(2, registry.entries.value.size)
        val sessions = sshClient.sessions.toList()
        assertEquals(2, sessions.size)

        // Drive the exact signal the notification action posts.
        registry.requestDisconnectAll()

        waitUntil {
            conn1.state.value is TransportState.Disconnected &&
                conn2.state.value is TransportState.Disconnected
        }
        assertTrue(conn1.state.value is TransportState.Disconnected)
        assertTrue(conn2.state.value is TransportState.Disconnected)
        assertTrue("registry must be empty after disconnect-all", registry.entries.value.isEmpty())
        assertTrue("hub must be empty after disconnect-all", hub.clients.value.isEmpty())
        sessions.forEach { session ->
            assertTrue("every transport must be closed", session.closed.get() >= 1)
        }
    }

    // (f) Reconnect-without-VM (Task #43): the notification Reconnect
    //     action posts into ReconnectBroker; the manager's init collector
    //     must redial that server with NO terminal screen/ViewModel
    //     alive. Fresh dial = a second transport on the same slot.
    @Test
    fun reconnectBrokerRequest_redialsServer_withNoViewModel() {
        val servers = FakeServerRepository().apply { put(sshServer()) }
        val hub = EventStreamHub()
        val registry = SessionRegistry()
        val sshClient = FakeSshClient()
        val broker = ReconnectBroker()
        val manager = newManager(servers, hub, registry, sshClient, reconnectBroker = broker)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val firstSession = sshClient.sessions.first()

        // Drive the exact signal ReconnectActionReceiver posts.
        broker.requestReconnect(serverId)

        waitUntil {
            sshClient.sessions.size == 2 && conn.state.value is TransportState.Connected
        }

        assertEquals("reconnect must dial a fresh transport", 2, sshClient.connectCount.get())
        assertTrue("the old transport must be closed", firstSession.closed.get() >= 1)
        assertTrue(
            "slot must land Connected again, was ${conn.state.value}",
            conn.state.value is TransportState.Connected,
        )
        // Same long-lived slot across the reconnect (stable flows).
        assertSame(conn, manager.connections.value[serverId])
        assertEquals(1, registry.entries.value.size)
    }
}
