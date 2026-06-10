package dev.kuch.termx.libs.sshnative

/**
 * Outcome of [MoshClient.tryConnectDetailed].
 *
 * [Success] carries the live [MoshSession]. [Failed] carries a structured
 * [MoshFailureReason] so callers can tell the user WHY mosh fell back to
 * SSH instead of silently burning the handshake timeout — the two most
 * common causes (no UTF-8 locale on the VPS, mosh-server not installed)
 * are both user-fixable and used to be invisible because only stdout of
 * the remote `mosh-server new` exec was read.
 */
sealed interface MoshConnectResult {
    /** The handshake landed and the local mosh-client is running. */
    data class Success(val session: MoshSession) : MoshConnectResult

    /** No usable session; [reason] explains why. Callers fall back to SSH. */
    data class Failed(val reason: MoshFailureReason) : MoshConnectResult
}

/**
 * Why [MoshClient.tryConnectDetailed] did not produce a usable session.
 *
 * Classified from the remote `mosh-server new` exec's stderr, which is
 * drained concurrently with stdout during the handshake. Mirrors the
 * [SshException] philosophy: a closed, transport-library-free hierarchy
 * so feature modules never import `net.schmizz.*`.
 */
sealed interface MoshFailureReason {
    /**
     * mosh-server hard-refused to start because the remote environment
     * has no UTF-8 native locale ("mosh-server needs a UTF-8 native
     * locale to run"). Fix: install/generate a UTF-8 locale on the VPS.
     */
    data object MissingUtf8Locale : MoshFailureReason

    /**
     * The remote shell could not resolve the `mosh-server` binary
     * ("command not found" / "not found"). Fix: install mosh on the VPS.
     */
    data object MoshServerMissing : MoshFailureReason

    /**
     * The `MOSH CONNECT <port> <key>` line never appeared within the
     * handshake budget and stderr stayed silent — slow host, filtered
     * SSH exec, or anything else that stalls without an error message.
     */
    data object HandshakeTimeout : MoshFailureReason

    /**
     * Anything else: [detail] is the first ~200 chars of the remote
     * stderr transcript, or a local description when the failure was on
     * our side (SSH bootstrap error, mosh-client spawn failure).
     */
    data class Other(val detail: String) : MoshFailureReason
}
