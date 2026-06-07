package dev.kuch.termx.core.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.remote.fakes.FakeExecChannel
import dev.kuch.termx.core.data.remote.fakes.FakeInstallCompanionUseCase
import dev.kuch.termx.core.data.remote.fakes.FakeSshSession
import dev.kuch.termx.core.data.remote.fakes.FakeTermxReleaseFetcher
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Decision-logic tests for [CompanionUpdateRepository] — the on-connect
 * companion (termxd) update check (OPT-2, Task #32).
 *
 * Drives the repo with a programmable [FakeSshSession] (the `termx --version`
 * / `uname -m` probe), the canned [FakeTermxReleaseFetcher] (latest =
 * `termxd-v0.1.0`, amd64 + arm64 assets), and a real [AppPreferences] over
 * Robolectric's in-memory DataStore for the TTL + skip memories.
 *
 * IMPORTANT: the DataStore singleton is per (Context, name) and Robolectric
 * reuses the Context across tests in the same JVM, so each case uses a FRESH
 * random `serverId` to avoid the skip-set / last-check entries written by a
 * prior test bleeding in. (Same hazard documented in `AppPreferencesTest`.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CompanionUpdateRepositoryTest {

    private lateinit var prefs: AppPreferences

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = AppPreferences(context)
    }

    private fun repo(
        fetcher: FakeTermxReleaseFetcher = FakeTermxReleaseFetcher(),
        useCase: FakeInstallCompanionUseCase = FakeInstallCompanionUseCase(),
    ) = CompanionUpdateRepository(
        releaseFetcher = fetcher,
        appPreferences = prefs,
        installUseCase = useCase,
    )

    /** A session that reports `termx` at [path] printing [versionOutput], on [arch]. */
    private fun installedSession(
        path: String = "/home/user/.local/bin/termx",
        versionOutput: String,
        arch: String = "x86_64",
    ) = FakeSshSession(
        execResponses = mapOf(
            "command -v termx" to FakeExecChannel(stdout = "$path\n"),
            "uname -m" to FakeExecChannel(stdout = "$arch\n"),
            "--version" to FakeExecChannel(stdout = versionOutput),
        ),
    )

    /** A session with no `termx` binary present, on [arch]. */
    private fun missingSession(arch: String = "x86_64") = FakeSshSession(
        execResponses = mapOf(
            "command -v termx" to FakeExecChannel(stdout = "\n"),
            "uname -m" to FakeExecChannel(stdout = "$arch\n"),
        ),
    )

    @Test
    fun `missing binary yields Missing with arch-matched asset`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()

        repo.maybeOfferUpdate(serverId, missingSession(arch = "aarch64"))

        val state = repo.state.value
        assertTrue("expected Missing, got $state", state is CompanionUpdateState.Missing)
        state as CompanionUpdateState.Missing
        assertEquals(serverId, state.serverId)
        assertEquals("arm64", state.arch)
        assertEquals("termxd-v0.1.0", state.latestTag)
        assertTrue("expected arm64 asset, got ${state.downloadUrl}", state.downloadUrl.contains("arm64"))
    }

    @Test
    fun `older installed version yields UpdateAvailable`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()

        // Installed 0.0.9 < latest termxd-v0.1.0.
        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "termx version 0.0.9\n"))

        val state = repo.state.value
        assertTrue("expected UpdateAvailable, got $state", state is CompanionUpdateState.UpdateAvailable)
        state as CompanionUpdateState.UpdateAvailable
        assertEquals(serverId, state.serverId)
        assertEquals("termxd-v0.1.0", state.latestTag)
        assertTrue("expected installed capture, got ${state.installed}", state.installed.contains("0.0.9"))
        assertTrue("expected x86_64 asset, got ${state.downloadUrl}", state.downloadUrl.contains("x86_64"))
    }

    @Test
    fun `version-unknown installed binary still offers an update`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()

        // `termx --version` prints nothing -> "version unknown" sentinel ->
        // treated as the zero version -> reinstall offered.
        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "\n"))

        assertTrue(repo.state.value is CompanionUpdateState.UpdateAvailable)
    }

    @Test
    fun `equal installed version yields UpToDate`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()

        // Installed 0.1.0 == latest termxd-v0.1.0.
        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "termx version 0.1.0\n"))

        assertEquals(CompanionUpdateState.UpToDate, repo.state.value)
    }

    @Test
    fun `newer installed version yields UpToDate`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()

        // Installed 0.2.0 > latest termxd-v0.1.0 — nothing to offer.
        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "termx version 0.2.0\n"))

        assertEquals(CompanionUpdateState.UpToDate, repo.state.value)
    }

    @Test
    fun `previously-skipped tag collapses an offer to Skipped`() = runTest {
        val serverId = UUID.randomUUID()
        // User already dismissed exactly this (server, tag) pair.
        prefs.addCompanionUpdateSkipped(serverId, "termxd-v0.1.0")
        val repo = repo()

        // An outdated binary WOULD normally produce UpdateAvailable...
        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "termx version 0.0.9\n"))

        // ...but the skip memory keeps it quiet.
        assertEquals(CompanionUpdateState.Skipped, repo.state.value)
    }

    @Test
    fun `skip memory is keyed per version - a newer tag re-surfaces the offer`() = runTest {
        val serverId = UUID.randomUUID()
        // Skipped an OLDER tag; the live release is termxd-v0.1.0, so the
        // token doesn't match and the offer should still appear.
        prefs.addCompanionUpdateSkipped(serverId, "termxd-v0.0.5")
        val repo = repo()

        repo.maybeOfferUpdate(serverId, installedSession(versionOutput = "termx version 0.0.9\n"))

        assertTrue(repo.state.value is CompanionUpdateState.UpdateAvailable)
    }

    @Test
    fun `within TTL the repo does not re-probe`() = runTest {
        val serverId = UUID.randomUUID()
        // Stamp a check as having happened just now.
        prefs.setCompanionUpdateLastCheck(serverId, System.currentTimeMillis())
        val repo = repo()
        val session = installedSession(versionOutput = "termx version 0.0.9\n")

        repo.maybeOfferUpdate(serverId, session)

        // No SSH exec ran, and the state was never moved off Idle.
        assertTrue("expected no probe within TTL, ran ${session.execHistory}", session.execHistory.isEmpty())
        assertEquals(CompanionUpdateState.Idle, repo.state.value)
    }

    @Test
    fun `a stale last-check does NOT suppress the probe`() = runTest {
        val serverId = UUID.randomUUID()
        // 25h ago — older than the 24h TTL, so a re-probe must happen.
        val stale = System.currentTimeMillis() - (25L * 60L * 60L * 1000L)
        prefs.setCompanionUpdateLastCheck(serverId, stale)
        val repo = repo()
        val session = installedSession(versionOutput = "termx version 0.0.9\n")

        repo.maybeOfferUpdate(serverId, session)

        assertTrue("expected a probe past the TTL", session.execHistory.isNotEmpty())
        assertTrue(repo.state.value is CompanionUpdateState.UpdateAvailable)
    }

    @Test
    fun `unsupported arch leaves no offer and stamps the TTL`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo()
        val session = FakeSshSession(
            execResponses = mapOf(
                "command -v termx" to FakeExecChannel(stdout = "\n"),
                "uname -m" to FakeExecChannel(stdout = "sparc\n"),
            ),
        )

        repo.maybeOfferUpdate(serverId, session)

        // Nothing actionable surfaced...
        assertEquals(CompanionUpdateState.Idle, repo.state.value)
        // ...and the TTL was stamped so a reconnect won't immediately re-probe.
        val session2 = FakeSshSession(
            execResponses = mapOf("command -v termx" to FakeExecChannel(stdout = "\n")),
        )
        repo.maybeOfferUpdate(serverId, session2)
        assertTrue("expected TTL to suppress the second probe", session2.execHistory.isEmpty())
    }

    @Test
    fun `release fetch failure leaves no offer`() = runTest {
        val serverId = UUID.randomUUID()
        val repo = repo(fetcher = FakeTermxReleaseFetcher(failure = java.io.IOException("offline")))

        repo.maybeOfferUpdate(serverId, missingSession())

        assertEquals(CompanionUpdateState.Idle, repo.state.value)
    }

    // --- install relay ------------------------------------------------------

    @Test
    fun `install runs Preview then Install and ends Installed`() = runTest {
        val serverId = UUID.randomUUID()
        val useCase = FakeInstallCompanionUseCase().apply {
            scripts[InstallCompanionUseCase.Stage.Preview] = listOf(
                InstallStep3State.Downloading("Downloading asset..."),
                InstallStep3State.PreviewingDiff(emptyList()),
            )
            scripts[InstallCompanionUseCase.Stage.Install] = listOf(
                InstallStep3State.Installing(listOf("step one")),
                InstallStep3State.Success,
            )
        }
        val repo = repo(useCase = useCase)

        repo.install(serverId, "https://example.test/termxd_Linux_x86_64.tar.gz")

        assertEquals(CompanionUpdateState.Installed, repo.state.value)
        // Both stages ran, Preview before Install, with the URL plumbed through.
        assertEquals(
            listOf(InstallCompanionUseCase.Stage.Preview, InstallCompanionUseCase.Stage.Install),
            useCase.invocations.map { it.stage },
        )
        assertEquals(
            "https://example.test/termxd_Linux_x86_64.tar.gz",
            useCase.invocations.first().context.downloadUrl,
        )
    }

    @Test
    fun `install surfaces a Preview error without running Install`() = runTest {
        val serverId = UUID.randomUUID()
        val useCase = FakeInstallCompanionUseCase().apply {
            scripts[InstallCompanionUseCase.Stage.Preview] = listOf(
                InstallStep3State.Error("Download failed (exit 7)."),
            )
        }
        val repo = repo(useCase = useCase)

        repo.install(serverId, "https://example.test/asset.tar.gz")

        val state = repo.state.value
        assertTrue("expected Error, got $state", state is CompanionUpdateState.Error)
        state as CompanionUpdateState.Error
        assertTrue(state.message.contains("Download failed"))
        // Install stage must NOT have run after a failed preview.
        assertEquals(
            listOf(InstallCompanionUseCase.Stage.Preview),
            useCase.invocations.map { it.stage },
        )
    }
}
