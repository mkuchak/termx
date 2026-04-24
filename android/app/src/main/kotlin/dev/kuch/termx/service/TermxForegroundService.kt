package dev.kuch.termx.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.kuch.termx.core.data.session.SessionRegistry
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service holding termx in memory while at least one SSH tab
 * is active.
 *
 * MVP lifecycle (Task #43):
 *  - Started by [TerminalViewModel] on first successful connect via
 *    [start] — idempotent, so repeat calls on subsequent tabs are fine.
 *  - Observes [SessionRegistry.entries]; whenever the map changes it
 *    either rebuilds the notification or, if the map went empty,
 *    self-terminates with [stopSelf].
 *  - Responds to the "Disconnect all" notification action by forwarding
 *    a request into [SessionRegistry.requestDisconnectAll]; each
 *    ViewModel collects that signal and tears down its own session.
 *
 * What this service deliberately does NOT do yet:
 *  - Own the SSH transport (stays on the ViewModel for now).
 *  - Survive process death / resurrect sessions (Server-ownership
 *    refactor slated for a later task).
 *  - Tail `events.ndjson` or fire event-driven notifications (Task #44).
 */
@AndroidEntryPoint
class TermxForegroundService : Service() {

    @Inject lateinit var sessionRegistry: SessionRegistry
    @Inject lateinit var notificationBuilder: TermxNotificationBuilder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcherJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Android 12+ forces us to call startForeground within 5 s of
        // startForegroundService, so do it immediately with whatever
        // snapshot the registry currently holds.
        val initial = sessionRegistry.entries.value.values.toList()
        val notification = notificationBuilder.build(initial)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_ID, notification)
        }

        watcherJob = serviceScope.launch {
            sessionRegistry.entries.collect { map ->
                if (map.isEmpty()) {
                    stopSelf()
                    return@collect
                }
                val updated = notificationBuilder.build(map.values.toList())
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(FOREGROUND_ID, updated)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            sessionRegistry.requestDisconnectAll()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcherJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val FOREGROUND_ID = 42
        const val ACTION_DISCONNECT_ALL = "dev.kuch.termx.action.DISCONNECT_ALL"

        /**
         * Idempotent entry point used by the ViewModel on first connect.
         * Safe to call repeatedly — Android coalesces duplicate
         * startForegroundService calls to the same component.
         */
        fun start(context: Context) {
            val intent = Intent(context, TermxForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
