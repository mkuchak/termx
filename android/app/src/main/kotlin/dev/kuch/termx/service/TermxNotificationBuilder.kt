package dev.kuch.termx.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.MainActivity
import dev.kuch.termx.R
import dev.kuch.termx.core.data.session.SessionRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the persistent "active sessions" notification shown while the
 * foreground service is alive.
 *
 * Kept as a separate `@Singleton` so the service composes it, and a
 * later task (#44 event router) can reuse it when the session set
 * updates mid-event without re-instantiating.
 *
 * Channel strategy: one low-importance channel for the persistent
 * notification ([CHANNEL_SERVICE]). Event-driven alerts (permission
 * request, task done, error, disconnect) live in their own channels
 * that Task #44 will add — keeping them separate so the user can
 * silence the persistent chrome without losing alerts.
 */
@Singleton
class TermxNotificationBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun build(entries: List<SessionRegistry.Entry>): android.app.Notification {
        ensureChannel()

        val tabs = entries.size
        val servers = entries.map { it.serverId }.toSet().size
        val title = if (entries.isEmpty()) {
            "termx"
        } else {
            val tabWord = if (tabs == 1) "tab" else "tabs"
            val serverWord = if (servers == 1) "server" else "servers"
            "termx — $tabs $tabWord on $servers $serverWord"
        }
        val subtitle = entries.joinToString(" · ") { "${it.serverLabel}: ${it.tabName}" }
            .take(SUBTITLE_MAX_CHARS)

        val openIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectIntent = PendingIntent.getService(
            context,
            REQUEST_DISCONNECT,
            Intent(context, TermxForegroundService::class.java).apply {
                action = TermxForegroundService.ACTION_DISCONNECT_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "No active sessions" })
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Disconnect all", disconnectIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_SERVICE) != null) return
        val channel = NotificationChannel(
            CHANNEL_SERVICE,
            "Active sessions",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent while termx has live SSH sessions. " +
                "Kept minimal; event alerts use separate channels."
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_SERVICE = "termx.service"
        const val REQUEST_OPEN = 0
        const val REQUEST_DISCONNECT = 1
        const val SUBTITLE_MAX_CHARS = 80
    }
}
