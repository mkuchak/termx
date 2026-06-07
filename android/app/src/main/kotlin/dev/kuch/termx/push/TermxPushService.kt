package dev.kuch.termx.push

import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.notification.AgentAlertPoster
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Tier-2 UnifiedPush integration point for "agent finished" alerts.
 *
 * The ntfy UnifiedPush distributor delivers pushes here even when termx is
 * swipe-killed or freshly rebooted — no Google FCM involved. We use the
 * connector 3.x [PushService] (a Service, not the obsolete 2.x
 * MessagingReceiver/BroadcastReceiver) whose single intent-filter action is
 * `org.unifiedpush.android.connector.PUSH_EVENT` (declared in the manifest).
 *
 * Lifecycle callbacks (all driven by the distributor):
 *  - [onNewEndpoint]: persist the distributor endpoint so the VPS can be
 *    synced with it on the next connect.
 *  - [onMessage]: decode the push payload and post the same agent-finished
 *    notification (channel + strong vibration) as Tier 1, via
 *    [AgentAlertPoster.postRaw]. A plaintext POST arrives with
 *    `decrypted == false` and the raw bytes in [PushMessage.content]; that
 *    is fine — we just decode UTF-8.
 *  - [onUnregistered]: clear the stored endpoint.
 *
 * [AgentAlertPoster.postRaw] and [AlertPreferences.setUnifiedPushEndpoint]
 * are `suspend`, and these callbacks run on the connector's binder thread,
 * so we bridge with [runBlocking] (mirrors the existing endpoint-store flow).
 *
 * `@AndroidEntryPoint` enables Hilt field injection into the Service.
 */
@AndroidEntryPoint
class TermxPushService : PushService() {

    @Inject lateinit var alertPreferences: AlertPreferences

    @Inject lateinit var poster: AgentAlertPoster

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // VPS sync happens on the next connect; here we just persist it.
        runBlocking { alertPreferences.setUnifiedPushEndpoint(endpoint.url) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val text = message.content.toString(Charsets.UTF_8)
        // Same channel + strong vibration as the Tier-1 in-connection alert.
        runBlocking { poster.postRaw(title = "herdr", body = text) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w(TAG, "UnifiedPush registration failed (instance=$instance): $reason")
    }

    override fun onUnregistered(instance: String) {
        runBlocking { alertPreferences.setUnifiedPushEndpoint("") }
    }

    private companion object {
        private const val TAG = "TermxPushService"
    }
}
