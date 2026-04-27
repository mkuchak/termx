package dev.kuch.termx.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for [VersionTag]. The updater fires (or doesn't)
 * based entirely on this comparator — a regression here ships a
 * silent never-update bug or a same-version download loop.
 */
class VersionTagTest {

    @Test fun `normalise strips a leading v prefix`() {
        assertEquals("1.1.16", VersionTag.normalise("v1.1.16"))
        assertEquals("1.1.16", VersionTag.normalise("V1.1.16"))
        assertEquals("1.1.16", VersionTag.normalise("1.1.16"))
    }

    @Test fun `normalise drops SemVer pre-release and build metadata`() {
        assertEquals("1.1.16", VersionTag.normalise("v1.1.16-rc1"))
        assertEquals("1.1.16", VersionTag.normalise("1.1.16+build42"))
        assertEquals("1.1.16", VersionTag.normalise("v1.1.16-rc1+build42"))
    }

    @Test fun `normalise returns null for unparseable input`() {
        assertNull(VersionTag.normalise(""))
        assertNull(VersionTag.normalise("   "))
        assertNull(VersionTag.normalise("v"))
        assertNull(VersionTag.normalise("not-a-version"))
        assertNull(VersionTag.normalise("1.x.3"))
        assertNull(VersionTag.normalise("v1.-2.3"))
    }

    @Test fun `compare orders patch revisions correctly`() {
        assertTrue(VersionTag.compare("v1.1.17", "v1.1.16") > 0)
        assertTrue(VersionTag.compare("v1.1.16", "v1.1.17") < 0)
        assertEquals(0, VersionTag.compare("v1.1.16", "v1.1.16"))
    }

    @Test fun `compare orders minor revisions correctly`() {
        assertTrue(VersionTag.compare("v1.2.0", "v1.1.99") > 0)
        assertTrue(VersionTag.compare("v1.1.99", "v1.2.0") < 0)
    }

    @Test fun `compare orders major revisions correctly`() {
        assertTrue(VersionTag.compare("v2.0.0", "v1.99.99") > 0)
        assertTrue(VersionTag.compare("v1.99.99", "v2.0.0") < 0)
    }

    @Test fun `compare treats missing tail components as zero`() {
        // "1.2" should equal "1.2.0" should equal "1.2.0.0".
        assertEquals(0, VersionTag.compare("1.2", "1.2.0"))
        assertEquals(0, VersionTag.compare("1.2.0", "1.2.0.0"))
        // "1.2.1" should still beat "1.2".
        assertTrue(VersionTag.compare("1.2.1", "1.2") > 0)
    }

    @Test fun `compare ignores v vs no-v on either side`() {
        // The release pipeline tags as "v1.1.17"; BuildConfig.VERSION_NAME
        // is "1.1.17". They MUST compare equal or the updater would
        // perpetually offer the running version to the user.
        assertEquals(0, VersionTag.compare("v1.1.17", "1.1.17"))
        assertEquals(0, VersionTag.compare("1.1.17", "v1.1.17"))
    }

    @Test fun `isNewer is the strict-greater-than convenience`() {
        assertTrue(VersionTag.isNewer(candidate = "v1.1.17", installed = "1.1.16"))
        assertFalse(VersionTag.isNewer(candidate = "v1.1.16", installed = "1.1.16"))
        assertFalse(VersionTag.isNewer(candidate = "v1.1.15", installed = "1.1.16"))
    }

    @Test fun `unparseable inputs sort BEFORE everything (treated as zero version)`() {
        // A malformed BuildConfig.VERSION_NAME shouldn't silently
        // suppress updates — better to over-prompt than under-prompt.
        assertTrue(VersionTag.compare("v1.0.0", "garbage") > 0)
        assertTrue(VersionTag.compare("v0.0.1", "garbage") > 0)
        assertEquals(0, VersionTag.compare("garbage", "junk"))
    }
}
