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
 * Idle ─► Running ─► Success
 *              └───► Error(msg)
 * ```
 *
 * Any new edit to a form field resets the state back to [Idle] so a stale
 * green check can't misrepresent the current form.
 */
sealed interface TestResult {
    data object Idle : TestResult

    data object Running : TestResult

    data object Success : TestResult

    data class Error(val message: String) : TestResult
}
