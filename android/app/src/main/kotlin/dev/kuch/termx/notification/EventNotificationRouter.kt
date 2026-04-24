package dev.kuch.termx.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.MainActivity
import dev.kuch.termx.R
import dev.kuch.termx.core.data.prefs.AlertPreferences
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.libs.companion.EventStreamClient
import dev.kuch.termx.libs.companion.events.TermxEvent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Subscribes to every live [EventStreamClient] published in
 * [EventStreamHub] and maps the resulting [TermxEvent] into one of the
 * four user-facing channels defined by [NotificationChannels].
 *
 * Event → channel mapping (Task #44 spec):
 *  - `PermissionRequested` → `termx.permission` with inline
 *    Approve / Deny / Open-app actions.
 *  - `ShellCommandLong` (over user threshold) → `termx.task`.
 *  - `ShellCommandError` → `termx.error`.
 *  - `DiffCreated` → `termx.task` with a Review action.
 *  - `SessionClosed` → `termx.disconnect` with a Reconnect action.
 *    MVP fires for every `SessionClosed`; future work disambiguates
 *    user-triggered exits from transport drops.
 *  - `ClaudeIdle` → `termx.task` ("Claude finished").
 *  - `SessionCreated`, `PermissionResolved` → no notification.
 *  - `Unknown` → log-only.
 *
 * Subscription lifecycle:
 *  - [start] opens a supervisor job that watches `clients` for
 *    add/remove. Each entry gets a per-server child job that collects
 *    `client.stream()`; when the entry is unpublished the child job is
 *    cancelled, which cancels the collect, which tears down the tail.
 *  - [stop] cancels the whole tree — called from
 *    `TermxForegroundService.onDestroy`.
 *
 * Per-server mute honouring: [AlertPreferences]' mute sets are read
 * *at emit time* (not subscription time) so toggling mute from a future
 * settings UI affects the next event immediately without restart.
 *
 * Notification id policy (matches spec):
 *  - Permission and diff notifications use `event.id.hashCode()` so
 *    every distinct event gets its own notification — we don't want a
 *    stacking stream where permission #2 overwrites #1.
 *  - Task / error / disconnect use stacking ids keyed on serverId +
 *    event type so a storm of errors coalesces into one notification
 *    with updated body.
 */
@Singleton
class EventNotificationRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hub: EventStreamHub,
    private val alertPreferences: AlertPreferences,
) {
    private var rootJob: Job? = null
    private val perServerJobs = mutableMapOf<UUID, Job>()

    /**
     * Open-app PendingIntent factory: the `requestCode` needs to be
     * unique per distinct target so Android doesn't collapse identical
     * PendingIntents and silently swap their extras. We hash
     * `serverId + approvalId` to keep the permission deep link
     * addressable.
     */
    private fun openAppIntent(requestCode: Int, approvalId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (approvalId != null) putExtra(EXTRA_APPROVAL_ID, approvalId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun start(scope: CoroutineScope) {
        if (rootJob != null) return
        rootJob = scope.launch(SupervisorJob()) {
            hub.clients.collect { snapshot ->
                // Drop any per-server jobs for servers that are gone.
                val goneIds = perServerJobs.keys - snapshot.keys
                goneIds.forEach { id ->
                    perServerJobs.remove(id)?.cancel()
                }
                // Spin up jobs for newly-added servers.
                snapshot.forEach { (id, entry) ->
                    if (perServerJobs.containsKey(id)) return@forEach
                    perServerJobs[id] = scope.launch {
                        runCatching {
                            entry.client.stream().collect { event ->
                                handle(entry.serverId, entry.serverLabel, event)
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        rootJob?.cancel()
        rootJob = null
        perServerJobs.values.forEach { it.cancel() }
        perServerJobs.clear()
    }

    private suspend fun handle(serverId: UUID, serverLabel: String, event: TermxEvent) {
        when (event) {
            is TermxEvent.PermissionRequested -> postPermission(serverId, serverLabel, event)
            is TermxEvent.ShellCommandLong -> {
                val threshold = alertPreferences.longCommandThresholdMs.first()
                if (event.durationMs > threshold && !isTaskMuted(serverId)) {
                    postTaskLong(serverId, serverLabel, event)
                }
            }
            is TermxEvent.ShellCommandError -> {
                if (!isErrorMuted(serverId)) postError(serverId, serverLabel, event)
            }
            is TermxEvent.DiffCreated -> {
                if (!isTaskMuted(serverId)) postDiff(serverId, serverLabel, event)
            }
            is TermxEvent.SessionClosed -> postDisconnect(serverId, serverLabel, event)
            is TermxEvent.ClaudeIdle -> {
                if (!isTaskMuted(serverId)) postClaudeIdle(serverId, serverLabel, event)
            }
            is TermxEvent.SessionCreated,
            is TermxEvent.PermissionResolved,
            is TermxEvent.ClaudeWorking,
            is TermxEvent.Unknown,
            -> { /* intentionally no notification */ }
        }
    }

    private suspend fun isTaskMuted(serverId: UUID): Boolean =
        alertPreferences.muteTasks.first().contains(serverId)

    private suspend fun isErrorMuted(serverId: UUID): Boolean =
        alertPreferences.muteErrors.first().contains(serverId)

    private fun postPermission(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.PermissionRequested,
    ) {
        val notificationId = event.requestId.hashCode()
        val approveIntent = PendingIntent.getBroadcast(
            context,
            ("approve:${event.requestId}").hashCode(),
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ApprovalActionReceiver.ACTION_APPROVE
                putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, event.requestId)
                putExtra(ApprovalActionReceiver.EXTRA_SERVER_ID, serverId.toString())
                putExtra(ApprovalActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            ("deny:${event.requestId}").hashCode(),
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ApprovalActionReceiver.ACTION_DENY
                putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, event.requestId)
                putExtra(ApprovalActionReceiver.EXTRA_SERVER_ID, serverId.toString())
                putExtra(ApprovalActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = openAppIntent(
            requestCode = ("open:${event.requestId}").hashCode(),
            approvalId = event.requestId,
        )

        val notification = NotificationCompat.Builder(context, NotificationChannels.PERMISSION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Claude needs approval")
            .setContentText("${event.toolName} on $serverLabel")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "Approve", approveIntent)
            .addAction(0, "Deny", denyIntent)
            .addAction(0, "Open app", openIntent)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun postTaskLong(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.ShellCommandLong,
    ) {
        val notificationId = stackingId(serverId, "task_long")
        val openIntent = openAppIntent(notificationId)
        val seconds = (event.durationMs / 1000).coerceAtLeast(1)
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Command finished on $serverLabel")
            .setContentText("${event.cmd} (${seconds}s)")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun postError(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.ShellCommandError,
    ) {
        val notificationId = stackingId(serverId, "error")
        val openIntent = openAppIntent(notificationId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERROR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Command failed on $serverLabel")
            .setContentText("${event.cmd} (exit ${event.exitCode})")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun postDiff(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.DiffCreated,
    ) {
        val notificationId = event.diffId.hashCode()
        val reviewIntent = openAppIntent(
            requestCode = ("diff:${event.diffId}").hashCode(),
            approvalId = event.diffId,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("New diff on $serverLabel")
            .setContentText("Claude edited ${event.filePath}")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(reviewIntent)
            .setAutoCancel(true)
            .addAction(0, "Review", reviewIntent)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun postDisconnect(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.SessionClosed,
    ) {
        val notificationId = stackingId(serverId, "disconnect")
        val reconnectIntent = PendingIntent.getBroadcast(
            context,
            ("reconnect:$serverId").hashCode(),
            Intent(context, ReconnectActionReceiver::class.java).apply {
                action = ReconnectActionReceiver.ACTION_RECONNECT
                putExtra(ReconnectActionReceiver.EXTRA_SERVER_ID, serverId.toString())
                putExtra(ReconnectActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = openAppIntent(notificationId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.DISCONNECT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Session dropped — $serverLabel")
            .setContentText("tmux session ${event.session} disconnected")
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .addAction(0, "Reconnect", reconnectIntent)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun postClaudeIdle(
        serverId: UUID,
        serverLabel: String,
        event: TermxEvent.ClaudeIdle,
    ) {
        val notificationId = stackingId(serverId, "claude_idle")
        val openIntent = openAppIntent(notificationId)
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Claude finished")
            .setContentText("$serverLabel · ${event.session}")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        nm()?.notify(notificationId, notification)
    }

    private fun stackingId(serverId: UUID, bucket: String): Int =
        ("$serverId:$bucket").hashCode()

    private fun nm(): NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    companion object {
        /** Extra surfaced on deep-link intents so MainActivity can route. */
        const val EXTRA_APPROVAL_ID = "TERMX_APPROVAL_ID"
    }
}
