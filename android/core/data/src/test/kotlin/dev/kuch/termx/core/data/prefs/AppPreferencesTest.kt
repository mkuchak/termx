package dev.kuch.termx.core.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AppPreferences] split in two halves:
 *
 *  1. Default-constant guards — compile-time checks that the
 *     [AppPreferences.Companion] still names the documented default
 *     values. These would have caught a drift like "v1.1.8 bumped
 *     auto-lock to 1440 then someone reverted it to 5".
 *  2. DataStore round-trips — for every setter, verify a subsequent
 *     read returns the written value (and that clamping applies to
 *     `setFontSizeSp` / `setAutoLockMinutes`). Each round-trip resets
 *     the relevant key first because DataStore's singleton lives the
 *     entire JVM and bleeds state across tests.
 *
 * What this file *cannot* easily test: "DataStore has no value at
 * all → AppPreferences returns DEFAULT_X". The DataStore singleton
 * is per (Context, name) and Robolectric reuses the Context across
 * tests — there's no clean fresh-DataStore-per-test seam. The
 * default-constant tests above cover the same intent (the production
 * Flows literally do `it[KEY] ?: DEFAULT_X`, so the constants ARE
 * the defaults).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesTest {

    private lateinit var prefs: AppPreferences

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    // ---- default constants --------------------------------------------

    @Test fun `DEFAULT_PARANOID_MODE is false`() {
        assertFalse(AppPreferences.DEFAULT_PARANOID_MODE)
    }

    @Test fun `DEFAULT_AUTO_LOCK_MINUTES is 1440 (24 hours)`() {
        // v1.1.8 bumped this from 5 to 1440 — a regression here would
        // bring back the casual app-switch false-lock symptom.
        assertEquals(1440, AppPreferences.DEFAULT_AUTO_LOCK_MINUTES)
    }

    @Test fun `DEFAULT_FONT_SIZE_SP is 14`() {
        assertEquals(14, AppPreferences.DEFAULT_FONT_SIZE_SP)
    }

    @Test fun `DEFAULT_ACTIVE_THEME_ID is dracula`() {
        assertEquals("dracula", AppPreferences.DEFAULT_ACTIVE_THEME_ID)
    }

    @Test fun `MIN and MAX font size span the slider range`() {
        assertEquals(8, AppPreferences.MIN_FONT_SIZE_SP)
        assertEquals(32, AppPreferences.MAX_FONT_SIZE_SP)
    }

    @Test fun `DEFAULT_PTT_LANGUAGE is en-US (matches PttLanguage)`() {
        assertEquals("en-US", AppPreferences.DEFAULT_PTT_LANGUAGE)
    }

    @Test fun `DEFAULT_PTT_CONTEXT is empty string`() {
        assertEquals("", AppPreferences.DEFAULT_PTT_CONTEXT)
    }

    @Test fun `DEFAULT_ONBOARDING_COMPLETE is false`() {
        assertFalse(AppPreferences.DEFAULT_ONBOARDING_COMPLETE)
    }

    // ---- round-trip writes --------------------------------------------

    @Test fun `setParanoidMode round-trips both true and false`() = runTest {
        prefs.setParanoidMode(true)
        assertTrue(prefs.paranoidMode.first())
        prefs.setParanoidMode(false)
        assertFalse(prefs.paranoidMode.first())
    }

    @Test fun `setAutoLockMinutes coerces negatives to 0 then accepts positives`() = runTest {
        prefs.setAutoLockMinutes(-5)
        assertEquals(0, prefs.autoLockMinutes.first())
        prefs.setAutoLockMinutes(60)
        assertEquals(60, prefs.autoLockMinutes.first())
    }

    @Test fun `setFontSizeSp clamps below 8 and above 32`() = runTest {
        prefs.setFontSizeSp(2)
        assertEquals(8, prefs.fontSizeSp.first())
        prefs.setFontSizeSp(100)
        assertEquals(32, prefs.fontSizeSp.first())
        prefs.setFontSizeSp(20)
        assertEquals(20, prefs.fontSizeSp.first())
    }

    @Test fun `setActiveThemeId round-trips arbitrary string`() = runTest {
        prefs.setActiveThemeId("solarized-dark")
        assertEquals("solarized-dark", prefs.activeThemeId.first())
    }

    @Test fun `setPttSourceLanguage round-trips`() = runTest {
        prefs.setPttSourceLanguage("pt-BR")
        assertEquals("pt-BR", prefs.pttSourceLanguage.first())
    }

    @Test fun `setPttTargetLanguage round-trips`() = runTest {
        prefs.setPttTargetLanguage("fr-FR")
        assertEquals("fr-FR", prefs.pttTargetLanguage.first())
    }

    @Test fun `setPttContext round-trips multi-line text`() = runTest {
        val ctx = "kubectl, systemctl,\nk9s, my server names"
        prefs.setPttContext(ctx)
        assertEquals(ctx, prefs.pttContext.first())
    }

    @Test fun `setOnboardingComplete round-trips`() = runTest {
        prefs.setOnboardingComplete(true)
        assertTrue(prefs.onboardingComplete.first())
    }

    @Test fun `independent keys do not interfere`() = runTest {
        prefs.setPttSourceLanguage("pt-BR")
        prefs.setPttTargetLanguage("hi-IN")
        prefs.setPttContext("test")
        prefs.setFontSizeSp(20)
        prefs.setActiveThemeId("solarized-dark")

        assertEquals("pt-BR", prefs.pttSourceLanguage.first())
        assertEquals("hi-IN", prefs.pttTargetLanguage.first())
        assertEquals("test", prefs.pttContext.first())
        assertEquals(20, prefs.fontSizeSp.first())
        assertEquals("solarized-dark", prefs.activeThemeId.first())
    }
}
