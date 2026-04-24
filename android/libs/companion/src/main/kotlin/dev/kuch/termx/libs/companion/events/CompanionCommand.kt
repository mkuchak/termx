package dev.kuch.termx.libs.companion.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Commands the phone writes back to the VPS as JSON files in
 * `~/.termx/commands/<id>.json`. `termxd` polls that directory, processes
 * each file, then deletes it; the resulting state change (a permission
 * resolution, an injected prompt) surfaces back to the phone via the
 * [TermxEvent] stream so every channel flows through the same tail.
 *
 * Wire format: JSON with a top-level `type` discriminator matching the
 * [SerialName] of the subclass. Every command carries an [id] (typically a
 * UUIDv4) which is used both as the filename stem and the request
 * correlation key in downstream events.
 *
 * Subclasses:
 *  - [ApprovePermission]: resolves a pending PreToolUse request (Phase 5).
 *  - [DenyPermission]: counterpart for a rejection.
 *  - [InjectPrompt]: injects text into a running tmux session's PTY
 *    (used by push-to-talk in Phase 6, but also by generic "send" flows).
 */
@Serializable
sealed class CompanionCommand {
    abstract val id: String

    /**
     * Approve a blocked [PermissionRequested][TermxEvent.PermissionRequested].
     *
     * @param remember if true, termxd also appends a rule to the VPS's
     *   `~/.claude/settings.json` so subsequent invocations with the same
     *   tool+args pattern auto-approve — this mirrors Claude's own
     *   `permissions.allow` and is the pathway the pattern-whitelist UI
     *   (Phase 5) uses.
     */
    @Serializable
    @SerialName("approve_permission")
    data class ApprovePermission(
        override val id: String,
        @SerialName("request_id") val requestId: String,
        val remember: Boolean = false,
    ) : CompanionCommand()

    /**
     * Deny a pending permission request with an optional human-readable
     * reason that the Claude hook will echo back as its decision message.
     */
    @Serializable
    @SerialName("deny_permission")
    data class DenyPermission(
        override val id: String,
        @SerialName("request_id") val requestId: String,
        val reason: String? = null,
    ) : CompanionCommand()

    /**
     * Inject [text] verbatim into the PTY of the named tmux [session]
     * (equivalent to `tmux send-keys -t <session> <text> Enter` on the
     * server side). Used by the PTT flow to deliver transcribed audio.
     */
    @Serializable
    @SerialName("inject_prompt")
    data class InjectPrompt(
        override val id: String,
        val session: String,
        val text: String,
    ) : CompanionCommand()

    /**
     * Append [pattern] to the VPS's `~/.termx/allowlist.txt` so subsequent
     * tool calls matching the pattern auto-approve without roundtripping
     * to the phone. Consumed by the Phase 5 "Always approve" button on
     * the permission dialog.
     *
     * [pattern] is a Go-compatible regex matched against
     * `<tool_name>|<command-or-path>` by `termx _hook-pretooluse`. The
     * UI is expected to escape / sanity-check before sending; we deliberately
     * do not gate here so power users can craft custom rules.
     */
    @Serializable
    @SerialName("update_allowlist")
    data class UpdateAllowlist(
        override val id: String,
        val pattern: String,
    ) : CompanionCommand()
}
