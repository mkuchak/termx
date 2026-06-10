package dev.kuch.termx.libs.companion.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Commands the phone can write to the VPS as JSON files in
 * `~/.termx/commands/<id>.json`.
 *
 * ⚠ STATUS: nothing on the VPS consumes this directory. termxd never grew
 * a commands poller (`CommandsDir` in termxd is only ever mkdir'd by
 * `install`), so dropping one of these files has no effect today. The live
 * permission round-trip is `EventStreamClient.respondToApproval`, which
 * writes `~/.termx/approvals/<id>.res.json` — the file the PreToolUse hook
 * actually polls — and "Always approve" persistence is
 * `EventStreamClient.appendAllowlistRule`. This schema and
 * `EventStreamClient.sendCommand` are retained for forward-compat with a
 * future server-side consumer (repo convention: decisions are superseded,
 * not erased).
 *
 * Wire format: JSON with a top-level `type` discriminator matching the
 * [SerialName] of the subclass. Every command carries an [id] (typically a
 * UUIDv4) which is used both as the filename stem and the request
 * correlation key in downstream events.
 *
 * Subclasses:
 *  - [ApprovePermission]: would resolve a pending PreToolUse request.
 *  - [DenyPermission]: counterpart for a rejection.
 *  - [UpdateAllowlist]: would append a broker-bypass rule.
 */
@Serializable
sealed class CompanionCommand {
    abstract val id: String

    /**
     * Approve a blocked [PermissionRequested][TermxEvent.PermissionRequested].
     *
     * @param remember intended: a future termxd consumer would also
     *   persist an auto-approve rule (mirroring Claude's own
     *   `permissions.allow`). Unconsumed today — the shipping equivalent
     *   is `EventStreamClient.respondToApproval(ALLOW)` plus
     *   `appendAllowlistRule`.
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
     * Intended: append [pattern] to the VPS's `~/.termx/allowlist.txt` so
     * subsequent tool calls matching the pattern auto-approve without
     * roundtripping to the phone. Unconsumed today — the "Always approve"
     * button now performs the append itself over SFTP via
     * `EventStreamClient.appendAllowlistRule`.
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
