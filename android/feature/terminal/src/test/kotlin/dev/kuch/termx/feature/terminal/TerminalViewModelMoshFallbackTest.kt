package dev.kuch.termx.feature.terminal

import android.content.Context
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
import dev.kuch.termx.feature.terminal.connection.ConnectionManager
import dev.kuch.termx.feature.terminal.fakes.FakeKeyPairRepository
import dev.kuch.termx.feature.terminal.fakes.FakeMoshClient
import dev.kuch.termx.feature.terminal.fakes.FakeMoshSession
import dev.kuch.termx.feature.terminal.fakes.FakeSecretVault
import dev.kuch.termx.feature.terminal.fakes.FakeServerRepository
import dev.kuch.termx.feature.terminal.fakes.FakeSshClient
import dev.kuch.termx.feature.terminal.fakes.QuiescentMainDispatcherRule
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import dev.kuch.termx.libs.sshnative.MoshFailureReason
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * Truthful-transport coverage for [TerminalViewModel]: the mosh
 * first-output liveness gate + the mosh→SSH fallback reason surfacing.
 *
 * The killed bug: with UDP 60000-60010 firewalled on the VPS,
 * `mosh-server new` still prints `MOSH CONNECT` over SSH/TCP, the local
 * mosh-client spawns — and then waits forever for datagrams that never
 * arrive. Pre-gate the VM flipped to Connected+moshBacked on that dead
 * transport (frozen terminal reported as success), and every mosh→SSH
 * fallback was a single silent `Log.i`.
 *
 * Fakes follow this module's open-class subclass pattern
 * ([FakeMoshClient]/[FakeSshClient]); same Robolectric + real-time
 * polling setup as [TerminalViewModelMoshTier1Test] because the byte
 * pump and liveness wait live on Dispatchers.IO / real delays.
 *
 * Main is a HandlerThread-backed dispatcher (NOT Unconfined): the
 * liveness timeout resumes the connect coroutine on the coroutines
 * DefaultExecutor timer thread, and with Unconfined the continuation
 * would STAY there — where the vendored `TerminalSession` constructor's
 * no-arg `Handler()` (TerminalSession.java:96) throws "Can't create
 * handler inside thread that has not called Looper.prepare()". That
 * setup — plus the manager-scope quiescence that kills the cross-class
 * TestMainDispatcher race — now lives in the shared
 * [QuiescentMainDispatcherRule]; its KDoc carries the full WHY,
 * including why the dispatcher is hand-rolled rather than
 * `Handler.asCoroutineDispatcher()` (Robolectric's mocked SystemClock
 * would freeze `postDelayed`-based timeouts forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TerminalViewModelMoshFallbackTest {

    @get:Rule
    val mainRule = QuiescentMainDispatcherRule()

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val serverId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun moshServer() = Server(
        id = serverId,
        label = "prod-1",
        host = "vps.example",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        keyPairId = null,
        passwordAlias = null,
        groupId = null,
        useMosh = true,
        lastConnected = null,
        pingMs = null,
    )

    /**
     * Post-Task-#42 the transport (and the liveness gate under test)
     * lives in [ConnectionManager]; the VM is a binder. These tests keep
     * driving everything THROUGH the VM so they pin the end-to-end
     * behavior across the split.
     */
    private fun newVm(
        servers: FakeServerRepository,
        hub: EventStreamHub,
        moshClient: FakeMoshClient,
        sshClient: FakeSshClient,
        registry: SessionRegistry = SessionRegistry(),
    ): TerminalViewModel {
        val manager = ConnectionManager(
            appContext = appContext,
            serverRepository = servers,
            keyPairRepository = FakeKeyPairRepository(),
            secretVault = FakeSecretVault(),
            passwordCache = PasswordCache().apply { put(serverId, "pw") },
            alertPreferences = AlertPreferences(appContext),
            sessionRegistry = registry,
            reconnectBroker = ReconnectBroker(),
            eventStreamHub = hub,
            eventStreamRepository = EventStreamRepository(EventStreamClientFactory()),
            companionUpdateRepository = mockk(relaxed = true),
            moshClient = moshClient,
            sshClient = sshClient,
        ).also {
            // Tiny so the no-first-output liveness-failure path falls back
            // in ~0.3s instead of burning the production 15s window.
            it.firstOutputTimeoutMs = 300L
            mainRule.track(it)
        }
        return TerminalViewModel(
            appPreferences = AppPreferences(appContext),
            connectionManager = manager,
        )
    }

    @Before
    fun resetPrefs() = runBlocking {
        AlertPreferences(appContext).setUnifiedPushEnabled(false)
        AlertPreferences(appContext).setUnifiedPushEndpoint("")
    }

    /**
     * Busy-wait on a predicate. Deadline is a generous 12s (not the
     * Tier-1 file's 5s) because the liveness-failure cases burn the
     * real-time first-output window before the SSH fallback starts —
     * these tests shrink it to 300ms via [newVm]'s `firstOutputTimeoutMs`
     * override (production is 15s).
     */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
    }

    /** Collect one-shot transport notices for the duration of a test. */
    private fun collectNotices(vm: TerminalViewModel): Pair<CopyOnWriteArrayList<String>, Job> {
        val notices = CopyOnWriteArrayList<String>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            vm.transportNotices.collect { notices += it }
        }
        return notices to job
    }

    // (a) THE BUG THIS KILLS: handshake OK but zero output bytes within
    //     the first-output window (a slow-cold-starting mosh-client or
    //     genuinely filtered UDP) → SSH fallback IN THE SAME connect
    //     attempt, mosh session closed, fallback reason on uiState,
    //     one-shot notice emitted.
    @Test
    fun moshSilentAfterHandshake_fallsBackToSsh_sameAttempt_withFallbackReason() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val moshSession = FakeMoshSession(emitFirstOutput = false)
        val moshClient = FakeMoshClient(appContext, sshClient, moshSession)
        val vm = newVm(servers, hub, moshClient, sshClient)
        val (notices, noticeJob) = collectNotices(vm)

        vm.connect(serverId)
        waitUntil { vm.state.value.status == TerminalUiState.Status.Connected }

        assertEquals(TerminalUiState.Status.Connected, vm.state.value.status)
        assertFalse("fallback connection must NOT be mosh-backed", vm.state.value.moshBacked)
        assertNull("fallback is not an error", vm.state.value.error)
        assertEquals(
            "no response in time — slow start or blocked UDP",
            vm.state.value.transportFallbackReason,
        )
        // Same attempt: exactly one mosh try, the dead session closed.
        assertEquals(1, moshClient.tryConnectCount.get())
        assertTrue("dead mosh session must be closed", moshSession.closed.get() >= 1)
        // SSH path took over: one PRIMARY connect, no mosh side channel.
        assertEquals(
            "fallback must open exactly one SSH connection (no side channel)",
            1,
            sshClient.connectCount.get(),
        )
        // The primary session publishes the hub entry on the SSH path.
        waitUntil { hub.clients.value.containsKey(serverId) }
        assertTrue(hub.clients.value.containsKey(serverId))
        // One-shot snackbar notice fired with the same reason.
        waitUntil { notices.isNotEmpty() }
        assertEquals(
            listOf(
                "Connected via SSH — mosh unavailable: " +
                    "no response in time — slow start or blocked UDP",
            ),
            notices.toList(),
        )
        noticeJob.cancel()
    }

    // (a2) liveness teardown must leave no orphaned SessionRegistry entry
    //      — the mosh tab registered pre-gate is unregistered on fallback
    //      and replaced by the SSH tab's registration.
    @Test
    fun moshSilentAfterHandshake_registryHoldsOnlyTheSshTab() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val moshClient =
            FakeMoshClient(appContext, sshClient, FakeMoshSession(emitFirstOutput = false))
        val registry = SessionRegistry()
        val vm = newVm(servers, hub, moshClient, sshClient, registry = registry)

        vm.connect(serverId)
        waitUntil { vm.state.value.status == TerminalUiState.Status.Connected }

        assertEquals(
            "exactly one live registry entry after fallback",
            1,
            registry.entries.value.size,
        )
        vm.disconnect()
        assertTrue(registry.entries.value.isEmpty())
    }

    // (b) healthy mosh: first bytes arrive promptly → moshBacked stays
    //     true and no fallback reason is set.
    @Test
    fun moshEmitsPromptly_staysMoshBacked_reasonNull() {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val moshClient = FakeMoshClient(appContext, sshClient, FakeMoshSession())
        val vm = newVm(servers, hub, moshClient, sshClient)
        val (notices, noticeJob) = collectNotices(vm)

        vm.connect(serverId)
        waitUntil { vm.state.value.status == TerminalUiState.Status.Connected }

        assertTrue("live mosh must stay mosh-backed", vm.state.value.moshBacked)
        assertNull(
            "no fallback reason on a live mosh transport",
            vm.state.value.transportFallbackReason,
        )
        // Side channel (the one SSH connect) starts only after the gate.
        waitUntil { hub.clients.value.containsKey(serverId) }
        assertEquals(1, sshClient.connectCount.get())
        assertTrue("no fallback notice on a live mosh transport", notices.isEmpty())
        noticeJob.cancel()
    }

    // (c) classified handshake failures propagate their human string to
    //     uiState.transportFallbackReason on the SSH fallback.
    @Test
    fun handshakeFailure_missingUtf8Locale_reasonPropagatesToUiState() {
        assertHandshakeFailureSurfaces(
            MoshFailureReason.MissingUtf8Locale,
            "VPS missing UTF-8 locale",
        )
    }

    @Test
    fun handshakeFailure_moshServerMissing_reasonPropagatesToUiState() {
        assertHandshakeFailureSurfaces(
            MoshFailureReason.MoshServerMissing,
            "mosh-server not installed",
        )
    }

    @Test
    fun handshakeFailure_timeout_reasonPropagatesToUiState() {
        assertHandshakeFailureSurfaces(
            MoshFailureReason.HandshakeTimeout,
            "handshake timeout",
        )
    }

    private fun assertHandshakeFailureSurfaces(
        reason: MoshFailureReason,
        expectedText: String,
    ) {
        val servers = FakeServerRepository().apply { put(moshServer()) }
        val hub = EventStreamHub()
        val sshClient = FakeSshClient()
        val moshClient =
            FakeMoshClient(appContext, sshClient, session = null, failureReason = reason)
        val vm = newVm(servers, hub, moshClient, sshClient)
        val (notices, noticeJob) = collectNotices(vm)

        vm.connect(serverId)
        waitUntil { vm.state.value.status == TerminalUiState.Status.Connected }

        assertEquals(TerminalUiState.Status.Connected, vm.state.value.status)
        assertFalse(vm.state.value.moshBacked)
        assertEquals(expectedText, vm.state.value.transportFallbackReason)
        waitUntil { notices.isNotEmpty() }
        assertEquals(
            listOf("Connected via SSH — mosh unavailable: $expectedText"),
            notices.toList(),
        )
        noticeJob.cancel()
    }
}
