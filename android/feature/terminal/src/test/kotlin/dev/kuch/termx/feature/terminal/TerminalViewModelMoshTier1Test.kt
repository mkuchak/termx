package dev.kuch.termx.feature.terminal

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.prefs.AppPreferences
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
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier-1 (event-stream-over-mosh) coverage for [TerminalViewModel].
 *
 * The DEFAULT path is mosh (`Server.useMosh == true`). Before Task #27 the
 * mosh-success branch returned BEFORE publishing an [EventStreamHub] entry
 * and BEFORE syncing the UnifiedPush endpoint, so every mosh-backed
 * session — the common case — got zero notifications. These tests pin the
 * dedicated best-effort side SSH connection that restores both.
 *
 * Real collaborators are used wherever they're cheap value/registry types
 * ([EventStreamHub], [SessionRegistry], [PasswordCache], DataStore-backed
 * prefs under Robolectric); only the transport surfaces ([SshClient],
 * [MoshClient]) and the persistence repos are faked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TerminalViewModelMoshTier1Test {

    // Real Unconfined (not the test scheduler): the side-channel and the
    // UnifiedPush sync hop onto Dispatchers.IO, which a virtual clock
    // can't drive. Tests poll real state with waitUntil() instead.
    @get:Rule
    val mainRule = MainDispatcherRule(Dispatchers.Unconfined)

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val serverId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private fun moshServer(useMosh: Boolean = true) = Server(
        id = serverId,
        label = "prod-1",
        host = "vps.example",
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

    private fun newVm(
        servers: FakeServerRepository,
        hub: EventStreamHub,
        moshSession: FakeMoshSession?,
        sshClient: FakeSshClient,
        passwordCache: PasswordCache = PasswordCache().apply { put(serverId, "pw") },
        alertPreferences: AlertPreferences = AlertPreferences(appContext),
    ): TerminalViewModel = TerminalViewModel(
        appContext = appContext,
        savedStateHandle = SavedStateHandle(),
        serverRepository = servers,
        keyPairRepository = FakeKeyPairRepository(),
        secretVault = FakeSecretVault(),
        passwordCache = passwordCache,
        appPreferences = AppPreferences(appContext),
        alertPreferences = alertPreferences,
        sessionRegistry = SessionRegistry(),
        eventStreamHub = hub,
        eventStreamRepository = EventStreamRepository(EventStreamClientFactory()),
        reconnectBroker = ReconnectBroker(),
        moshClient = FakeMoshClient(appContext, sshClient, moshSession),
        sshClient = sshClient,
        companionUpdateRepository = mockk(relaxed = true),
    )

    @Before
    fun resetPrefs() = runBlocking {
        // DataStore files survive across tests in the same Robolectric VM;
        // start every case with UnifiedPush off so (b) is the only case
        // that performs the endpoint write.
        AlertPreferences(appContext).setUnifiedPushEnabled(false)
        AlertPreferences(appContext).setUnifiedPushEndpoint("")
    }

    /** Busy-wait on a predicate up to ~5s; the IO-hopping paths are fast. */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    // (a) CORE REGRESSION: mosh success must publish a hub entry under the
    //     server's registry id. Fails on the pre-Task-#27 code.
    @Test
    fun moshSuccess_publishesEventStreamHubEntry() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient)

        vm.connect(serverId)
        waitUntil { hub.clients.value.containsKey(serverId) }

        assertEquals(
            TerminalUiState.Status.Connected,
            vm.state.value.status,
        )
        assertTrue("terminal must be mosh-backed", vm.state.value.moshBacked)
        val entry = hub.clients.value[serverId]
        assertTrue(
            "mosh success must publish an EventStreamHub entry under the server id",
            entry != null,
        )
        assertEquals("prod-1", entry!!.serverLabel)
        // The side channel is a SECOND SSH connection (mosh carries no
        // tail-able exec channel of its own).
        assertEquals(1, sshClient.connectCount.get())
    }

    // (b) mosh success syncs the UnifiedPush endpoint over the side
    //     session when enabled + endpoint set: mkdir + atomic SFTP write.
    @Test
    fun moshSuccess_syncsUnifiedPushEndpointOverSideSession() = runBlocking {
        val alerts = AlertPreferences(appContext)
        alerts.setUnifiedPushEnabled(true)
        alerts.setUnifiedPushEndpoint("https://push.example/UP123")

        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient, alertPreferences = alerts)

        vm.connect(serverId)
        // Wait for the side session and then for the endpoint write to land
        // (both behind the DataStore IO read in syncUnifiedPushEndpoint).
        waitUntil { sshClient.sessions.isNotEmpty() }
        val side = sshClient.sessions.first()
        waitUntil { side.sftpRenames.isNotEmpty() }

        // mkdir -p of the ~/.termx dir (home resolved to /home/test).
        assertTrue(
            "side session must mkdir the companion dir; saw ${side.execHistory}",
            side.execHistory.any { it.contains("mkdir") && it.contains("/home/test/.termx") },
        )
        // Atomic write: temp write + rename onto the canonical endpoint path.
        val endpointPath = "/home/test/.termx/ntfy-endpoint"
        assertTrue(
            "side session must atomically write the endpoint file; saw ${side.sftpRenames}",
            side.sftpRenames.any { it.second == endpointPath },
        )
        val written = side.sftpWrites.firstOrNull()
        assertTrue(
            "endpoint bytes must be the registered UnifiedPush endpoint",
            written != null &&
                String(written.second, Charsets.UTF_8) == "https://push.example/UP123",
        )
    }

    // (c) side-SSH connect FAILURE leaves state Connected+moshBacked, the
    //     hub empty, and no exception escaping.
    @Test
    fun moshSuccess_sideConnectFailure_isSwallowed() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient(failConnect = true)
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient)

        // Must not throw despite the side connect blowing up.
        vm.connect(serverId)
        waitUntil { sshClient.connectCount.get() >= 1 }

        assertEquals(
            "terminal stays up when the side channel fails",
            TerminalUiState.Status.Connected,
            vm.state.value.status,
        )
        assertTrue(vm.state.value.moshBacked)
        assertNull("connection error must not be surfaced", vm.state.value.error)
        assertTrue(
            "no hub entry when the side connect failed",
            hub.clients.value.isEmpty(),
        )
    }

    // (d) disconnect tears down the side session AND unpublishes the hub.
    @Test
    fun disconnect_closesSideSession_andUnpublishes() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient)

        vm.connect(serverId)
        waitUntil { hub.clients.value.containsKey(serverId) }
        val side = sshClient.sessions.first()

        vm.disconnect()

        assertEquals(
            TerminalUiState.Status.Disconnected,
            vm.state.value.status,
        )
        assertTrue("side session must be closed on disconnect", side.closed.get() >= 1)
        assertTrue(
            "hub entry must be dropped on disconnect",
            hub.clients.value.isEmpty(),
        )
    }

    // (e) plain-SSH path still publishes EXACTLY ONCE and opens no second
    //     connection (no side channel on the non-mosh path).
    @Test
    fun plainSsh_publishesOnce_andNoSideChannel() {
        val servers = FakeServerRepository().apply { put(moshServer(useMosh = false)) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        // moshSession irrelevant on the plain path, but FakeMoshClient is
        // never consulted because useMosh == false.
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient)

        vm.connect(serverId)
        waitUntil { hub.clients.value.containsKey(serverId) }

        assertEquals(TerminalUiState.Status.Connected, vm.state.value.status)
        assertFalse("plain SSH must not be mosh-backed", vm.state.value.moshBacked)
        assertTrue(hub.clients.value.containsKey(serverId))
        // Exactly one SSH connect (the primary). No side channel.
        assertEquals(
            "plain-SSH path must open exactly one SSH connection",
            1,
            sshClient.connectCount.get(),
        )
        assertEquals(1, sshClient.sessions.size)
    }

    // (f) LATE side-connect after teardown: if disconnect() ran while the
    //     side SSH was still handshaking, the resolved session must be
    //     closed as an orphan and NOT published.
    @Test
    fun lateSideConnect_afterTeardown_closesOrphan_andDoesNotPublish() {
        val gate = CompletableDeferred<Unit>()
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        // Gate the side connect so it can't land until we release it.
        val sshClient = FakeSshClient(gate = gate)
        val vm = newVm(servers, hub, FakeMoshSession(), sshClient)

        vm.connect(serverId)
        // mosh terminal is up; the side connect is parked on the gate.
        waitUntil { vm.state.value.status == TerminalUiState.Status.Connected }
        waitUntil { sshClient.connectCount.get() >= 1 }
        assertTrue("hub must still be empty while side connect is parked", hub.clients.value.isEmpty())

        // Tear down BEFORE the side connect resolves.
        vm.disconnect()
        // Now let the orphan side connection complete.
        gate.complete(Unit)
        waitUntil { sshClient.sessions.isNotEmpty() }
        val orphan = sshClient.sessions.first()
        waitUntil { orphan.closed.get() >= 1 }

        assertTrue("orphan side session must be closed", orphan.closed.get() >= 1)
        assertTrue(
            "a side session landing after teardown must NOT publish",
            hub.clients.value.isEmpty(),
        )
    }
}
