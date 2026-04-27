package dev.kuch.termx.feature.updater

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [UpdateChecker.parse] — the GitHub Releases API JSON
 * shape parser. We don't exercise the network call here; the parser
 * is the high-leverage piece (network errors are surfaced by the
 * caller as Result.Error and don't need their own assertions).
 *
 * Cases lock the contract against:
 *  - happy path: newer release with the expected `.apk` asset
 *  - same / older release: returns UpToDate
 *  - missing `tag_name` / `assets` / `browser_download_url` / `size`
 *  - asset list with no `.apk`
 *  - malformed JSON
 */
class UpdateCheckerTest {

    private lateinit var checker: UpdateChecker

    @Before fun setUp() {
        checker = UpdateChecker(OkHttpClient.Builder().build())
    }

    @Test fun `well-formed newer release returns Available`() {
        val body = """
            {
              "tag_name": "v1.1.17",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "termx-v1.1.17-release.apk",
                  "size": 12345678,
                  "browser_download_url": "https://github.com/.../termx-v1.1.17-release.apk"
                }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue("expected Available, got $result", result is UpdateChecker.Result.Available)
        result as UpdateChecker.Result.Available
        assertEquals("v1.1.17", result.version)
        assertEquals(12345678L, result.sizeBytes)
        assertTrue(result.downloadUrl.endsWith("termx-v1.1.17-release.apk"))
    }

    @Test fun `same version returns UpToDate`() {
        val body = """
            {
              "tag_name": "v1.1.16",
              "assets": [
                {
                  "name": "termx-v1.1.16-release.apk",
                  "size": 12000000,
                  "browser_download_url": "https://...apk"
                }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertEquals(UpdateChecker.Result.UpToDate, result)
    }

    @Test fun `older release also returns UpToDate (downgrade not offered)`() {
        val body = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {
                  "name": "termx-v1.0.0-release.apk",
                  "size": 10000000,
                  "browser_download_url": "https://...apk"
                }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertEquals(UpdateChecker.Result.UpToDate, result)
    }

    @Test fun `picks the apk asset when other artifacts are present`() {
        // Real release pages can carry .asc signatures, source archives,
        // SBOMs, etc. We must pick the .apk specifically.
        val body = """
            {
              "tag_name": "v1.1.17",
              "assets": [
                { "name": "checksums.txt", "size": 256, "browser_download_url": "https://...txt" },
                { "name": "termx-v1.1.17-release.apk", "size": 12000000, "browser_download_url": "https://...apk" },
                { "name": "termx-v1.1.17-release.apk.asc", "size": 488, "browser_download_url": "https://...asc" }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Available)
        assertTrue((result as UpdateChecker.Result.Available).downloadUrl.endsWith(".apk"))
    }

    @Test fun `missing tag_name returns Error`() {
        val body = """
            {
              "assets": [
                { "name": "x.apk", "size": 1, "browser_download_url": "https://...apk" }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
        assertTrue(
            (result as UpdateChecker.Result.Error).reason.contains("tag_name", ignoreCase = true),
        )
    }

    @Test fun `missing assets returns Error`() {
        val body = """{"tag_name": "v1.1.17"}"""
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
    }

    @Test fun `release with no apk asset returns Error`() {
        val body = """
            {
              "tag_name": "v1.1.17",
              "assets": [
                { "name": "checksums.txt", "size": 256, "browser_download_url": "https://...txt" }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
        assertTrue(
            (result as UpdateChecker.Result.Error).reason.contains(".apk"),
        )
    }

    @Test fun `asset missing browser_download_url returns Error`() {
        val body = """
            {
              "tag_name": "v1.1.17",
              "assets": [
                { "name": "termx-v1.1.17-release.apk", "size": 12000000 }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
    }

    @Test fun `asset missing size returns Error`() {
        val body = """
            {
              "tag_name": "v1.1.17",
              "assets": [
                { "name": "termx-v1.1.17-release.apk", "browser_download_url": "https://...apk" }
              ]
            }
        """.trimIndent()
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
    }

    @Test fun `malformed JSON returns Error mentioning malformed`() {
        val body = "this is not JSON{{{"
        val result = checker.parse(body, installedVersion = "1.1.16")
        assertTrue(result is UpdateChecker.Result.Error)
        assertTrue(
            (result as UpdateChecker.Result.Error).reason.contains("Malformed", ignoreCase = true),
        )
    }
}
