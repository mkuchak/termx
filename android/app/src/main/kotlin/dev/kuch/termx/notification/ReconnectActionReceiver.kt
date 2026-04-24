package dev.kuch.termx.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kuch.termx.core.data.session.ReconnectBroker
import java.util.UUID
import javax.inject.Inject

/**
 * Reconnect action posted on `termx.disconnect` notifications.
 *
 * Posts the server id into [ReconnectBroker] so the matching
 * `TerminalViewModel` can react — decoupling the receiver from the
 * ViewModel side-steps the awkward "how do we call a ViewModel from a
 * BroadcastReceiver" question.
 *
 * Also cancels the source notification so the action chip doesn't stay
 * visible after the reconnect kicks off.
 */
@AndroidEntryPoint
class ReconnectActionReceiver : BroadcastReceiver() {

    @Inject lateinit var reconnectBroker: ReconnectBroker

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECONNECT) return
        val serverIdStr = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val serverId = runCatching { UUID.fromString(serverIdStr) }.getOrNull() ?: return

        reconnectBroker.requestReconnect(serverId)

        if (notificationId > 0) {
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            nm?.cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_RECONNECT = "dev.kuch.termx.action.TERMX_RECONNECT"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
