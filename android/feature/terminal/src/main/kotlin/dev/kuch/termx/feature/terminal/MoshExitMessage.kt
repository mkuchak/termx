package dev.kuch.termx.feature.terminal

/**
 * Pure-logic decision for the mosh-client exit snackbar.
 *
 * v1.1.16 only surfaced a message when mosh-client died early (< 2 s)
 * with a non-zero code. That left two real failure modes invisible:
 *
 *  - **Late non-zero exit** — the linker / static-init crash window is
 *    < 50 ms, but a runtime crash (segfault inside ncurses, OOM-kill,
 *    SIGTERM from the OOM killer, …) can land at any time. Without
 *    this branch the user just sees a bare "disconnected" with no
 *    clue why.
 *  - **Clean fast exit** — when the UDP path between phone and VPS is
 *    blocked, mosh-client opens its socket, fails to reach the server
 *    on the first packets, and exits with code 0. v1.1.16 treated
 *    that the same as the user typing `exit` on the remote.
 *
 * This object centralises the decision so [TerminalViewModel] picks
 * the right snackbar copy and so the logic is unit-testable without
 * spinning up the whole VM. Returns null for cases that should NOT
 * surface a message (clean exit after a long-running session).
 */
internal object MoshExitMessage {

    /**
     * Window under which a clean (exit 0) mosh-client termination is
     * suspicious — the typical "user typed `exit`" path lands well
     * after this. Within the window we hint at the most common cause
     * (UDP blocked between phone and VPS).
     */
    const val SUSPECT_CLEAN_EXIT_MS: Long = 30_000L

    /**
     * Returns the snackbar text for a mosh-client exit, or null when
     * no message should be shown (normal long-running clean exit).
     */
    fun forDiagnostic(exitCode: Int, elapsedMs: Long, head: String): String? {
        val reason = extractReadableReason(head)
        val durationLabel = formatElapsed(elapsedMs)
        return when {
            exitCode != 0 ->
                "mosh-client exited after $durationLabel (exit $exitCode): $reason"
            elapsedMs < SUSPECT_CLEAN_EXIT_MS ->
                "mosh-client exited cleanly after $durationLabel — UDP path may be blocked. " +
                    "Check the VPS firewall on ports 60000-61000."
            else -> null
        }
    }

    /**
     * Render an elapsed-millis duration as `"14ms"`, `"5.3s"`, or
     * `"3m07s"` depending on magnitude. Keeps snackbar copy compact
     * while staying precise enough at the small end (where ms matter
     * for distinguishing pre-main crashes from post-main ones).
     */
    fun formatElapsed(ms: Long): String = when {
        ms < 1_000L -> "${ms}ms"
        ms < 60_000L -> "%.1fs".format(ms / 1000.0)
        else -> {
            val minutes = ms / 60_000L
            val seconds = (ms % 60_000L) / 1_000L
            "${minutes}m${"%02d".format(seconds)}s"
        }
    }

    /**
     * Pull the most useful single line out of mosh-client's captured
     * stderr / logcat head. Looks for ncurses' `Cannot find termcap`,
     * mosh's own `mosh-client: …` prefix, the linker's `cannot locate
     * symbol`, or any line containing `error`. Falls back to the first
     * non-empty line, then to a flattened size-capped form.
     *
     * Used to be private to [TerminalViewModel.extractReadableReason];
     * lifted here so the same logic powers the unit tests and any
     * future caller (e.g. the Settings diagnostics screen).
     */
    fun extractReadableReason(head: String): String {
        if (head.isBlank()) return "no output captured"
        val lines = head.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val preferred = lines.firstOrNull { line ->
            line.contains("termcap", ignoreCase = true) ||
                line.contains("terminal", ignoreCase = true) ||
                line.startsWith("mosh", ignoreCase = true) ||
                line.contains("error", ignoreCase = true)
        }
        val best = preferred ?: lines.firstOrNull() ?: head.replace('\n', ' ').trim()
        return best.take(200)
    }
}
