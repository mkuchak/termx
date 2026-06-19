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
 * `mosh-server new -s -p <portRange>` (`-s` binds the `$SSH_CONNECTION`
 * address, so the UDP socket follows the bootstrap SSH's address
 * family), parses the
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
     * Lossy convenience over [tryConnectDetailed]: the failure reason is
     * dropped. Prefer the detailed variant when the caller wants to tell
     * the user WHY mosh fell back (missing UTF-8 locale and missing
     * mosh-server are both user-fixable).
     *
     * Open for JVM unit tests that want to substitute a fake by
     * subclassing.
     */
    open suspend fun tryConnect(
        target: SshTarget,
        auth: SshAuth,
        portRange: String = "60000:60010",
        handshakeTimeoutMs: Long = 8_000,
        startupCommand: String? = null,
    ): MoshSession? = (
        tryConnectDetailed(
            target = target,
            auth = auth,
            portRange = portRange,
            handshakeTimeoutMs = handshakeTimeoutMs,
            startupCommand = startupCommand,
        ) as? MoshConnectResult.Success
        )?.session

    /**
     * Like [tryConnect], but reports the handshake outcome as a
     * [MoshConnectResult] instead of collapsing every failure to `null`.
     *
     * [MoshConnectResult.Failed.reason] is classified from the remote
     * `mosh-server new` exec's stderr (drained concurrently with stdout):
     * [MoshFailureReason.MissingUtf8Locale], [MoshFailureReason.MoshServerMissing],
     * [MoshFailureReason.HandshakeTimeout], or [MoshFailureReason.Other].
     *
     * Open for JVM unit tests that want to substitute a fake by
     * subclassing.
     */
    open suspend fun tryConnectDetailed(
        target: SshTarget,
        auth: SshAuth,
        portRange: String = "60000:60010",
        handshakeTimeoutMs: Long = 8_000,
        startupCommand: String? = null,
    ): MoshConnectResult = MoshClientImpl(context, sshClient).tryConnect(
        target = target,
        auth = auth,
        portRange = portRange,
        handshakeTimeoutMs = handshakeTimeoutMs,
        startupCommand = startupCommand,
    )
}
