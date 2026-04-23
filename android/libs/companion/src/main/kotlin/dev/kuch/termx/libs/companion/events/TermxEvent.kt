package dev.kuch.termx.libs.companion.events

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Event types emitted by `termxd` (the VPS-side daemon) into
 * `~/.termx/events.ndjson`. The phone tails that file over SSH and decodes
 * each line into one of these subclasses.
 *
 * Wire format: a single JSON object per line with a top-level `type`
 * discriminator. Unknown or malformed lines surface as [Unknown] so the
 * phone keeps working when the daemon ships new event kinds.
 */
@Serializable
sealed class TermxEvent {
    abstract val ts: Instant
    abstract val session: String

    @Serializable
    @SerialName("session_created")
    data class SessionCreated(
        override val ts: Instant,
        override val session: String,
    ) : TermxEvent()

    @Serializable
    @SerialName("session_closed")
    data class SessionClosed(
        override val ts: Instant,
        override val session: String,
    ) : TermxEvent()

    @Serializable
    @SerialName("shell_command_long")
    data class ShellCommandLong(
        override val ts: Instant,
        override val session: String,
        val cmd: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("exit_code") val exitCode: Int,
        val pwd: String?,
    ) : TermxEvent()

    @Serializable
    @SerialName("shell_command_error")
    data class ShellCommandError(
        override val ts: Instant,
        override val session: String,
        val cmd: String,
        @SerialName("duration_ms") val durationMs: Long,
        @SerialName("exit_code") val exitCode: Int,
        val pwd: String?,
    ) : TermxEvent()

    @Serializable
    @SerialName("permission_requested")
    data class PermissionRequested(
        override val ts: Instant,
        override val session: String,
        @SerialName("request_id") val requestId: String,
        @SerialName("tool_name") val toolName: String,
        @SerialName("tool_args") val toolArgs: JsonElement,
    ) : TermxEvent()

    @Serializable
    @SerialName("permission_resolved")
    data class PermissionResolved(
        override val ts: Instant,
        override val session: String,
        @SerialName("request_id") val requestId: String,
        // "allow" | "deny" | "always"
        val decision: String,
        val reason: String?,
    ) : TermxEvent()

    @Serializable
    @SerialName("diff_created")
    data class DiffCreated(
        override val ts: Instant,
        override val session: String,
        @SerialName("diff_id") val diffId: String,
        @SerialName("file_path") val filePath: String,
        val tool: String,
    ) : TermxEvent()

    @Serializable
    @SerialName("claude_idle")
    data class ClaudeIdle(
        override val ts: Instant,
        override val session: String,
    ) : TermxEvent()

    @Serializable
    @SerialName("claude_working")
    data class ClaudeWorking(
        override val ts: Instant,
        override val session: String,
    ) : TermxEvent()

    /**
     * Fallback bucket. Not part of the wire schema; the parser assembles this
     * when it sees an unknown `type` or cannot decode a line at all.
     */
    data class Unknown(
        override val ts: Instant,
        override val session: String,
        val type: String,
        val raw: String,
    ) : TermxEvent()
}
