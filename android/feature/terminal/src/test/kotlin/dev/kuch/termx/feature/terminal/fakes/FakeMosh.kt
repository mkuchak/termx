package dev.kuch.termx.feature.terminal.fakes

import android.content.Context
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshConnectResult
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshFailureReason
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshTarget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * A live-but-inert mosh session.
 *
 * By default [output] emits one greeting chunk (so the VM's
 * first-output liveness gate passes immediately, mirroring a healthy
 * mosh link where mosh-server pushes the initial screen state on first
 * UDP contact) and then parks forever — the session stays installed as
 * `activeShell.moshSession`, which is exactly what the side-channel
 * torn-down guard reads, and the diagnostic stays `exitCode = null` so
 * the early-exit error branch never fires.
 *
 * Pass [emitFirstOutput] = false to model the firewalled-UDP hang: the
 * handshake succeeded but mosh-client never produces a byte. The flow
 * never emits and never completes, so the only way out is the VM's
 * liveness timeout → SSH fallback.
 */
class FakeMoshSession(
    private val emitFirstOutput: Boolean = true,
) : MoshSession {
    override val output: Flow<ByteArray> = flow {
        if (emitFirstOutput) emit("mosh-screen".toByteArray(Charsets.UTF_8))
        awaitCancellation()
    }
    override val diagnostic: StateFlow<MoshDiagnostic> =
        MutableStateFlow(MoshDiagnostic(exitCode = null, elapsedMs = 0, head = ""))
    val closed = AtomicInteger(0)

    override suspend fun write(bytes: ByteArray) { /* no-op */ }
    override suspend fun resize(cols: Int, rows: Int) { /* no-op */ }
    override fun close() {
        closed.incrementAndGet()
    }
}

/**
 * [MoshClient] override returning a pre-built [MoshSession] (or, when
 * [session] is null, a [MoshConnectResult.Failed] carrying
 * [failureReason] to force the SSH fallback). The real super-constructor
 * still needs a [Context] + [SshClient]; we never call into them because
 * [tryConnectDetailed] — the entry point the VM uses — is fully
 * overridden, and the base `tryConnect` delegates to it.
 */
class FakeMoshClient(
    context: Context,
    sshClient: SshClient,
    private val session: MoshSession?,
    private val failureReason: MoshFailureReason = MoshFailureReason.HandshakeTimeout,
) : MoshClient(context, sshClient) {
    val tryConnectCount = AtomicInteger(0)

    override suspend fun tryConnectDetailed(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
        handshakeTimeoutMs: Long,
        startupCommand: String?,
    ): MoshConnectResult {
        tryConnectCount.incrementAndGet()
        return session?.let { MoshConnectResult.Success(it) }
            ?: MoshConnectResult.Failed(failureReason)
    }
}
