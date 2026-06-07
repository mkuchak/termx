package dev.kuch.termx.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.libs.companion.events.TermxEvent
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Regression coverage for the "supersede footgun": [EventNotificationRouter]
 * must suppress the Tier-1 in-app agent-finished alert ONLY when Tier 2
 * (UnifiedPush) is *genuinely deliverable*, never merely because the
 * `unifiedPushEnabled` pref is on. Otherwise a half-configured user (pref ON
 * but no distributor/endpoint) gets Tier 1 suppressed AND no Tier 2 = total
 * silence, breaking the router's "exactly one alert" contract.
 *
 * Wiring (the app module's first unit-test source set, mirroring
 * core/data's Robolectric pattern):
 *  - real [AlertPreferences] on the Robolectric [Context] so the three
 *    signal prefs round-trip through DataStore;
 *  - a MockK [AgentAlertPoster] so we can assert whether `post()` fired —
 *    that boolean IS the Tier-1 fire/suppress decision;
 *  - `mockkStatic(UnifiedPush::class)` to stub the live
 *    `getAckDistributor(context)` lookup (the third signal).
 *
 * DataStore is a per-(Context, name) JVM singleton that bleeds state across
 * tests, so [setUp] resets every signal/gate pref to a known baseline.
 *
 * Truth table (does AgentAlertPoster.post fire?):
 *  T1 disabled / "" / null distributor                 → FIRES
 *  T2 enabled  / endpoint / distributor                → SUPPRESSED (only case)
 *  T3 enabled  / "" / null distributor (THE guard)     → FIRES
 *  T4 enabled  / "" / distributor                      → FIRES
 *  T5 enabled  / endpoint / null distributor           → FIRES
 *  T6 deliverable but agentFinishedEnabled=false        → SUPPRESSED (supersede precedes master switch)
 *  T7 deliverable but server in agentFinishedMuted      → SUPPRESSED
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EventNotificationRouterSupersedeTest {

    private lateinit var context: Context
    private lateinit var alertPreferences: AlertPreferences
    private lateinit var poster: AgentAlertPoster
    private lateinit var router: EventNotificationRouter

    private val serverId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val serverLabel = "prod-1"

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        alertPreferences = AlertPreferences(context)
        poster = mockk(relaxed = true)
        val hub: EventStreamHub = mockk(relaxed = true)
        router = EventNotificationRouter(context, hub, alertPreferences, poster)

        // Reset the JVM-singleton DataStore to a known baseline: Tier-2
        // signals off, Tier-1 gates open (master enabled, nothing muted).
        alertPreferences.setUnifiedPushEnabled(false)
        alertPreferences.setUnifiedPushEndpoint("")
        alertPreferences.setAgentFinishedEnabled(true)
        alertPreferences.setAgentFinishedMuted(serverId, false)

        // Stub the live distributor lookup; per-test overrides below.
        mockkStatic(UnifiedPush::class)
        every { UnifiedPush.getAckDistributor(any()) } returns null
    }

    @After fun tearDown() {
        unmockkStatic(UnifiedPush::class)
    }

    /** Apply the three Tier-2 signals, then drive one agent-finished event. */
    private suspend fun emit(
        enabled: Boolean,
        endpoint: String,
        distributor: String?,
    ) {
        alertPreferences.setUnifiedPushEnabled(enabled)
        alertPreferences.setUnifiedPushEndpoint(endpoint)
        every { UnifiedPush.getAckDistributor(any()) } returns distributor

        val event: TermxEvent.AgentFinished = mockk(relaxed = true)
        every { event.agent } returns "claude"
        every { event.workspace } returns "~/work"
        router.handle(serverId, serverLabel, event)
    }

    private fun assertPosted() = coVerify(exactly = 1) {
        poster.post(serverId, serverLabel, "claude", "~/work")
    }

    private fun assertNotPosted() = coVerify(exactly = 0) {
        poster.post(any(), any(), any(), any())
    }

    // ---- predicate, asserted directly ---------------------------------

    @Test fun `predicate false when UnifiedPush disabled`() = runTest {
        alertPreferences.setUnifiedPushEnabled(false)
        alertPreferences.setUnifiedPushEndpoint("https://ntfy.example/x")
        every { UnifiedPush.getAckDistributor(any()) } returns "org.ntfy"
        assertFalse(router.tier2GenuinelyDeliverable())
    }

    @Test fun `predicate false when endpoint blank`() = runTest {
        alertPreferences.setUnifiedPushEnabled(true)
        alertPreferences.setUnifiedPushEndpoint("")
        every { UnifiedPush.getAckDistributor(any()) } returns "org.ntfy"
        assertFalse(router.tier2GenuinelyDeliverable())
    }

    @Test fun `predicate false when no acknowledged distributor`() = runTest {
        alertPreferences.setUnifiedPushEnabled(true)
        alertPreferences.setUnifiedPushEndpoint("https://ntfy.example/x")
        every { UnifiedPush.getAckDistributor(any()) } returns null
        assertFalse(router.tier2GenuinelyDeliverable())
    }

    @Test fun `predicate true only when all three signals line up`() = runTest {
        alertPreferences.setUnifiedPushEnabled(true)
        alertPreferences.setUnifiedPushEndpoint("https://ntfy.example/x")
        every { UnifiedPush.getAckDistributor(any()) } returns "org.ntfy"
        assertTrue(router.tier2GenuinelyDeliverable())
    }

    // ---- truth table, behavioural (did post() fire?) ------------------

    @Test fun `T1 - all signals off - Tier-1 FIRES`() = runTest {
        emit(enabled = false, endpoint = "", distributor = null)
        assertPosted()
    }

    @Test fun `T2 - genuinely deliverable - Tier-1 SUPPRESSED`() = runTest {
        emit(enabled = true, endpoint = "https://ntfy.example/x", distributor = "org.ntfy")
        assertNotPosted()
    }

    @Test fun `T3 - enabled but no endpoint or distributor - Tier-1 FIRES (the regression guard)`() = runTest {
        // RED on the old `if (unifiedPushEnabled) return` guard, GREEN now.
        emit(enabled = true, endpoint = "", distributor = null)
        assertPosted()
    }

    @Test fun `T4 - enabled with distributor but no endpoint - Tier-1 FIRES`() = runTest {
        emit(enabled = true, endpoint = "", distributor = "org.ntfy")
        assertPosted()
    }

    @Test fun `T5 - enabled with endpoint but no distributor - Tier-1 FIRES`() = runTest {
        emit(enabled = true, endpoint = "https://ntfy.example/x", distributor = null)
        assertPosted()
    }

    @Test fun `T6 - deliverable but master switch off - SUPPRESSED (supersede precedes master switch)`() = runTest {
        alertPreferences.setAgentFinishedEnabled(false)
        emit(enabled = true, endpoint = "https://ntfy.example/x", distributor = "org.ntfy")
        assertNotPosted()
    }

    @Test fun `T7 - deliverable but server muted - SUPPRESSED`() = runTest {
        alertPreferences.setAgentFinishedMuted(serverId, true)
        emit(enabled = true, endpoint = "https://ntfy.example/x", distributor = "org.ntfy")
        assertNotPosted()
    }
}
