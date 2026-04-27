package dev.kuch.termx.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the pure [UpdateInstallerSource.classify] helper. We
 * skip the Robolectric `detect(Context)` path because shadow APIs
 * for installer-package-name vary across Robolectric versions and
 * the meaningful logic is what `classify` does with the result.
 */
class UpdateInstallerSourceTest {

    @Test fun `null installer means manual sideload`() {
        assertEquals(UpdateInstallerSource.Sideload, UpdateInstallerSource.classify(null))
    }

    @Test fun `empty-string installer means manual sideload`() {
        assertEquals(UpdateInstallerSource.Sideload, UpdateInstallerSource.classify(""))
    }

    @Test fun `org_fdroid_fdroid maps to FDroid (suppress in-app updater)`() {
        assertEquals(UpdateInstallerSource.FDroid, UpdateInstallerSource.classify("org.fdroid.fdroid"))
    }

    @Test fun `org_fdroid_basic also maps to FDroid`() {
        assertEquals(UpdateInstallerSource.FDroid, UpdateInstallerSource.classify("org.fdroid.basic"))
    }

    @Test fun `aurora droid client also maps to FDroid`() {
        assertEquals(UpdateInstallerSource.FDroid, UpdateInstallerSource.classify("com.aurora.adroid"))
    }

    @Test fun `unknown installer maps to Unknown (will still update)`() {
        // A vendor-specific store, an Aurora-Store-style proxy, etc.
        // Better to over-prompt than to silently never update.
        assertEquals(
            UpdateInstallerSource.Unknown,
            UpdateInstallerSource.classify("com.example.vendor.store"),
        )
    }

    @Test fun `play store installer maps to Unknown (we don't ship there)`() {
        // termx isn't on Play; the value can never legitimately appear.
        // We treat it as Unknown rather than Sideload to be conservative
        // — a Play install would handle its own updates if it ever existed.
        assertEquals(
            UpdateInstallerSource.Unknown,
            UpdateInstallerSource.classify("com.android.vending"),
        )
    }
}
