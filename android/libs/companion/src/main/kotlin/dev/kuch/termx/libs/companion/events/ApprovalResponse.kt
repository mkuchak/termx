package dev.kuch.termx.libs.companion.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phone-side decision for one blocked PreToolUse request, written to
 * `~/.termx/approvals/<request_id>.res.json` on the VPS — the file
 * `termx _hook-pretooluse` polls every 100 ms while it blocks Claude.
 *
 * SCHEMA CONTRACT — field names must match the Go struct EXACTLY
 * (`termxd/cmd/hook_pretooluse.go`, type `approvalResponse`):
 *
 * ```go
 * type approvalResponse struct {
 *     Decision string `json:"decision"` // "approve" | "deny"
 *     Reason   string `json:"reason,omitempty"`
 * }
 * ```
 *
 * Grep anchor for schema drift: hook_pretooluse.go. If either side renames
 * a field, the golden fixtures under `src/test/resources/approvals-golden/`
 * fail first.
 *
 * Decision semantics on the Go side (`runHookPreToolUse`): the value is
 * lowercased + trimmed, then ONLY `"approve"` and `"allow"` unblock the
 * tool call — every other string (including `"always"`) is a deny. That is
 * why there is no ALWAYS entry below: "Always approve" is `allow` on the
 * wire, and its persistence half lives entirely on the phone via
 * [dev.kuch.termx.libs.companion.EventStreamClient.appendAllowlistRule]
 * (the Go hook persists nothing when it reads a response).
 *
 * [reason] mirrors Go's `omitempty`: `null` is omitted from the JSON
 * (kotlinx default `encodeDefaults = false`), and a non-null value is
 * echoed by the hook to Claude's stderr on deny.
 */
@Serializable
data class ApprovalResponse(
    val decision: Decision,
    val reason: String? = null,
) {
    /** Wire values accepted by `hook_pretooluse.go`. See class KDoc. */
    @Serializable
    enum class Decision {
        @SerialName("allow")
        ALLOW,

        @SerialName("deny")
        DENY,
    }
}
