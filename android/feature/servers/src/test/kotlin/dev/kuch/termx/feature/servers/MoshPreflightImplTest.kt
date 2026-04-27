package dev.kuch.termx.feature.servers

import dev.kuch.termx.libs.sshnative.ExecChannel
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshSession
import dev.kuch.termx.libs.sshnative.SshTarget
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MoshPreflightImpl] — the layered probe behind the
 * mosh-aware "Test connection" button. Each branch of the decision
 * tree maps to one of the user-actionable outcomes the UI surfaces.
 *
 * Tests use mockk because the impl drives both [SshClient] (for
 * `command -v mosh-server`) and [MoshClient] (for the handshake +
 * UDP probe), and standing up a real SshSession on the JVM would
 * require a sshd test fixture we don't have here.
 *
 * Real coroutines (no virtual clock) because the impl uses
 * [withTimeoutOrNull] internally; under a TestDispatcher the
 * timeouts would fire before the mocked first-byte emission could
 * land, masking the actual decision logic.
 */
class MoshPreflightImplTest {

    private val target = SshTarget(
        host = "203.0.113.1",
        port = 22,
        username = "root",
        knownHostsPath = "/tmp/known_hosts",
    )
    private val auth: SshAuth = SshAuth.Password("hunter2")

    // ---- step 1: command -v mosh-server -------------------------------

    @Test fun `command -v exits non-zero - mosh-server missing branch`() = runTest {
        val sshClient = sshClientReturningCommandVExit(exitCode = 1)
        val moshClient = mockk<MoshClient>()
        val pre = MoshPreflightImpl(sshClient, moshClient)

        val result = pre.run(target, auth)

        assertTrue("expected Failed, got $result", result is MoshStatus.Failed)
        val reason = (result as MoshStatus.Failed).reason
        assertTrue(
            "expected the apt-install hint, got: $reason",
            reason.contains("not on PATH") && reason.contains("apt install"),
        )
    }

    @Test fun `command -v throws - treated as missing rather than transport error`() = runTest {
        val sshClient = mockk<SshClient>()
        coEvery { sshClient.connect(any(), any(), any()) } throws java.io.IOException("boom")
        val moshClient = mockk<MoshClient>()
        val pre = MoshPreflightImpl(sshClient, moshClient)

        val result = pre.run(target, auth)

        // The SSH-only test already proved the transport works. A
        // sudden failure here is far more likely to mean mosh-server
        // is just missing than that SSH itself broke between two
        // back-to-back exec calls — bias toward the actionable
        // "install mosh" hint.
        assertTrue(result is MoshStatus.Failed)
        assertTrue((result as MoshStatus.Failed).reason.contains("apt install"))
    }

    // ---- step 2: mosh-server handshake ---------------------------------

    @Test fun `tryConnect returns null - HandshakeFailed branch`() = runTest {
        val sshClient = sshClientReturningCommandVExit(exitCode = 0)
        val moshClient = mockk<MoshClient>()
        coEvery { moshClient.tryConnect(any(), any(), any(), any(), any()) } returns null

        val pre = MoshPreflightImpl(sshClient, moshClient)
        val result = pre.run(target, auth)

        assertTrue(result is MoshStatus.Failed)
        val reason = (result as MoshStatus.Failed).reason
        assertTrue(
            "expected handshake-timeout copy, got: $reason",
            reason.contains("Handshake didn't complete"),
        )
    }

    // ---- step 3: first-byte UDP probe ----------------------------------

    @Test fun `first byte arrives within timeout - Ok`() = runTest {
        val sshClient = sshClientReturningCommandVExit(exitCode = 0)
        val moshSession = mockk<MoshSession>(relaxed = true)
        every { moshSession.output } returns flowOf(byteArrayOf('H'.code.toByte(), 'i'.code.toByte()))
        every { moshSession.diagnostic } returns
            MutableStateFlow(MoshDiagnostic(exitCode = null, elapsedMs = 0L, head = ""))
        val moshClient = mockk<MoshClient>()
        coEvery { moshClient.tryConnect(any(), any(), any(), any(), any()) } returns moshSession

        val pre = MoshPreflightImpl(sshClient, moshClient)
        val result = pre.run(target, auth)

        assertEquals(MoshStatus.Ok, result)
    }

