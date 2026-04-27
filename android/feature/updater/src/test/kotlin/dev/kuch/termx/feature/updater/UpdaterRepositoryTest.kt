package dev.kuch.termx.feature.updater

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * State-machine tests for [UpdaterRepository]. A regression here is
 * the difference between "users get nudged to update" and "the app
 * silently never offers an update", or worse, "the banner ping-pongs
 * between Available and Skipped on every launch".
 *
 * The repository launches into its own [Dispatchers.IO]-backed scope,
 * so we follow the [PttViewModelTest] pattern: real coroutines + a
 * polling [waitForState] helper rather than refactoring production
 * code to inject a dispatcher.
 *
 * AppPreferences is constructed against the Robolectric Context so
 * `updaterLastCheckEpochMs` and `updaterSkippedVersion` round-trip
 * through real DataStore — that's where most of the cache-decay /
 * skip-bookkeeping bugs would hide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class UpdaterRepositoryTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var prefs: AppPreferences
    private lateinit var checker: UpdateChecker
    private lateinit var downloader: ApkDownloader
    private lateinit var installer: ApkInstaller

    @Before fun setUp() {
        prefs = AppPreferences(appContext)
        // Always start from a clean slate: clear any state that could
        // bleed in from a sibling test running earlier in the same JVM.
        runBlocking {
            prefs.setUpdaterLastCheckEpochMs(0L)
            prefs.setUpdaterSkippedVersion("")
        }
        checker = mockk()
        downloader = mockk(relaxed = true)
        installer = mockk(relaxed = true)
    }

    @After fun tearDown() {
        // Reset the persisted updater fields so the next test in the
        // same JVM doesn't observe leftover state.
        runBlocking {
            prefs.setUpdaterLastCheckEpochMs(0L)
            prefs.setUpdaterSkippedVersion("")
        }
    }

    private fun newRepo(): UpdaterRepository = UpdaterRepository(
        context = appContext,
        checker = checker,
        downloader = downloader,
        installer = installer,
        appPreferences = prefs,
    )

    /**
     * Spin until [predicate] holds against the current state or the
     * 5 s real-time deadline elapses. The repository hops to
     * `Dispatchers.IO` for every transition; `runTest`'s virtual clock
     * never sees those launches, so polling is the simplest path that
     * doesn't require injecting a dispatcher into production code.
     */
    private fun waitForState(repo: UpdaterRepository, predicate: (UpdaterState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!predicate(repo.state.value) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    // ---- happy paths --------------------------------------------------

    @Test fun `initial state is Idle`() {
        val repo = newRepo()
        assertEquals(UpdaterState.Idle, repo.state.value)
    }

    @Test fun `refreshNow with UpToDate result transitions to UpToDate state`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.UpToDate
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.UpToDate }

        val state = repo.state.value
        assertTrue("expected UpToDate, got $state", state is UpdaterState.UpToDate)
        assertEquals("1.1.16", (state as UpdaterState.UpToDate).installedVersion)
    }

    @Test fun `refreshNow with Available result and no cache transitions to Available without auto-download`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.Available(
            version = "v1.1.17",
            downloadUrl = "https://example/apk",
            sizeBytes = 12_000_000L,
        )
        every { downloader.cachedFile("v1.1.17") } returns null
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.Available }

        val state = repo.state.value as UpdaterState.Available
        assertEquals("v1.1.17", state.version)
        assertEquals(12_000_000L, state.sizeBytes)
        // refreshNow path passes autoDownloadIfWifi=false; we must NOT
        // start a download just from a manual "Check for updates" tap.
        verify(exactly = 0) { downloader.download(any(), any()) }
    }

    @Test fun `refreshNow with Available result reuses cached file as ReadyToInstall`() = runTest {
        val cached = File.createTempFile("termx-v1.1.17", ".apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.Available(
            version = "v1.1.17",
            downloadUrl = "https://example/apk",
            sizeBytes = 12L,
        )
        every { downloader.cachedFile("v1.1.17") } returns cached
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.ReadyToInstall }

        val state = repo.state.value as UpdaterState.ReadyToInstall
        assertEquals("v1.1.17", state.version)
        assertEquals(cached, state.apkFile)
        // Download MUST be skipped when a finished file is already
        // sitting in cache from a previous attempt.
        verify(exactly = 0) { downloader.download(any(), any()) }
        cached.delete()
    }

    @Test fun `refreshNow with Available equal to skipped version transitions to Skipped`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.Available(
            version = "v1.1.17",
            downloadUrl = "https://example/apk",
            sizeBytes = 12_000_000L,
        )
        every { downloader.cachedFile(any()) } returns null
        prefs.setUpdaterSkippedVersion("v1.1.17")
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.Skipped }

        assertEquals(UpdaterState.Skipped, repo.state.value)
    }

    @Test fun `Available reappears once a newer version supersedes the previously-skipped tag`() = runTest {
        // User skipped v1.1.17 last week. Today the upstream tag is
        // v1.1.18 — we MUST surface it again, otherwise skipping a
        // single release suppresses every future release.
        coEvery { checker.check("1.1.17") } returns UpdateChecker.Result.Available(
            version = "v1.1.18",
            downloadUrl = "https://example/apk",
            sizeBytes = 12_000_000L,
        )
        every { downloader.cachedFile(any()) } returns null
        prefs.setUpdaterSkippedVersion("v1.1.17")
        val repo = newRepo()

        repo.refreshNow("1.1.17")
        waitForState(repo) { it is UpdaterState.Available }

        val state = repo.state.value as UpdaterState.Available
        assertEquals("v1.1.18", state.version)
    }

    @Test fun `refreshNow with Error result transitions to Error`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.Error("HTTP 502")
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.Error }

        val state = repo.state.value as UpdaterState.Error
        assertEquals("HTTP 502", state.message)
    }

    // ---- side effects on AppPreferences -------------------------------

    @Test fun `refreshNow stamps updaterLastCheckEpochMs on completion`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.UpToDate
        val before = System.currentTimeMillis()
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.UpToDate }

        val stamped = prefs.updaterLastCheckEpochMs.first()
        assertTrue("timestamp ($stamped) must advance past start ($before)", stamped >= before)
    }

    @Test fun `skip persists the version to DataStore and transitions to Skipped`() = runTest {
        val repo = newRepo()

        repo.skip("v1.1.17")
        waitForState(repo) { it is UpdaterState.Skipped }

        assertEquals("v1.1.17", prefs.updaterSkippedVersion.first())
    }

    @Test fun `dismiss resets to Idle`() = runTest {
        coEvery { checker.check("1.1.16") } returns UpdateChecker.Result.Error("nope")
        val repo = newRepo()

        repo.refreshNow("1.1.16")
        waitForState(repo) { it is UpdaterState.Error }

        repo.dismiss()
        assertEquals(UpdaterState.Idle, repo.state.value)
    }

    // ---- download path ------------------------------------------------

    @Test fun `startDownload progresses Available to Downloading then ReadyToInstall on success`() = runTest {
        val available = UpdaterState.Available(
            version = "v1.1.17",
            downloadUrl = "https://example/apk",
            sizeBytes = 100L,
        )
        val cached = File.createTempFile("termx-v1.1.17", ".apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        every { downloader.download("https://example/apk", "v1.1.17") } returns
            flowOf(ApkDownloader.Progress(bytesRead = 100L, bytesTotal = 100L))
        every { downloader.cachedFile("v1.1.17") } returns cached
        val repo = newRepo()

        repo.startDownload(available)
        waitForState(repo) { it is UpdaterState.ReadyToInstall }

        val state = repo.state.value as UpdaterState.ReadyToInstall
        assertEquals("v1.1.17", state.version)
        assertEquals(cached, state.apkFile)
        cached.delete()
    }

    @Test fun `startDownload surfaces failure as Error state`() = runTest {
        val available = UpdaterState.Available(
            version = "v1.1.17",
            downloadUrl = "https://example/apk",
            sizeBytes = 100L,
        )
        every { downloader.download(any(), any()) } returns kotlinx.coroutines.flow.flow {
            throw java.io.IOException("connection reset")
        }
        val repo = newRepo()

        repo.startDownload(available)
        waitForState(repo) { it is UpdaterState.Error }

        val state = repo.state.value as UpdaterState.Error
        assertTrue(
            "Error message must mention the underlying cause; got '${state.message}'",
            state.message.contains("connection reset"),
        )
    }
}
