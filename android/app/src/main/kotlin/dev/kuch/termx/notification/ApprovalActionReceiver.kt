package dev.kuch.termx.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.libs.companion.events.CompanionCommand
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the inline [Approve] / [Deny] actions posted on every
 * `termx.permission` notification.
 *
 * Intent contract:
 *  - Action: [ACTION_APPROVE] or [ACTION_DENY]
 *  - Extra [EXTRA_REQUEST_ID]: the `request_id` from the permission event
 *  - Extra [EXTRA_SERVER_ID]: stringified UUID of the server whose
 *    `EventStreamClient` should carry the resolution command
 *  - Extra [EXTRA_NOTIFICATION_ID]: system id to cancel after posting,
 *    so a second tap on a duplicate notification doesn't fire the command
 *
 * We do the SFTP write off the main thread via a small supervising
 * scope. BroadcastReceivers have ~10 s before the system ANRs them; an
 * SFTP rename is well under that on any healthy network, but pending
 * command delivery isn't time-critical enough to warrant blocking the
 * receiver — firing the scope and returning is fine.
 */
@AndroidEntryPoint
class ApprovalActionReceiver : BroadcastReceiver() {

    @Inject lateinit var eventStreamHub: EventStreamHub

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val serverIdStr = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val serverId = runCatching { UUID.fromString(serverIdStr) }.getOrNull() ?: return
        val client = eventStreamHub.clients.value[serverId]?.client
        if (client == null) {
            Log.w(TAG, "No live EventStreamClient for $serverId; dropping $action")
        } else {
            val command = when (action) {
                ACTION_APPROVE -> CompanionCommand.ApprovePermission(
                    id = UUID.randomUUID().toString(),
                    requestId = requestId,
                    remember = false,
                )
                ACTION_DENY -> CompanionCommand.DenyPermission(
                    id = UUID.randomUUID().toString(),
                    requestId = requestId,
                    reason = "Denied from notification",
                )
                else -> null
            }
            if (command != null) {
                // Firing without goAsync is deliberate: command delivery
                // is best-effort from a notification action, the router
                // will re-fire a toast via its `errors` flow if the SFTP
                // hop fails, and we don't want to hold the BroadcastReceiver
                // lifetime open against network jitter.
                scope.launch {
                    runCatching { client.sendCommand(command) }
                        .onFailure { Log.w(TAG, "sendCommand failed", it) }
                }
            }
        }

        if (notificationId > 0) {
            val nm = ContextCompat.getSystemService(context, NotificationManager::class.java)
            nm?.cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_APPROVE = "dev.kuch.termx.action.TERMX_APPROVE"
        const val ACTION_DENY = "dev.kuch.termx.action.TERMX_DENY"
        const val EXTRA_REQUEST_ID = "approval_id"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TAG = "ApprovalActionReceiver"
    }
}