    @Test fun `no first byte and no diagnostic - UDP-blocked hint`() = runTest {
        val sshClient = sshClientReturningCommandVExit(exitCode = 0)
        val moshSession = mockk<MoshSession>(relaxed = true)
        // Output never emits — simulates UDP packets being dropped
        // somewhere between phone and VPS. Diagnostic stays at the
        // initial "alive" snapshot so the timeout path runs.
        // Suspends forever without emitting — `first()` will be parked
        // when the surrounding `withTimeoutOrNull` deadline fires, which
        // is the path we want to exercise. `emptyFlow()` would instead
        // make `first()` throw NoSuchElementException and short-circuit
        // straight into the impl's outer runCatching.
        every { moshSession.output } returns flow { awaitCancellation() }
        every { moshSession.diagnostic } returns
            MutableStateFlow(MoshDiagnostic(exitCode = null, elapsedMs = 0L, head = ""))
        val moshClient = mockk<MoshClient>()
        coEvery { moshClient.tryConnect(any(), any(), any(), any(), any()) } returns moshSession

        val pre = MoshPreflightImpl(sshClient, moshClient)
        val result = pre.run(target, auth)

        assertTrue(result is MoshStatus.Failed)
        val reason = (result as MoshStatus.Failed).reason
        assertTrue(
            "expected the UDP-blocked hint, got: $reason",
            reason.contains("UDP") && reason.contains("60000"),
        )
    }

    @Test fun `no first byte but mosh-client crashed - exit-code branch`() = runTest {
        val sshClient = sshClientReturningCommandVExit(exitCode = 0)
        val moshSession = mockk<MoshSession>(relaxed = true)
        // Suspends forever without emitting — `first()` will be parked
        // when the surrounding `withTimeoutOrNull` deadline fires, which
        // is the path we want to exercise. `emptyFlow()` would instead
        // make `first()` throw NoSuchElementException and short-circuit
        // straight into the impl's outer runCatching.
        every { moshSession.output } returns flow { awaitCancellation() }
        // Diagnostic flips to a non-zero exit while the first-byte
        // probe is timing out — this is the late-runtime-crash case.
        every { moshSession.diagnostic } returns
            MutableStateFlow(MoshDiagnostic(exitCode = 137, elapsedMs = 1_500L, head = "killed"))
        val moshClient = mockk<MoshClient>()
        coEvery { moshClient.tryConnect(any(), any(), any(), any(), any()) } returns moshSession

        val pre = MoshPreflightImpl(sshClient, moshClient)
        val result = pre.run(target, auth)

        assertTrue(result is MoshStatus.Failed)
        val reason = (result as MoshStatus.Failed).reason
        assertTrue(
            "expected exit-code reference, got: $reason",
            reason.contains("exit 137"),
        )
    }

    @Test fun `output flow completes empty - regression for v1_1_19 NoSuchElementException leak`() = runTest {
        // v1.1.19 used `mosh.output.first()` here. When mosh-client
        // exits without writing anything (the user's actual case),
        // the channelFlow returns without ever emitting, `first()`
        // throws NoSuchElementException("Expected at least one
        // element"), and the message leaked to the user as
        // "Mosh: Unexpected error: Expected at least one element".
        // v1.1.20 uses firstOrNull and routes through MoshExitMessage
        // so this case now produces the same signal-decoded copy a
        // live connect attempt would.
        val sshClient = sshClientReturningCommandVExit(exitCode = 0)
        val moshSession = mockk<MoshSession>(relaxed = true)
        every { moshSession.output } returns emptyFlow()
        every { moshSession.diagnostic } returns
            MutableStateFlow(MoshDiagnostic(exitCode = 139, elapsedMs = 14L, head = ""))
        val moshClient = mockk<MoshClient>()
        coEvery { moshClient.tryConnect(any(), any(), any(), any(), any()) } returns moshSession

        val pre = MoshPreflightImpl(sshClient, moshClient)
        val result = pre.run(target, auth)

        assertTrue("expected Failed, got $result", result is MoshStatus.Failed)
        val reason = (result as MoshStatus.Failed).reason
        // Must surface the v1.1.20 signal-decoded format AND must
        // NOT leak the "Expected at least one element" Java message.
        assertTrue(
            "expected SIGSEGV decode, got: $reason",
            reason.contains("SIGSEGV"),
        )
        assertTrue(
            "expected exit 139 reference, got: $reason",
            reason.contains("exit 139"),
        )
        assertTrue(
            "expected the no-output-captured note, got: $reason",
            reason.contains("no output captured"),
        )
        assertFalse(
            "must not leak NoSuchElementException message, got: $reason",
            reason.contains("Expected at least one element"),
        )
    }

    // ---- helpers -------------------------------------------------------

    private fun sshClientReturningCommandVExit(exitCode: Int): SshClient {
        val sshClient = mockk<SshClient>()
        val session = mockk<SshSession>(relaxed = true)
        val exec = mockk<ExecChannel>(relaxed = true)
        coEvery { sshClient.connect(any(), any(), any()) } returns session
        coEvery { session.openExec(any()) } returns exec
        every { exec.stdout } returns flow { /* empty drain */ }
        every { exec.exitCode } returns CompletableDeferred(exitCode)
        return sshClient
    }
}
