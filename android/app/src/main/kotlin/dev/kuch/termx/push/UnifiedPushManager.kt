package dev.kuch.termx.push

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AlertPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Process-wide controller for the Tier-2 UnifiedPush registration lifecycle.
 *
 * Pairs with [TermxPushService] (which receives the endpoint + pushes): this
 * class owns the *outbound* side — picking a distributor (the ntfy app), asking
 * it to register termx, and tearing that down. NO Google FCM is involved; the
 * distributor delivers pushes peer-to-peer.
 *
 * Surface contract (consumed by the settings screen, Task #25):
 *  - [distributors] / [ackDistributor] feed the picker + status row, and let
 *    the UI show an "install ntfy" CTA when [distributors] is empty.
 *  - [choose] saves the user's pick and (re)registers.
 *  - [enable] / [disable] flip the [AlertPreferences.unifiedPushEnabled] master
 *    switch and register/unregister accordingly; both are `suspend` because
 *    they touch DataStore, so the VM calls them from a coroutine.
 *  - [ensureRegisteredIfEnabled] is the cold-start hook (see TermxApplication):
 *    re-asserts the registration on every launch when push is enabled, since a
 *    distributor only keeps us subscribed while we keep asking.
 *
 * API note: this targets connector **3.x** — `register` / `unregister` /
 * `getDistributors` / `getAckDistributor` / `saveDistributor` /
 * `tryUseCurrentOrDefaultDistributor` on the [UnifiedPush] object. The 2.x
 * `registerApp` / `getDistributor` / `forceRegister` names are gone (or
 * deprecated aliases) and are deliberately not used.
 */
@Singleton
class UnifiedPushManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertPreferences: AlertPreferences,
) {

    /**
     * Cold-start re-registration. No-op unless the user has opted in.
     *
     * Reads the master switch once, synchronously — the same `runBlocking {
     * …first() }` bridge `TermxPushService` uses for its `suspend` pref I/O,
     * so this can be called straight from `Application.onCreate`. The window
     * is a single DataStore read, not a network round-trip.
     *
     * When enabled we ask the connector to reuse the current/last distributor
     * (or the system default) and only [register] if that handshake reports
     * success — so a user who never picked a distributor stays silent rather
     * than getting a spurious failure callback.
     */
    fun ensureRegisteredIfEnabled() {
        val enabled = runBlocking { alertPreferences.unifiedPushEnabled.first() }
        if (!enabled) return
        // Overload taking an app Context is @Deprecated in favour of the
        // Activity one, but the Activity variant is only for the in-UI
        // deep-link picker; from Application.onCreate we have no Activity and
        // explicitly want the headless "reuse current/default" path.
        @Suppress("DEPRECATION")
        UnifiedPush.tryUseCurrentOrDefaultDistributor(context) { ok ->
            if (ok) UnifiedPush.register(context, messageForDistributor = MESSAGE_FOR_DISTRIBUTOR)
        }
    }

    /** Installed UnifiedPush distributors (package names); empty → none installed. */
    fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    /** The distributor that has acknowledged our registration, or null if none. */
    fun ackDistributor(): String? = UnifiedPush.getAckDistributor(context)

    /** Persist the user's distributor choice and (re)register against it. */
    fun choose(pkg: String) {
        UnifiedPush.saveDistributor(context, pkg)
        UnifiedPush.register(context, messageForDistributor = MESSAGE_FOR_DISTRIBUTOR)
    }

    /** Turn push on: flip the master switch, then assert the registration. */
    suspend fun enable() {
        alertPreferences.setUnifiedPushEnabled(true)
        ensureRegisteredIfEnabled()
    }

    /** Turn push off: tear down the registration, then flip the master switch off. */
    suspend fun disable() {
        UnifiedPush.unregister(context)
        alertPreferences.setUnifiedPushEnabled(false)
    }

    private companion object {
        /** Shown by the distributor UI to identify this registration. */
        private const val MESSAGE_FOR_DISTRIBUTOR = "termx"
    }
}
