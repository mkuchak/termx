package dev.kuch.termx.libs.sshnative

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.libs.sshnative.impl.MoshClientImpl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for the mosh transport.
 *
 * Opens a short-lived SSH session against the target, runs
 * `mosh-server new -i <bindIp> -p <portRange>`, parses the
 * `MOSH CONNECT <port> <key>` line from its stdout, and then spawns the
 * on-device `libmoshclient.so` (from
 * [android.content.pm.ApplicationInfo.nativeLibraryDir]) to handle the
 * UDP handshake. If the mosh-server line does not appear within
 * [tryConnect]'s timeout window the caller is expected to fall back to
 * plain SSH — see `TerminalViewModel.openSession` for the race.
 */
@Singleton
open class MoshClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sshClient: SshClient,
) {
    /**
     * Try to establish a mosh session.
     *
     * Returns `null` if the mosh-server handshake does not complete
     * within [handshakeTimeoutMs] milliseconds (server missing, port
     * range exhausted, firewall dropping UDP during the startup probe,
     * anything else that prevents a clean `MOSH CONNECT` line). The
     * caller falls back to plain SSH in that case — this is the
     * 8-second race documented in the roadmap Phase 3 notes.
     *
     * Open for JVM unit tests that want to substitute a fake by
     * subclassing.
     */
    open suspend fun tryConnect(
        target: SshTarget,
        auth: SshAuth,
        bindIp: String = "0.0.0.0",
        portRange: String = "60000:60010",
        handshakeTimeoutMs: Long = 8_000,
    ): MoshSession? = MoshClientImpl(context, sshClient).tryConnect(
        target = target,
        auth = auth,
        bindIp = bindIp,
        portRange = portRange,
        handshakeTimeoutMs = handshakeTimeoutMs,
    )
}
