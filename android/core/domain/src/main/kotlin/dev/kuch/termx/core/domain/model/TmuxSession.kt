package dev.kuch.termx.core.domain.model

import java.time.Instant

/**
 * A snapshot of one tmux session discovered on the remote host.
 *
 * Sourced initially by parsing `tmux ls -F ...` (Task #25); Phase 4 swaps
 * the transport to `~/.termx/sessions/*.json` but the model stays stable.
 *
 * [claudeDetected] is a cheap heuristic — any pane whose current command
 * contains "claude" or "node" flips the flag so the tab bar (Task #26)
 * can badge the session. False negatives are fine; the detection is
 * advisory, not authoritative.
 */
data class TmuxSession(
    val name: String,
    val activity: Instant,
    val attached: Boolean,
    val windowCount: Int,
    val claudeDetected: Boolean = false,
)
