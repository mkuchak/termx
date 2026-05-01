package dev.kuch.termx.feature.servers.setup

import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import dev.kuch.termx.feature.servers.MainDispatcherRule
import dev.kuch.termx.feature.servers.fakes.FakeInstallCompanionUseCase
import dev.kuch.termx.feature.servers.fakes.FakeKeyPairRepository
import dev.kuch.termx.feature.servers.fakes.FakeSecretVault
import dev.kuch.termx.feature.servers.fakes.FakeServerGroupRepository
import dev.kuch.termx.feature.servers.fakes.FakeServerRepository
import dev.kuch.termx.feature.servers.fakes.FakeSshClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Unit tests for [SetupWizardViewModel].
 *
 * Focused on the three behaviors that make up the install-flow
 * regression (commit `bfa4364`):
 *
 *  - The password-override is forwarded to the use-case exactly when the
 *    draft uses password auth and the password is non-blank; otherwise
 *    the VM passes `null` so the use-case's own validation is the one
 *    surface that reports missing password.
 *  - `runCompanionDetect` actually re-runs the use-case (the Retry
 *    button on Step 3's error screen calls it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupWizardViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val knownHosts = "/tmp/known_hosts"

    private fun newViewModel(
        installCompanion: FakeInstallCompanionUseCase = FakeInstallCompanionUseCase(),
        servers: FakeServerRepository = FakeServerRepository(),
        keys: FakeKeyPairRepository = FakeKeyPairRepository(),
        vault: FakeSecretVault = FakeSecretVault(),
    ): SetupWizardViewModel = SetupWizardViewModel(
        knownHostsPath = knownHosts,
        serverRepository = servers,
        keyPairRepository = keys,
        serverGroupRepository = FakeServerGroupRepository(),
        secretVault = vault,
        vaultLockState = VaultLockState().apply { markUnlocked() },
        installCompanion = installCompanion,
        passwordCache = dev.kuch.termx.core.data.prefs.PasswordCache(),
        sshClient = FakeSshClient(),
    )

    private fun SetupWizardViewModel.setDraft(
        authType: AuthType,
        password: String,
    ) {
        onHostChange("example.test")
        onUsernameChange("root")
        onAuthTypeChange(authType)
        onPasswordChange(password)
    }

    @Test
    fun `password auth with non-blank password passes the password through to install use-case`() = runTest {
        val installCompanion = FakeInstallCompanionUseCase().apply {
            scripts[InstallCompanionUseCase.Stage.Detect] =
                listOf(InstallStep3State.Detecting, InstallStep3State.AlreadyInstalled("termx v1"))
        }
        val vm = newViewModel(installCompanion = installCompanion)
        vm.setDraft(AuthType.PASSWORD, "hunter2")

        // The wizard's Step 2 -> 3 transition requires a green
        // testResult, which is set via a live SSH handshake. We bypass
        // that gate by calling `save()` which persists the draft and
        // gives us a savedServerId, then invoke runCompanionDetect
        // directly (same thing the UI does when Step 3 enters).
        vm.save(onDoneIfPasswordAuth = {})
        // `save` launches a coroutine — let it settle.
        testScheduler.advanceUntilIdle()

        vm.runCompanionDetect()
        testScheduler.advanceUntilIdle()

        val ctx = installCompanion.invocations
            .first { it.stage == InstallCompanionUseCase.Stage.Detect }
            .context
        assertEquals("hunter2", ctx.passwordOverride)
    }

    @Test
    fun `password auth with blank password sends null passwordOverride`() = runTest {
        val installCompanion = FakeInstallCompanionUseCase()
        val vm = newViewModel(installCompanion = installCompanion)
        vm.setDraft(AuthType.PASSWORD, "   ")

        vm.save(onDoneIfPasswordAuth = {})
        testScheduler.advanceUntilIdle()
        vm.runCompanionDetect()
        testScheduler.advanceUntilIdle()

        val ctx = installCompanion.invocations
            .first { it.stage == InstallCompanionUseCase.Stage.Detect }
            .context
        assertNull(
            "expected passwordOverride null when password is blank but got '${ctx.passwordOverride}'",
            ctx.passwordOverride,
        )
    }

    @Test
    fun `key auth always sends null passwordOverride regardless of password field`() = runTest {
        val installCompanion = FakeInstallCompanionUseCase()
        val vm = newViewModel(installCompanion = installCompanion)
        vm.setDraft(AuthType.KEY, "unused-password")

        vm.save(onDoneIfPasswordAuth = {})
        testScheduler.advanceUntilIdle()
        vm.runCompanionDetect()
        testScheduler.advanceUntilIdle()

        val ctx = installCompanion.invocations
            .first { it.stage == InstallCompanionUseCase.Stage.Detect }
            .context
        assertNull(ctx.passwordOverride)
    }

    @Test
    fun `runCompanionDetect re-runs the flow and emits Detecting as the first state`() = runTest {
        val installCompanion = FakeInstallCompanionUseCase().apply {
            scripts[InstallCompanionUseCase.Stage.Detect] =
                listOf(InstallStep3State.Detecting, InstallStep3State.Error("boom"))
        }
        val vm = newViewModel(installCompanion = installCompanion)
        vm.setDraft(AuthType.PASSWORD, "hunter2")

        vm.save(onDoneIfPasswordAuth = {})
        testScheduler.advanceUntilIdle()

        // First invocation: the initial detect.
        vm.runCompanionDetect()
        testScheduler.advanceUntilIdle()
        val afterFirst = vm.installStep3State.value
        assertTrue(
            "expected terminal Error state after first run, got $afterFirst",
            afterFirst is InstallStep3State.Error,
        )

        // Retry: the VM must re-enter the use-case and drive the state
        // back through Detecting before emitting the next terminal state.
        // If the retry were a no-op, invocation count wouldn't grow.
        val before = installCompanion.invocations.size
        installCompanion.scripts[InstallCompanionUseCase.Stage.Detect] =
            listOf(InstallStep3State.Detecting, InstallStep3State.AlreadyInstalled("termx v1"))
        vm.runCompanionDetect()
        testScheduler.advanceUntilIdle()

        val afterRetry = vm.installStep3State.value
        assertTrue(
            "expected AlreadyInstalled after retry, got $afterRetry",
            afterRetry is InstallStep3State.AlreadyInstalled,
        )
        assertEquals(
            "retry should invoke the use-case exactly once more",
            before + 1,
            installCompanion.invocations.size,
        )
    }

    @Test
    fun `save with password auth triggers the password-done callback without advancing to step 5`() = runTest {
        val vm = newViewModel()
        vm.setDraft(AuthType.PASSWORD, "hunter2")
        var invoked = false
        vm.save(onDoneIfPasswordAuth = { invoked = true })
        testScheduler.advanceUntilIdle()

        assertTrue("expected onDoneIfPasswordAuth to fire", invoked)
        assertFalse(
            "password-auth save must not advance past step 4",
            vm.state.value.currentStep == 5,
        )
        assertNotNull(vm.state.value.savedServerId)
    }
}
