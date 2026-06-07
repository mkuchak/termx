package dev.kuch.termx.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.MainActivity
import dev.kuch.termx.R
import dev.kuch.termx.core.data.prefs.AlertPreferences
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Builds and posts an "agent finished" heads-up notification on the
 * [NotificationChannels.AGENT] channel and fires a strong, max-amplitude
 * vibration so a phone-in-pocket user feels it.
 *
 * Extracted as a shared `@Singleton` so BOTH delivery tiers post an
 * identical alert through the same code:
 *  - Tier 1 (in-connection SSH event stream): [EventNotificationRouter]
 *    calls [post] with full server context. The router owns the
 *    enable/mute/supersede gating; by the time it calls here the alert is
 *    already cleared to fire.
 *  - Tier 2 (UnifiedPush, separate `TermxPushService` task): injects this
 *    poster and calls [postRaw] with only the decoded push title/body —
 *    there is no `serverId` in that context. Tier 2 is itself opt-in, so
 *    [postRaw] always posts (no gating here).
 *
 * The strong vibration is gated only by
 * [AlertPreferences.agentStrongVibration] and applies to both entry
 * points. The notification *sound* comes from the channel; the vibration
 * is fired from code (the channel has `enableVibration(false)`) so we can
 * guarantee a controlled max-amplitude waveform rather than a device's
 * default channel buzz.
 */
@Singleton
class AgentAlertPoster @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alertPreferences: AlertPreferences,
) {

    /**
     * Server-keyed entry used by the Tier-1 router. The stacking id is
     * keyed on `serverId + "agent"` so repeated finishes from one server
     * coalesce into one updated notification instead of stacking.
     */
    suspend fun post(serverId: UUID, serverLabel: String, agent: String, workspace: String?) {
        val notificationId = stackingId(serverId, "agent")
        postNotification(
            notificationId = notificationId,
            title = "herdr: $agent finished",
            body = workspace ?: serverLabel,
            contentIntent = openAppIntent(notificationId),
        )
        vibrateStrong()
    }

    /**
     * Raw entry used by the Tier-2 UnifiedPush service, which has only the
     * decoded push text and no server context. Always posts (Tier 2 is
     * user-enabled) and fires the same strong vibration.
     */
    suspend fun postRaw(title: String, body: String) {
        // No serverId to key on; hash the title so distinct pushes don't
        // overwrite each other, while a repeat of the same push coalesces.
        val notificationId = ("agent_raw:$title").hashCode()
        postNotification(
            notificationId = notificationId,
            title = title,
            body = body,
            contentIntent = openAppIntent(notificationId),
        )
        vibrateStrong()
    }

    private fun postNotification(
        notificationId: Int,
        title: String,
        body: String,
        contentIntent: PendingIntent,
    ) {
        val notification = NotificationCompat.Builder(context, NotificationChannels.AGENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        nm()?.notify(notificationId, notification)
    }

    /**
     * Fire a ~3s strong (max-amplitude) vibration when the user has the
     * strong-vibration preference on.
     *
     * The effect is three 1s buzzes at full amplitude. On API 33+ we tag
     * the vibration with [VibrationAttributes.USAGE_NOTIFICATION] so the
     * platform applies DND policy and the channel's DND-bypass to the
     * vibration too. Devices without amplitude control will throw on the
     * amplitude-bearing waveform, so we degrade to a timings-only waveform
     * (the platform substitutes [VibrationEffect.DEFAULT_AMPLITUDE]).
     */
    private suspend fun vibrateStrong() {
        if (!alertPreferences.agentStrongVibration.first()) return
        val vibrator = resolveVibrator() ?: return

        try {
            vibrate(vibrator, VibrationEffect.createWaveform(WAVEFORM_TIMINGS, WAVEFORM_AMPLITUDES, -1))
        } catch (_: Throwable) {
            // Some devices lack amplitude control and reject the
            // amplitude-bearing waveform: fall back to timings only.
            runCatching {
                vibrate(vibrator, VibrationEffect.createWaveform(WAVEFORM_TIMINGS, -1))
            }
        }
    }

    private fun vibrate(vibrator: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
            )
        } else {
            vibrator.vibrate(effect)
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun openAppIntent(requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stackingId(serverId: UUID, bucket: String): Int =
        ("$serverId:$bucket").hashCode()

    private fun nm(): NotificationManager? =
        ContextCompat.getSystemService(context, NotificationManager::class.java)

    private companion object {
        // Three 1s buzzes separated by 300ms gaps (~3s total): an initial
        // 0ms off-segment, then on/off pairs.
        private val WAVEFORM_TIMINGS = longArrayOf(0, 1000, 300, 1000, 300, 1000)
        private val WAVEFORM_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
    }
}
