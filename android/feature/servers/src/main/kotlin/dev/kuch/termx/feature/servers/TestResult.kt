package dev.kuch.termx.feature.servers

/**
 * Outcome of pressing "Test connection" in the add/edit server sheet.
 *
 * Modeled as a sealed interface so UI code can exhaustively `when` over the
 * progress states and render the correct spinner / icon / message without a
 * fallback branch.
 *
 * State transitions observed by [AddEditServerViewModel]:
 *
 * ```
 * Idle ─► Running ─► Success(moshStatus)
 *              └───► Error(msg)
 * ```
 *
 * Any new edit to a form field resets the state back to [Idle] so a stale
 * green check can't misrepresent the current form.
 *
 * v1.1.19: [Success] now carries a [MoshStatus] so a mosh-flagged server
 * that passes SSH but fails the mosh-server handshake is reported
 * differently from one that passes both. SSH-only servers get
 * [MoshStatus.NotChecked] and the UI degenerates to the v1.1.18
 * "Connected successfully" row.
 */
sealed interface TestResult {
    data object Idle : TestResult

    data object Running : TestResult

    data class Success(val moshStatus: MoshStatus = MoshStatus.NotChecked) : TestResult

    data class Error(val message: String) : TestResult
}

/**
 * Outcome of the mosh-aware preflight performed after a successful SSH
 * handshake. Decoupled from [TestResult.Success] so the SSH-only happy
 * path (or a non-mosh server) doesn't have to invent a fake "OK" value
 * that could be confused with "we tested mosh and it worked".
 */
sealed interface MoshStatus {
    /** useMosh=false on the row, or preflight skipped — nothing to show. */
    data object NotChecked : MoshStatus

    /** mosh-server is present, handshake succeeded; mosh should work. */
    data object Ok : MoshStatus

    /**
     * mosh-aware preflight failed. [reason] is a short user-facing
     * line ("mosh-server not on PATH — `apt install mosh` on the VPS",
     * "Handshake didn't complete in 8s", …) the UI can render straight
     * into the row beneath the green "Connected" check.
     */
    data class Failed(val reason: String) : MoshStatus
}
