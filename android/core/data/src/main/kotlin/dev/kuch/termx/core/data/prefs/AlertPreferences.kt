package dev.kuch.termx.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore delegate for the alert-preferences file. Kept separate from
 * `app_prefs` so a future "reset alert rules" affordance can nuke this
 * file without touching font size / theme / paranoid-mode state.
 */
private val Context.alertPrefsDataStore by preferencesDataStore(name = "alert_prefs")

/**
 * Per-server notification mute state + cross-cutting alert thresholds.
 *
 * Task #44 scope: the mute toggles and the long-command threshold.
 * Settings UI that flips these is deliberately out of scope for Task #44
 * — only the method signatures are surfaced, so a follow-up settings task
 * can wire a row in `SettingsScreen` without re-designing the persistence.
 *
 * Storage shape:
 *  - Mute sets are stored as string sets of UUIDs so DataStore's
 *    [stringSetPreferencesKey] fits naturally; UUID parsing happens on
 *    the read path with a defensive filter so a corrupt entry doesn't
 *    nuke the whole collection.
 *  - [longCommandThresholdMs] is persisted as an Int (milliseconds fit
 *    comfortably) with a 60 s default that mirrors the Task #44 spec.
 *  - [batteryOptPromptDismissed] is the "Don't ask again" flag for the
 *    Task #45 home-screen prompt.
 */
@Singleton
class AlertPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val ds = context.alertPrefsDataStore

    /** Set of server ids whose `task`-channel notifications are muted. */
    val muteTasks: Flow<Set<UUID>> =
        ds.data.map { prefs -> (prefs[KEY_MUTE_TASKS] ?: emptySet()).toUuidSet() }

    /** Set of server ids whose `error`-channel notifications are muted. */
    val muteErrors: Flow<Set<UUID>> =
        ds.data.map { prefs -> (prefs[KEY_MUTE_ERRORS] ?: emptySet()).toUuidSet() }

    /**
     * Duration in milliseconds a `shell_command_long` event needs to
     * exceed before the router raises a task notification. Default
     * 60 s — below that, phone-pocket pings become noise.
     */
    val longCommandThresholdMs: Flow<Long> =
        ds.data.map { (it[KEY_LONG_CMD_THRESHOLD_MS] ?: DEFAULT_LONG_CMD_THRESHOLD_MS).toLong() }

    /** "Don't ask again" toggle for the battery-optimization prompt. */
    val batteryOptPromptDismissed: Flow<Boolean> =
        ds.data.map { it[KEY_BATTERY_OPT_DISMISSED] ?: false }

    suspend fun setMuteTasks(serverId: UUID, muted: Boolean) {
        ds.edit { prefs ->
            val current = prefs[KEY_MUTE_TASKS] ?: emptySet()
            prefs[KEY_MUTE_TASKS] = if (muted) current + serverId.toString() else current - serverId.toString()
        }
    }

    suspend fun setMuteErrors(serverId: UUID, muted: Boolean) {
        ds.edit { prefs ->
            val current = prefs[KEY_MUTE_ERRORS] ?: emptySet()
            prefs[KEY_MUTE_ERRORS] = if (muted) current + serverId.toString() else current - serverId.toString()
        }
    }

    suspend fun setLongCommandThresholdMs(ms: Long) {
        ds.edit { it[KEY_LONG_CMD_THRESHOLD_MS] = ms.coerceAtLeast(0L).toInt() }
    }

    suspend fun setBatteryOptPromptDismissed(dismissed: Boolean) {
        ds.edit { it[KEY_BATTERY_OPT_DISMISSED] = dismissed }
    }

    private fun Set<String>.toUuidSet(): Set<UUID> =
        mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()

    private companion object {
        val KEY_MUTE_TASKS = stringSetPreferencesKey("mute_tasks_server_ids")
        val KEY_MUTE_ERRORS = stringSetPreferencesKey("mute_errors_server_ids")
        val KEY_LONG_CMD_THRESHOLD_MS = intPreferencesKey("long_cmd_threshold_ms")
        val KEY_BATTERY_OPT_DISMISSED = booleanPreferencesKey("battery_opt_prompt_dismissed")
        const val DEFAULT_LONG_CMD_THRESHOLD_MS = 60_000
    }
}
