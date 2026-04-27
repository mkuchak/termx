package dev.kuch.termx.feature.servers

import android.util.Log
import dev.kuch.termx.libs.sshnative.MoshClient
import dev.kuch.termx.libs.sshnative.MoshSession
import dev.kuch.termx.libs.sshnative.SshAuth
import dev.kuch.termx.libs.sshnative.SshClient
import dev.kuch.termx.libs.sshnative.SshTarget
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mosh-aware extension of the "Test connection" flow.
 *
 * The plain SSH test only proves that ssh-{auth,connect} works against
 * the host; it tells the user nothing about whether mosh will. The
 * three most common ways mosh fails on a working SSH host are:
 *
 *  1. **mosh-server not installed** on the VPS (`apt install mosh`
 *     never ran). Caught by `command -v mosh-server` in step 1.
 *  2. **mosh-server can't bind / handshake never lands** (port range
 *     blocked, mosh-server crash, locale mismatch swallowing the
 *     `MOSH CONNECT` line). Caught by step 2 below — we run the
 *     handshake the same way `TerminalViewModel.openSession` does.
 *  3. **UDP path blocked** between phone and VPS (NAT, firewall on
 *     ports 60000-60010). Caught by step 3 — we actually launch
 *     mosh-client locally and wait briefly for the first byte. No
 *     byte ⇒ phone never received a server frame ⇒ UDP is dropping.
 *
 * Steps 2 and 3 piggy-back on [MoshClient.tryConnect] which already
 * does both internally; we just observe the resulting [MoshSession]'s
 * `output` flow with a short timeout. mosh-server's natural 60s
 * no-client timeout reaps the orphaned VPS process if step 3 fails.
 */
fun interface MoshPreflight {

    /**
     * Run the layered mosh probe. Caller has already confirmed SSH
     * itself works, so we don't redo that here.
     *
     * Returns a [MoshStatus] the UI can render straight into the
     * "Test connection" row. Never throws — every failure is
     * collapsed to [MoshStatus.Failed] with a short user-facing
     * line.
     */
    suspend fun run(target: SshTarget, auth: SshAuth): MoshStatus
}

/**
 * Production implementation. Bound to [MoshPreflight] via Hilt's
 * [ServersModule] so the VMs can `@Inject` the interface and the
 * unit tests can pass a one-liner anonymous implementation.
 */
@Singleton
class MoshPreflightImpl @Inject constructor(
    private val sshClient: SshClient,
    private val moshClient: MoshClient,
) : MoshPreflight {

    override suspend fun run(target: SshTarget, auth: SshAuth): MoshStatus = runCatching {
        val moshServerPresent = checkMoshServerOnPath(target, auth)
        if (!moshServerPresent) {
            return@runCatching MoshStatus.Failed(
                "mosh-server not on PATH — run `sudo apt install mosh` on the VPS.",
            )
        }

        val mosh = moshClient.tryConnect(
            target = target,
            auth = auth,
            handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS,
        ) ?: return@runCatching MoshStatus.Failed(
            "Handshake didn't complete in ${HANDSHAKE_TIMEOUT_MS / 1_000}s — " +
                "mosh-server couldn't bind a port or printed an unparseable banner.",
        )

        try {
            probeFirstByte(mosh)
        } finally {
            runCatching { mosh.close() }
        }
    }.getOrElse { t ->
        Log.w(LOG_TAG, "mosh preflight threw", t)
        MoshStatus.Failed("Unexpected error: ${t.message ?: t.javaClass.simpleName}")
    }

    /**
     * One-shot SSH exec of `command -v mosh-server`. Returns true iff
     * the command exits 0 (POSIX guarantee that the binary is on
     * PATH). Any error → false; the caller treats that as
     * "mosh-server missing" rather than reporting a transport-level
     * failure here, since the SSH test already proved the transport
     * itself is healthy.
     */
    private suspend fun checkMoshServerOnPath(
        target: SshTarget,
        auth: SshAuth,
    ): Boolean = runCatching {
        sshClient.connect(target, auth, timeoutMillis = COMMAND_V_TIMEOUT_MS).use { session ->
            session.openExec("command -v mosh-server").use { exec ->
                exec.stdout.collect { /* drain so the channel can close */ }
                exec.exitCode.await() == 0
            }
        }
    }.getOrElse { t ->
        Log.w(LOG_TAG, "command -v mosh-server failed", t)
        false
    }

    /**
     * Wait for the first byte mosh-server pushes back over UDP. mosh
     * always emits at least one frame on connect (state-sync); failure
     * to receive any byte within [FIRST_BYTE_TIMEOUT_MS] is the
     * canonical signature of a blocked UDP path on ports 60000-60010.
     *
     * If the byte arrives we succeed regardless of exit code (the
     * close in the caller's `finally` triggers a clean teardown). If
     * the timeout elapses we sniff the diagnostic for a hard crash
     * before falling back to the UDP-blocked hint.
     */
    private suspend fun probeFirstByte(mosh: MoshSession): MoshStatus {
        val firstByte = withTimeoutOrNull(FIRST_BYTE_TIMEOUT_MS) {
            mosh.output.first()
        }
        if (firstByte != null) return MoshStatus.Ok

        val diag = withTimeoutOrNull(DIAG_AFTER_TIMEOUT_MS) {
            mosh.diagnostic.first { it.exitCode != null }
        }
        return if (diag != null && diag.exitCode != null && diag.exitCode != 0) {
            MoshStatus.Failed(
                "mosh-client exited unexpectedly (exit ${diag.exitCode}). Reconnect to see details.",
            )
        } else {
            MoshStatus.Failed(
                "Handshake OK but no UDP traffic in ${FIRST_BYTE_TIMEOUT_MS / 1_000}s — " +
                    "VPS firewall likely blocks UDP ports 60000-60010.",
            )
        }
    }

    private companion object {
        const val LOG_TAG = "MoshPreflight"
        const val COMMAND_V_TIMEOUT_MS = 5_000L
        const val HANDSHAKE_TIMEOUT_MS = 8_000L
        const val FIRST_BYTE_TIMEOUT_MS = 5_000L
        const val DIAG_AFTER_TIMEOUT_MS = 1_000L
    }
}
