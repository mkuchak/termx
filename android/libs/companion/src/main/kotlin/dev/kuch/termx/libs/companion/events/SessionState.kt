package dev.kuch.termx.libs.companion.events

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persistent per-session state that `termxd` writes to
 * `~/.termx/sessions/<name>.json`. The phone reads this directory over SFTP
 * to paint the tab bar with richer metadata than `tmux ls` can provide:
 * whether Claude is running, whether Claude is idle/working, whether it's
 * currently blocked on a permission prompt.
 *
 * Note: distinct from `dev.kuch.termx.core.domain.model.TmuxSession` — that
 * one is the plain-tmux flavor (Task #25, no termxd required). This struct
 * exists only when termxd is installed on the VPS, and carries the Claude
 * discriminator plus status enum.
 *
 * Status legend:
 *  - `idle`: Claude is running but waiting for a prompt (or plain tmux with
 *    no activity for N seconds).
 *  - `working`: Claude is mid-tool-call, or a long-running shell command is
 *    in flight.
 *  - `awaiting_permission`: Claude's PreToolUse hook is blocked on a
 *    decision file (Phase 5).
 *
 * Unknown `status` values still decode — we match on string comparison at
 * the call site rather than an enum, to stay forward-compatible with new
 * states termxd introduces before the app picks them up.
 */
@Serializable
data class SessionState(
    val name: String,
    @SerialName("created_at") val createdAt: Instant,
    val windows: Int,
    val status: String,
    val claude: Boolean,
)
