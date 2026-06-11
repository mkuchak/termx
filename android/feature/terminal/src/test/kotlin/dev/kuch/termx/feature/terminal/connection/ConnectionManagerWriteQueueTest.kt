package dev.kuch.termx.feature.terminal.connection

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.RemoteTerminalSession
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
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.PtyChannel
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Task #52: the per-shell FIFO write queue.
 * [ConnectionManagerBehaviorTest] (6) pins the writeErrors DROP_OLDEST
 * surfacing contract; what nothing pinned — because nothing guaranteed
 * it — is ORDER. `writeToPty` used to fire an independent
 * `scope.launch(Dispatchers.IO)` per call, so two rapid writes could
 * land on the transport reordered. This suite locks the queue contract
 * the PTT two-phase submit (text → delay → lone CR) builds on:
 *
 *  (1) strict FIFO: 100 rapid writeToPty calls arrive at the PTY in
 *      exact submission order, even when every write suspends — the
 *      exact shape that made the per-call-launch implementation
 *      interleave;
 *  (2) a mid-stream write failure surfaces on writeErrors AND the
 *      drainer survives it — subsequent writes still flow, still in
 *      order;
 *  (3) teardown mid-burst neither deadlocks nor crashes, the dead
 *      queue stops draining, and writes queued against the dead shell
 *      NEVER land on the next shell's transport after a reconnect
 *      (they drop, like the old null-pty behavior did).
 *
 * Task #53 builds the PTT submit on that contract; the second half of
 * this suite pins `submitLine` END TO END against live transports:
 *
 *  (4) text-then-lone-CR travels as ONE channel element — a concurrent
 *      writeToPty burst can land before or after, never between;
 *  (5) the CR delay is transport-sized in real time (>=75ms on an
 *      ssh-backed shell, >=300ms on a mosh-backed one);
 *  (6) the text is ESC[200~/201~-wrapped exactly while the REAL
 *      vendored emulator reports DECSET 2004 on, clean once off.
 *
 * (The exact step bytes/delays are unit-pinned in
 * [SubmitLineSequenceTest]; here we prove the wiring.)
 *
 * Setup matches the sibling suites: Robolectric + real-time polling
 * (the drainer lives on Dispatchers.IO), shared looper-backed Main +
 * scope quiescence via [QuiescentMainDispatcherRule], main-looper
 * idling in [waitUntil].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConnectionManagerWriteQueueTest {

    @get:Rule
    val mainRule = QuiescentMainDispatcherRule()

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private val serverId = UUID.fromString("77777777-7777-7777-7777-777777777777")

    @Before
    fun resetPrefs() = runBlocking {
        // DataStore files survive across tests in the same Robolectric
        // VM; keep UnifiedPush off so no test does endpoint writes.
        AlertPreferences(appContext).setUnifiedPushEnabled(false)
        AlertPreferences(appContext).setUnifiedPushEndpoint("")
    }

    private fun server(useMosh: Boolean = false) = Server(
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

    /**
     * [moshSession] non-null (+ `useMosh = true` on the server row)
     * routes the connect down the mosh path — the Task #53 submitLine
     * tests need a mosh-backed shell to pin the 300ms CR delay.
     */
    private fun newManager(
        sshClient: FakeSshClient,
        moshSession: MoshSession? = null,
        useMosh: Boolean = false,
    ): ConnectionManager = ConnectionManager(
        appContext = appContext,
        serverRepository = FakeServerRepository().apply { put(server(useMosh)) },
        keyPairRepository = FakeKeyPairRepository(),
        secretVault = FakeSecretVault(),
        passwordCache = PasswordCache().apply { put(serverId, "pw") },
        alertPreferences = AlertPreferences(appContext),
        sessionRegistry = SessionRegistry(),
        reconnectBroker = ReconnectBroker(),
        eventStreamHub = EventStreamHub(),
        eventStreamRepository = EventStreamRepository(EventStreamClientFactory()),
        companionUpdateRepository = mockk(relaxed = true),
        moshClient = FakeMoshClient(appContext, sshClient, session = moshSession),
        sshClient = sshClient,
    ).also { mainRule.track(it) }

    /** Same drain-the-main-looper poll as the sibling suites. */
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12_000L
        while (!predicate() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /**
     * Recording PTY whose `write` SUSPENDS for [writeDelayMs] before
     * recording — the shape that made the old per-call-launch
     * implementation reorder (every concurrent launch parked in the
     * same delay, then raced to the record line). [failOn] payloads
     * throw instead of recording, modelling a transient mid-stream
     * transport hiccup. Output parks forever like the stock fake so the
     * session stays Connected.
     */
    private class RecordingPty(
        private val writeDelayMs: Long = 0L,
        private val failOn: (String) -> Boolean = { false },
    ) : PtyChannel {
        val writes = CopyOnWriteArrayList<String>()

        /**
         * nanoTime at the moment each `writes` entry was recorded —
         * what the Task #53 submitLine tests measure the pre-CR delay
         * against (index-aligned with [writes]).
         */
        val stampsNanos = CopyOnWriteArrayList<Long>()
        val closed = AtomicInteger(0)
        override val output: Flow<ByteArray> = MutableSharedFlow<ByteArray>().asSharedFlow()
        override suspend fun write(bytes: ByteArray) {
            if (writeDelayMs > 0) delay(writeDelayMs)
            val payload = bytes.toString(Charsets.UTF_8)
            if (failOn(payload)) throw IOException("simulated mid-stream failure on $payload")
            writes += payload
            stampsNanos += System.nanoTime()
        }
        override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
        override fun close() {
            closed.incrementAndGet()
        }
    }

    /**
     * Mosh sibling of [RecordingPty] for the submitLine transport-delay
     * tests: emits one greeting chunk so the manager's first-output
     * liveness gate passes (mirroring [FakeMoshSession]), then parks;
     * records every stdin write with a timestamp.
     */
    private class RecordingMoshSession : MoshSession {
        val writes = CopyOnWriteArrayList<String>()
        val stampsNanos = CopyOnWriteArrayList<Long>()
        override val output: Flow<ByteArray> = flow {
            emit("mosh-screen".toByteArray(Charsets.UTF_8))
            awaitCancellation()
        }
        override val diagnostic: StateFlow<MoshDiagnostic> =
            MutableStateFlow(MoshDiagnostic(exitCode = null, elapsedMs = 0, head = ""))
        override suspend fun write(bytes: ByteArray) {
            writes += bytes.toString(Charsets.UTF_8)
            stampsNanos += System.nanoTime()
        }
        override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
        override fun close() { /* no-op */ }
    }

    /** [FakeSshClient] minting one [RecordingPty] per dialled session. */
    private class PtyMintingSshClient(
        private val mint: () -> RecordingPty,
    ) : FakeSshClient() {
        val ptys = CopyOnWriteArrayList<RecordingPty>()
        override fun newSession(): FakeSshSession = object : FakeSshSession() {
            override suspend fun openShell(
                term: String,
                cols: Int,
                rows: Int,
                command: String?,
            ): PtyChannel = mint().also { ptys += it }
        }
    }

    // ── (1) strict FIFO under a rapid burst ──
    //
    // 100 back-to-back writeToPty calls against a pty whose write
    // suspends 1ms each. Under the old per-call launches this is a
    // 100-way race on Dispatchers.IO; under the queue the only legal
    // outcome is exact submission order.
    @Test
    fun hundredRapidWrites_arriveInExactSubmissionOrder() {
        val sshClient = PtyMintingSshClient { RecordingPty(writeDelayMs = 1L) }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val expected = (0 until 100).map { "w%03d".format(it) }
        expected.forEach { manager.writeToPty(serverId, it.toByteArray(Charsets.UTF_8)) }

        val pty = sshClient.ptys.single()
        waitUntil { pty.writes.size == expected.size }
        assertEquals(
            "writes must reach the PTY in exact submission order",
            expected,
            pty.writes.toList(),
        )
    }

    // ── (2) mid-stream failure: surfaces, doesn't kill the drainer ──
    //
    // One poisoned payload throws inside the drainer. The failure must
    // surface on writeErrors (replay=0 — the collector parks BEFORE the
    // failure) and every subsequent write must still flow, in order.
    @Test
    fun midStreamWriteFailure_surfacesOnWriteErrors_andSubsequentWritesStillFlow() {
        val sshClient = PtyMintingSshClient { RecordingPty(failOn = { it == "boom" }) }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val errors = CopyOnWriteArrayList<String>()
        val collectScope = CoroutineScope(Dispatchers.Unconfined)
        collectScope.launch { conn.writeErrors.collect { errors += it } }

        manager.writeToPty(serverId, "first".toByteArray(Charsets.UTF_8))
        manager.writeToPty(serverId, "boom".toByteArray(Charsets.UTF_8))
        manager.writeToPty(serverId, "after-1".toByteArray(Charsets.UTF_8))
        manager.writeToPty(serverId, "after-2".toByteArray(Charsets.UTF_8))

        val pty = sshClient.ptys.single()
        waitUntil { pty.writes.size == 3 && errors.isNotEmpty() }
        assertEquals(
            "writes after the failure must still flow, in order",
            listOf("first", "after-1", "after-2"),
            pty.writes.toList(),
        )
        assertEquals("exactly one failure surfaced", 1, errors.size)
        assertTrue(errors.first().contains("Send failed"))
        collectScope.cancel()
    }

    // ── (3) teardown mid-burst: no deadlock, no crash, no leak onto
    //        the next shell ──
    //
    // 200 writes at 2ms each = ~400ms of queued work; disconnect() lands
    // while the drainer is provably mid-burst. The contract: teardown
    // completes (no deadlock — waitUntil + the rule's quiescence join
    // would both trip otherwise), the dead queue stops draining, and a
    // reconnect's fresh shell receives ONLY post-reconnect writes —
    // input queued against a dead shell drops, exactly like the old
    // null-pty behavior, and never lands on the next transport
    // mid-handshake.
    @Test
    fun disconnectMidBurst_noDeadlock_deadQueueStops_nothingLandsOnNextShell() {
        val sshClient = PtyMintingSshClient { RecordingPty(writeDelayMs = 2L) }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        repeat(200) { manager.writeToPty(serverId, "burst-$it".toByteArray(Charsets.UTF_8)) }
        manager.disconnect(serverId)

        waitUntil { conn.state.value is TransportState.Disconnected }
        assertTrue(conn.state.value is TransportState.Disconnected)
        val firstPty = sshClient.ptys.single()
        assertTrue("old transport must be closed", firstPty.closed.get() >= 1)
        assertTrue(
            "the burst must have been cut short by teardown, drained=${firstPty.writes.size}",
            firstPty.writes.size < 200,
        )
        // A write against the torn-down slot drops silently (shell is
        // null) — no crash, nothing recorded anywhere.
        manager.writeToPty(serverId, "into-the-void".toByteArray(Charsets.UTF_8))

        // The dead drainer must be CANCELLED, not just starved: give any
        // in-flight step time to settle, then assert the drained set is
        // frozen.
        Thread.sleep(100)
        val drainedAtTeardown = firstPty.writes.size
        Thread.sleep(100)
        assertEquals(
            "a dead shell's queue must stop draining",
            drainedAtTeardown,
            firstPty.writes.size,
        )

        // Reconnect: fresh shell, fresh queue. None of the dead burst
        // (nor the into-the-void write) may surface here.
        manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected && sshClient.ptys.size == 2 }
        manager.writeToPty(serverId, "fresh".toByteArray(Charsets.UTF_8))

        val secondPty = sshClient.ptys[1]
        waitUntil { secondPty.writes.isNotEmpty() }
        assertEquals(
            "the next shell must see ONLY post-reconnect writes",
            listOf("fresh"),
            secondPty.writes.toList(),
        )
        assertEquals(drainedAtTeardown, firstPty.writes.size)
    }

    // ── (4) submitLine: text + lone CR is ONE atomic element ──
    //
    // A >=64-char transcript (the real-world shape that exposed the
    // Claude Code tokenizer bug) is submitted while 100 raw writes
    // pour in from another thread. The 75ms pre-CR delay holds the
    // drainer INSIDE the submit element for a long, provable window —
    // if the CR were enqueued as its own element (e.g. via three
    // separate paste() writes), burst writes would slip between text
    // and CR. The only legal layout: CR immediately follows its text.
    @Test
    fun submitLine_textThenLoneCr_atomicAgainstConcurrentWriteToPtyBurst() {
        val sshClient = PtyMintingSshClient { RecordingPty() }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        val transcript =
            "type check the project then run the unit tests and commit the result"
        assertTrue("fixture must model the >=64-char shape", transcript.length >= 64)
        val burst = (0 until 100).map { "burst-%03d".format(it) }

        val burstThread = Thread {
            burst.forEach { manager.writeToPty(serverId, it.toByteArray(Charsets.UTF_8)) }
        }
        manager.submitLine(serverId, transcript)
        burstThread.start()
        burstThread.join()

        val pty = sshClient.ptys.single()
        waitUntil { pty.writes.size == burst.size + 2 }
        val writes = pty.writes.toList()
        val textIdx = writes.indexOf(transcript)
        assertTrue("the transcript must reach the pty (writes=$writes)", textIdx >= 0)
        assertEquals(
            "the lone CR must IMMEDIATELY follow its text — nothing may interleave",
            "\r",
            writes[textIdx + 1],
        )
        assertEquals(
            "exactly one CR — the submit's own",
            1,
            writes.count { it == "\r" },
        )
        assertEquals(
            "the burst keeps its own FIFO order around the atomic pair",
            burst,
            writes.filter { it.startsWith("burst-") },
        )
    }

    // ── (5) submitLine: transport-sized CR delay, measured for real ──

    @Test
    fun submitLine_sshBacked_delaysTheLoneCrBySshInterval() {
        val sshClient = PtyMintingSshClient { RecordingPty() }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }

        manager.submitLine(serverId, "git push origin main")

        val pty = sshClient.ptys.single()
        waitUntil { pty.writes.size == 2 }
        assertEquals(listOf("git push origin main", "\r"), pty.writes.toList())
        val gapMs = (pty.stampsNanos[1] - pty.stampsNanos[0]) / 1_000_000
        assertTrue(
            "CR must wait >= ${SSH_SUBMIT_CR_DELAY_MS}ms after the text, waited ${gapMs}ms",
            gapMs >= SSH_SUBMIT_CR_DELAY_MS,
        )
        // Upper bound proves ssh got the SSH delay, not the mosh one.
        // 225ms of slack between two in-memory list appends — generous
        // enough that only a wrongly-picked constant can trip it.
        assertTrue(
            "ssh-backed submit must NOT use the mosh delay, waited ${gapMs}ms",
            gapMs < MOSH_SUBMIT_CR_DELAY_MS,
        )
    }

    @Test
    fun submitLine_moshBacked_delaysTheLoneCrByMoshInterval() {
        val mosh = RecordingMoshSession()
        val manager = newManager(
            PtyMintingSshClient { RecordingPty() },
            moshSession = mosh,
            useMosh = true,
        )

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        assertTrue(
            "test must run on the mosh transport",
            (conn.state.value as TransportState.Connected).moshBacked,
        )

        manager.submitLine(serverId, "deploy the staging branch")

        waitUntil { mosh.writes.size == 2 }
        assertEquals(listOf("deploy the staging branch", "\r"), mosh.writes.toList())
        val gapMs = (mosh.stampsNanos[1] - mosh.stampsNanos[0]) / 1_000_000
        assertTrue(
            "mosh coalesces input into ~250ms frames — CR must wait >= " +
                "${MOSH_SUBMIT_CR_DELAY_MS}ms, waited ${gapMs}ms",
            gapMs >= MOSH_SUBMIT_CR_DELAY_MS,
        )
    }

    // ── (6) submitLine: live DECSET 2004 detection on the REAL emulator ──
    //
    // Not the pure-function fake flag: the remote app's actual
    // `ESC[?2004h` / `ESC[?2004l` bytes flow through feedRemoteBytes
    // into the vendored TerminalEmulator, and submitLine must read the
    // resulting mode back out (via the termx-added
    // isBracketedPasteMode accessor) per submit.
    @Test
    fun submitLine_wrapsInPasteMarkers_exactlyWhileRemoteBracketedPasteIsOn() {
        val sshClient = PtyMintingSshClient { RecordingPty() }
        val manager = newManager(sshClient)

        val conn = manager.connect(serverId)
        waitUntil { conn.state.value is TransportState.Connected }
        val session =
            (conn.state.value as TransportState.Connected).session as RemoteTerminalSession

        // Materialize the emulator the way a TerminalView attach does
        // (no view in Robolectric), then feed the remote's enable
        // sequence through the normal output path. The Robolectric
        // test thread IS the main-looper thread, so idling the main
        // looper applies the posted append before we submit.
        session.updateSize(80, 24, 12, 24)
        session.feedRemoteBytes("\u001B[?2004h".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        manager.submitLine(serverId, "summarize the failing tests")
        val pty = sshClient.ptys.single()
        waitUntil { pty.writes.size == 2 }
        assertEquals(
            "Claude-Code-style targets (DECSET 2004 on) get the wrapped text",
            listOf("\u001B[200~summarize the failing tests\u001B[201~", "\r"),
            pty.writes.toList(),
        )

        // The remote turns it off (raw-mode app exits back to bash) —
        // the very next submit must be clean, unwrapped text.
        session.feedRemoteBytes("\u001B[?2004l".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        manager.submitLine(serverId, "ls -la")
        waitUntil { pty.writes.size == 4 }
        assertEquals(
            "a plain prompt (DECSET 2004 off) must get clean text — no marker garbage",
            listOf("ls -la", "\r"),
            pty.writes.toList().drop(2),
        )
    }
}
