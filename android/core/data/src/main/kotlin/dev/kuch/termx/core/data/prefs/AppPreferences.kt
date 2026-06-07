package dev.kuch.termx.core.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
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
 * User-visible app preferences.
 *
 * - [paranoidMode]: when true, ask for biometric on every connect
 *   (referenced here, wired into the connect flow by Task #41).
 * - [autoLockMinutes]: how long the app can sit in background before
 *   [dev.kuch.termx.core.data.vault.VaultLifecycleObserver] re-locks.
 * - [fontSizeSp]: active terminal font size in density-independent
 *   pixels. Persisted by Task #17's pinch-to-zoom gesture.
 * - [pttMode]: last-used Push-to-talk mode (Task #42).
 * - [onboardingComplete]: first-run gate (Task #46) — flips to true
 *   when the 3-screen onboarding finishes (or the user skips), and
 *   stays that way forever after.
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

    /**
     * Push-to-talk source language as a BCP-47 locale code (e.g.
     * `"en-US"`, `"pt-BR"`). Drives the language Gemini is told the
     * audio is spoken in. Defaults to American English so a fresh
     * install transcribes English without further setup.
     */
    val pttSourceLanguage: Flow<String> =
        ds.data.map { it[KEY_PTT_SOURCE_LANGUAGE] ?: DEFAULT_PTT_LANGUAGE }

    /**
     * Push-to-talk target language as a BCP-47 locale code. When equal
     * to [pttSourceLanguage] the prompt is transcribe-only; when
     * different, Gemini translates from source to target. Defaults to
     * the same as the source so the new feature is invisible until the
     * user opts in.
     */
    val pttTargetLanguage: Flow<String> =
        ds.data.map { it[KEY_PTT_TARGET_LANGUAGE] ?: DEFAULT_PTT_LANGUAGE }

    /**
     * Optional free-text "domain context" appended to every
     * push-to-talk prompt. Lets the user prime Gemini with technical
     * jargon ("kubectl, systemctl, k9s") so transcripts spell their
     * tooling correctly. Empty by default; consumers should treat
     * blanks as "no context, omit the appendix".
     */
    val pttContext: Flow<String> =
        ds.data.map { it[KEY_PTT_CONTEXT] ?: DEFAULT_PTT_CONTEXT }

    /**
     * First-run gate consumed by [dev.kuch.termx.TermxNavHost]. Flips to
     * true when the user finishes (or skips) the 3-screen onboarding
     * shipped in Task #46; the flag is never cleared automatically so a
     * factory-reset user gets the onboarding once and never again.
     */
    val onboardingComplete: Flow<Boolean> =
        ds.data.map { it[KEY_ONBOARDING_COMPLETE] ?: DEFAULT_ONBOARDING_COMPLETE }

    /**
     * Last GitHub-Releases-API check epoch ms. The in-app updater
     * skips a network call when this is within `UPDATER_CHECK_TTL_MS`
     * of the current time, so cold start adds at most one HTTP round
     * per 24h.
     */
    val updaterLastCheckEpochMs: Flow<Long> =
        ds.data.map { it[KEY_UPDATER_LAST_CHECK_MS] ?: DEFAULT_UPDATER_LAST_CHECK_MS }

    /**
     * The latest version tag the user explicitly skipped (e.g.
     * `"v1.1.17"`). The banner stays hidden as long as the upstream
     * `releases/latest` matches this; a newer tag re-surfaces it.
     */
    val updaterSkippedVersion: Flow<String> =
        ds.data.map { it[KEY_UPDATER_SKIPPED_VERSION] ?: DEFAULT_UPDATER_SKIPPED_VERSION }

    // --- companion (termxd) on-connect update offer (Task #32) -------------
    //
    // Two anti-nag memories backing the best-effort on-connect companion
    // check, keyed by server id so each VPS is tracked independently:
    //
    //  - [companionUpdateSkipped]: set of `"$serverId@$tag"` strings the user
    //    explicitly dismissed via the banner's "Later". A newer `termxd-v*`
    //    tag re-surfaces the offer because the encoded token no longer matches.
    //    Mirrors the APK updater's single [updaterSkippedVersion], scaled to
    //    one entry per (server, version) pair.
    //  - [companionUpdateLastCheck]: per-server last-probe epoch ms (encoded
    //    `"$serverId=$epochMs"`). The repo skips re-probing a server inside a
    //    24h window, so frequent reconnects don't re-run `termx --version` over
    //    SSH AND we stay clear of GitHub's 60 req/h unauthenticated limit
    //    (TermxReleaseFetcher is uncached). Mirrors [updaterLastCheckEpochMs],
    //    keyed per server.

    /** Raw `"$serverId@$tag"` skip tokens; consumers query via [isCompanionUpdateSkipped]. */
    val companionUpdateSkipped: Flow<Set<String>> =
        ds.data.map { it[KEY_COMPANION_UPDATE_SKIPPED] ?: emptySet() }

    /** True iff the user previously skipped exactly [tag] for [serverId]. */
    fun isCompanionUpdateSkipped(serverId: UUID, tag: String): Flow<Boolean> =
        companionUpdateSkipped.map { it.contains(skipToken(serverId, tag)) }

    /** Last on-connect companion check epoch ms for [serverId], or 0 if never. */
    fun companionUpdateLastCheck(serverId: UUID): Flow<Long> =
        ds.data.map { prefs ->
            (prefs[KEY_COMPANION_UPDATE_LAST_CHECK] ?: emptySet())
                .lastCheckFor(serverId)
        }

    suspend fun setParanoidMode(value: Boolean) {
        ds.edit { it[KEY_PARANOID_MODE] = value }
    }

    suspend fun setAutoLockMinutes(value: Int) {
        ds.edit { it[KEY_AUTO_LOCK_MINUTES] = value.coerceAtLeast(0) }
    }

    suspend fun setFontSizeSp(value: Int) {
        ds.edit { it[KEY_FONT_SIZE_SP] = value.coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP) }
    }

    suspend fun setPttSourceLanguage(value: String) {
        ds.edit { it[KEY_PTT_SOURCE_LANGUAGE] = value }
    }

    suspend fun setPttTargetLanguage(value: String) {
        ds.edit { it[KEY_PTT_TARGET_LANGUAGE] = value }
    }

    suspend fun setPttContext(value: String) {
        ds.edit { it[KEY_PTT_CONTEXT] = value }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        ds.edit { it[KEY_ONBOARDING_COMPLETE] = value }
    }

    suspend fun setUpdaterLastCheckEpochMs(value: Long) {
        ds.edit { it[KEY_UPDATER_LAST_CHECK_MS] = value }
    }

    suspend fun setUpdaterSkippedVersion(value: String) {
        ds.edit { it[KEY_UPDATER_SKIPPED_VERSION] = value }
    }

    /** Remember that the user dismissed the companion update offer for [tag] on [serverId]. */
    suspend fun addCompanionUpdateSkipped(serverId: UUID, tag: String) {
        ds.edit { prefs ->
            val current = prefs[KEY_COMPANION_UPDATE_SKIPPED] ?: emptySet()
            prefs[KEY_COMPANION_UPDATE_SKIPPED] = current + skipToken(serverId, tag)
        }
    }

    /** Stamp [serverId]'s last companion check, replacing any prior entry for it. */
    suspend fun setCompanionUpdateLastCheck(serverId: UUID, epochMs: Long) {
        ds.edit { prefs ->
            val prefix = "$serverId="
            val kept = (prefs[KEY_COMPANION_UPDATE_LAST_CHECK] ?: emptySet())
                .filterNot { it.startsWith(prefix) }
                .toSet()
            prefs[KEY_COMPANION_UPDATE_LAST_CHECK] = kept + "$prefix$epochMs"
        }
    }

    /**
     * `internal` (was `private`) so AppPreferencesTest can verify the
     * default constants directly without relying on the DataStore
     * singleton being empty — the singleton persists across tests in
     * the same JVM, so testing defaults via the Flow.first() path is
     * unreliable.
     */
    internal companion object {
        val KEY_PARANOID_MODE = booleanPreferencesKey("paranoid_mode_enabled")
        val KEY_AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")
        val KEY_FONT_SIZE_SP = intPreferencesKey("terminal_font_size_sp")
        val KEY_PTT_SOURCE_LANGUAGE = stringPreferencesKey("ptt_source_language")
        val KEY_PTT_TARGET_LANGUAGE = stringPreferencesKey("ptt_target_language")
        val KEY_PTT_CONTEXT = stringPreferencesKey("ptt_context")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_UPDATER_LAST_CHECK_MS = longPreferencesKey("updater_last_check_epoch_ms")
        val KEY_UPDATER_SKIPPED_VERSION = stringPreferencesKey("updater_skipped_version")
        val KEY_COMPANION_UPDATE_SKIPPED = stringSetPreferencesKey("companion_update_skipped")
        val KEY_COMPANION_UPDATE_LAST_CHECK = stringSetPreferencesKey("companion_update_last_check")
        const val DEFAULT_PARANOID_MODE = false

        /**
         * Default auto-lock idle threshold, in minutes. Bumped from 5
         * to 1440 (24 h) in v1.1.8: the previous value tripped on
         * casual app-switching ("step away to copy a Gemini key from
         * a browser tab"), then returning users found the vault
         * locked and their next save / password persist silently
         * dropped. The phone's own lock screen remains the real
         * boundary against device theft; this timer is a soft
         * deterrent against an unattended-and-unlocked phone, and
         * 24 h is enough idleness to call that scenario.
         */
        const val DEFAULT_AUTO_LOCK_MINUTES = 1440
        const val DEFAULT_FONT_SIZE_SP = 14
        const val MIN_FONT_SIZE_SP = 8
        const val MAX_FONT_SIZE_SP = 32
        /**
         * Default source/target locale code for the PTT pickers. Kept
         * here (not delegated to PttLanguage in :core:domain) because
         * :core:data sits below :core:domain in the module graph in
         * spirit even though Gradle currently allows the import — this
         * one literal duplication avoids dragging the catalogue into
         * the prefs layer.
         */
        const val DEFAULT_PTT_LANGUAGE = "en-US"
        const val DEFAULT_PTT_CONTEXT = ""
        const val DEFAULT_ONBOARDING_COMPLETE = false
        const val DEFAULT_UPDATER_LAST_CHECK_MS = 0L
        const val DEFAULT_UPDATER_SKIPPED_VERSION = ""
    }
}

/**
 * Encode a per-(server, version) skip memory as a single stringSet entry.
 * `"$serverId@$tag"` is intentionally stable across reconnects so a newer
 * `termxd-v*` tag yields a different token (and re-surfaces the offer).
 */
private fun skipToken(serverId: UUID, tag: String): String = "$serverId@$tag"

/**
 * Pull [serverId]'s last-check epoch out of the `"$serverId=$epochMs"` set
 * encoding, defaulting to 0 (never checked) when no entry exists or the
 * stored value isn't a parseable long.
 */
private fun Set<String>.lastCheckFor(serverId: UUID): Long {
    val prefix = "$serverId="
    return firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.toLongOrNull()
        ?: 0L
}
