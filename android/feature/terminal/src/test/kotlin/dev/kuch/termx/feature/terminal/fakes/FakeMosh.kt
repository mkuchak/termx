package dev.kuch.termx.feature.terminal.fakes

import android.content.Context
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshDiagnostic
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshTarget
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A live-but-inert mosh session. `output` is an empty flow so the VM's
 * output-collector completes immediately without ever flipping the shell
 * into the early-exit error branch (diagnostic stays `exitCode = null`),
 * and the session reference stays installed as `activeShell.moshSession`
 * — which is exactly what the side-channel torn-down guard reads.
 */
class FakeMoshSession : MoshSession {
    override val output: Flow<ByteArray> = emptyFlow()
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
 * [MoshClient] override returning a pre-built [MoshSession] (or null to
 * force the SSH fallback). The real super-constructor still needs a
 * [Context] + [SshClient]; we never call into them because [tryConnect]
 * is fully overridden.
 */
class FakeMoshClient(
    context: Context,
    sshClient: SshClient,
    private val session: MoshSession?,
) : MoshClient(context, sshClient) {
    val tryConnectCount = AtomicInteger(0)

    override suspend fun tryConnect(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String,
        portRange: String,
        handshakeTimeoutMs: Long,
        startupCommand: String?,
    ): MoshSession? {
        tryConnectCount.incrementAndGet()
        return session
    }
}
