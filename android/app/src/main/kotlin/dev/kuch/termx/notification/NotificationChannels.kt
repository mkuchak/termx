package dev.kuch.termx.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the four event-driven notification channels that Task #44 needs.
 *
 * Channel model (matches docs/ROADMAP.md §7):
 *  - `termx.permission` — Claude asking to run a tool. HIGH importance
 *    with a default notification sound and vibration so a phone-in-pocket
 *    user cannot miss it.
 *  - `termx.task` — Long-running commands finished, Claude finished a
 *    turn, diffs created. DEFAULT importance, silent.
 *  - `termx.error` — Commands that exited non-zero. HIGH importance with
 *    a distinct sound so it's obvious even with the phone on the desk.
 *  - `termx.disconnect` — SSH session dropped unexpectedly. HIGH
 *    importance with a bespoke vibration pattern.
 *
 * Both HIGH channels intentionally choose sounds different from each
 * other so a user can distinguish "Claude wants permission" from
 * "Claude's command failed" without looking at the phone.
 *
 * Grouped under a single "termx" channel group so the Settings app
 * renders them together — avoids the UX where 5 termx channels (service
 * + 4 events) scatter alphabetically across the user's channel list.
 *
 * Called from [dev.kuch.termx.TermxApplication.onCreate] so channels
 * exist before any notification posts. Idempotent — system coalesces
 * repeat `createNotificationChannel` calls for the same id.
 */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureAll() {
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return

        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_ID, "termx"),
        )

        nm.createNotificationChannel(buildPermissionChannel())
        nm.createNotificationChannel(buildTaskChannel())
        nm.createNotificationChannel(buildErrorChannel())
        nm.createNotificationChannel(buildDisconnectChannel())
    }

    private fun buildPermissionChannel(): NotificationChannel =
        NotificationChannel(
            PERMISSION,
            "Permission requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Claude is asking for approval to run a tool."
            group = GROUP_ID
            enableVibration(true)
            vibrationPattern = VIBRATION_PERMISSION
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    private fun buildTaskChannel(): NotificationChannel =
        NotificationChannel(
            TASK,
            "Task complete",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Long-running commands finished."
            group = GROUP_ID
            setSound(null, null)
            enableVibration(false)
        }

    private fun buildErrorChannel(): NotificationChannel =
        NotificationChannel(
            ERROR,
            "Command errors",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Commands that exited non-zero."
            group = GROUP_ID
            enableVibration(true)
            vibrationPattern = VIBRATION_ERROR
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    private fun buildDisconnectChannel(): NotificationChannel =
        NotificationChannel(
            DISCONNECT,
            "Session drops",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "SSH session dropped unexpectedly."
            group = GROUP_ID
            enableVibration(true)
            vibrationPattern = VIBRATION_DISCONNECT
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

    companion object {
        const val GROUP_ID = "termx"
        const val PERMISSION = "termx.permission"
        const val TASK = "termx.task"
        const val ERROR = "termx.error"
        const val DISCONNECT = "termx.disconnect"

        // Distinct vibration patterns per high-importance channel: users
        // learn the cadence over time (like ringtones) and can triage
        // with the phone face-down.
        private val VIBRATION_PERMISSION = longArrayOf(0L, 120L, 60L, 120L)
        private val VIBRATION_ERROR = longArrayOf(0L, 200L, 80L, 200L, 80L, 200L)
        private val VIBRATION_DISCONNECT = longArrayOf(0L, 400L, 100L, 80L)
    }
}
