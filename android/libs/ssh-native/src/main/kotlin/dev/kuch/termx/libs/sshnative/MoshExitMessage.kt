package dev.kuch.termx.libs.sshnative

/**
 * Pure-logic decision for the mosh-client exit snackbar / preflight
 * row.
 *
 * History:
 *  - v1.1.16 only surfaced a message when mosh-client died early
 *    (< 2 s) with a non-zero code.
 *  - v1.1.18 broadened that to all non-zero exits, plus the
 *    suspicious "clean fast" path that almost always means UDP is
 *    blocked between phone and VPS.
 *  - v1.1.20 lifted the helper from `:feature:terminal` to
 *    `:libs:ssh-native` (next to [MoshSession] / [MoshDiagnostic])
 *    so both the live-connect path AND the [MoshPreflightImpl] in
 *    `:feature:servers` share one decision tree, and added signal-
 *    decoding (139 → SIGSEGV, 137 → SIGKILL, …) so an empty PTY-head
 *    + empty logcat still gives the user *something* actionable —
 *    "exit 139 (SIGSEGV)" beats "exit 139".
 *
 * Returns null for cases that should NOT surface a message (normal
 * clean exit after a long session — user typed `exit` on the remote).
 */
object MoshExitMessage {

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
        val exitLabel = formatExitCode(exitCode)
        return when {
            exitCode != 0 ->
                "mosh-client exited after $durationLabel ($exitLabel): $reason"
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
     * Format a waitpid-style exit code. Codes 128 + N (POSIX
     * convention) decode to "killed by signal N"; we surface the
     * common ones by name so the user sees "exit 139 (SIGSEGV)"
     * instead of just "exit 139". This is the difference between
     * "the process crashed" and "the process exited with status 139"
     * to a non-systems-programmer.
     */
    fun formatExitCode(exitCode: Int): String {
        if (exitCode <= 128) return "exit $exitCode"
        val signal = exitCode - 128
        val name = SIGNAL_NAMES[signal] ?: return "exit $exitCode"
        return "exit $exitCode, $name"
    }

    /**
     * Pull the most useful single line out of mosh-client's captured
     * stderr / logcat head. Looks for ncurses' `Cannot find termcap`,
     * mosh's own `mosh-client: …` prefix, the linker's `cannot locate
     * symbol`, the kernel's `Fatal signal …` tombstone marker, or any
     * line containing `error`. Falls back to the first non-empty line,
     * then to a flattened size-capped form.
     */
    fun extractReadableReason(head: String): String {
        if (head.isBlank()) return "no output captured"
        val lines = head.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val preferred = lines.firstOrNull { line ->
            line.contains("termcap", ignoreCase = true) ||
                line.contains("terminal", ignoreCase = true) ||
                line.startsWith("mosh", ignoreCase = true) ||
                line.contains("error", ignoreCase = true) ||
                line.contains("CANNOT LINK", ignoreCase = true) ||
                line.contains("cannot locate", ignoreCase = true) ||
                line.contains("Fatal signal", ignoreCase = true) ||
                line.contains("library", ignoreCase = true) && line.contains("not found", ignoreCase = true)
        }
        val best = preferred ?: lines.firstOrNull() ?: head.replace('\n', ' ').trim()
        return best.take(200)
    }

    /**
     * The signals worth naming in user copy. These are the ones a
     * mosh-client exit could realistically carry on a typical Android
     * device (excluding signals like SIGSTOP that wouldn't terminate
     * the process at all).
     */
    private val SIGNAL_NAMES: Map<Int, String> = mapOf(
        1 to "SIGHUP",
        2 to "SIGINT",
        3 to "SIGQUIT",
        4 to "SIGILL",
        6 to "SIGABRT",
        7 to "SIGBUS",
        8 to "SIGFPE",
        9 to "SIGKILL",
        11 to "SIGSEGV",
        13 to "SIGPIPE",
        14 to "SIGALRM",
        15 to "SIGTERM",
    )
}
