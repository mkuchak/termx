package dev.kuch.termx.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level DataStore delegate. It is required by [preferencesDataStore] to
 * live as a property on a receiver — declared on [Context] here so the
 * singleton wrapper below can reuse it.
 */
private val Context.appPrefsDataStore by preferencesDataStore(name = "app_prefs")

/**
 * User-visible app preferences. Currently surfaces the two knobs
 * Task #20 introduces:
 *
 * - [paranoidMode]: when true, ask for biometric on every connect
 *   (referenced here, wired into the connect flow by Task #41).
 * - [autoLockMinutes]: how long the app can sit in background before
 *   [dev.kuch.termx.core.data.vault.VaultLifecycleObserver] re-locks.
 * - [fontSizeSp]: active terminal font size in density-independent
 *   pixels. Persisted by Task #17's pinch-to-zoom gesture and read by
 *   [dev.kuch.termx.feature.terminal.TerminalScreen] on first bind.
 * - [activeThemeId]: currently-selected [
 *   dev.kuch.termx.core.domain.theme.TerminalTheme] id. Shared by the
 *   Task #18 theme picker and the terminal's [
 *   dev.kuch.termx.feature.terminal.theme.ThemeBinder].
 *
 * All I/O is coroutine-friendly — [Flow] for reads, `suspend` for writes.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val ds = context.appPrefsDataStore

    val paranoidMode: Flow<Boolean> =
        ds.data.map { it[KEY_PARANOID_MODE] ?: DEFAULT_PARANOID_MODE }

    val autoLockMinutes: Flow<Int> =
        ds.data.map { it[KEY_AUTO_LOCK_MINUTES] ?: DEFAULT_AUTO_LOCK_MINUTES }

    val fontSizeSp: Flow<Int> =
        ds.data.map { it[KEY_FONT_SIZE_SP] ?: DEFAULT_FONT_SIZE_SP }

    val activeThemeId: Flow<String> =
        ds.data.map { it[KEY_ACTIVE_THEME_ID] ?: DEFAULT_ACTIVE_THEME_ID }

    suspend fun setParanoidMode(value: Boolean) {
        ds.edit { it[KEY_PARANOID_MODE] = value }
    }

    suspend fun setAutoLockMinutes(value: Int) {
        ds.edit { it[KEY_AUTO_LOCK_MINUTES] = value.coerceAtLeast(0) }
    }

    suspend fun setFontSizeSp(value: Int) {
        ds.edit { it[KEY_FONT_SIZE_SP] = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP) }
    }

    suspend fun setActiveThemeId(id: String) {
        ds.edit { it[KEY_ACTIVE_THEME_ID] = id }
    }

    private companion object {
        val KEY_PARANOID_MODE = booleanPreferencesKey("paranoid_mode_enabled")
        val KEY_AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val KEY_FONT_SIZE_SP = intPreferencesKey("terminal_font_size_sp")
        val KEY_ACTIVE_THEME_ID = stringPreferencesKey("terminal_active_theme_id")
        const val DEFAULT_PARANOID_MODE = false
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
        const val DEFAULT_FONT_SIZE_SP = 14
        const val DEFAULT_ACTIVE_THEME_ID = "dracula"
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 32
    }
}
