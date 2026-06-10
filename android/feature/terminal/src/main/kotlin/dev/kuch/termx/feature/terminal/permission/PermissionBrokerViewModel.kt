package dev.kuch.termx.feature.terminal.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.libs.companion.events.ApprovalResponse
import dev.kuch.termx.libs.companion.events.TermxEvent
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phone-side half of the Phase 5 permission broker.
 *
 * Subscribes to every published [EventStreamHub] entry and collects its
 * [dev.kuch.termx.libs.companion.EventStreamClient.stream] independently
 * from [dev.kuch.termx.notification.EventNotificationRouter]. This is the
 * pragmatic choice noted in the Phase 5 brief: each collector of
 * `client.stream()` gets its own tail, which is cheap in CPU/memory and
 * costs one extra ssh exec per server but zero coordination complexity
 * between the notification router and the in-app dialog.
 *
 * Behaviour:
 *  - On [TermxEvent.PermissionRequested]: prepend a [PendingPermission]
 *    keyed by `request_id` to [pendingRequests]. If a dupe event ever
 *    lands (e.g., notification-driven reconnection), the existing entry
 *    is preserved.
 *  - On [TermxEvent.PermissionResolved]: remove the entry.
 *  - On [approve] / [deny]: write the decision straight to
 *    `~/.termx/approvals/<id>.res.json` via
 *    [dev.kuch.termx.libs.companion.EventStreamClient.respondToApproval] —
 *    the file `termx _hook-pretooluse` polls — and optimistically remove
 *    the pending entry; if the write fails, the hook default-denies after
 *    its 30 s timeout and the resulting `PermissionResolved` event just
 *    no-ops here.
 *  - On [alwaysApprove]: write the same `allow` decision (the Go hook only
 *    understands allow/deny and persists nothing itself), then best-effort
 *    append a rule to `~/.termx/allowlist.txt` via
 *    [dev.kuch.termx.libs.companion.EventStreamClient.appendAllowlistRule]
 *    so subsequent invocations bypass the broker. MVP pattern = exact tool
 *    name; power users can hand-edit `~/.termx/allowlist.txt` for richer
 *    rules.
 */
@HiltViewModel
class PermissionBrokerViewModel @Inject constructor(
    private val hub: EventStreamHub,
) : ViewModel() {

    private val _pendingRequests = MutableStateFlow<List<PendingPermission>>(emptyList())
    val pendingRequests: StateFlow<List<PendingPermission>> = _pendingRequests.asStateFlow()

    private val watcher: Job
    private val perServerJobs = mutableMapOf<UUID, Job>()

    init {
        watcher = viewModelScope.launch(SupervisorJob()) {
            hub.clients.collect { snapshot ->
                val goneIds = perServerJobs.keys - snapshot.keys
                goneIds.forEach { id ->
                    perServerJobs.remove(id)?.cancel()
                    // Drop any pending requests bound to a server that went away —
                    // the phone has no way to resolve them any more.
                    _pendingRequests.update { list ->
                        list.filterNot { it.serverId == id }
                    }
                }
                snapshot.forEach { (id, entry) ->
                    if (perServerJobs.containsKey(id)) return@forEach
                    perServerJobs[id] = viewModelScope.launch {
                        runCatching {
                            entry.client.stream().collect { event ->
                                handle(entry.serverId, entry.serverLabel, event)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * User tapped Approve. Optimistically remove the entry so the dialog
     * dismisses even before the response file lands — the server-side
     * `permission_resolved` event will confirm it (or, if the SFTP write
     * fails, the hook's 30 s timeout deny resolves it the hard way).
     */
    fun approve(approvalId: String) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        respondTo(entry.serverId, approvalId, ApprovalResponse.Decision.ALLOW)
    }

    /**
     * User tapped Deny. Optional [reason] is surfaced to Claude's stderr
     * via the hook so the model can react.
     */
    fun deny(approvalId: String, reason: String? = null) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        respondTo(entry.serverId, approvalId, ApprovalResponse.Decision.DENY, reason)
    }

    /**
     * Approve this request AND add a rule to the server's allowlist so
     * subsequent invocations of the tool bypass the broker entirely.
     *
     * Two writes, deliberately ordered and independently fallible:
     *  1. The decision (`allow` — the Go hook accepts only approve/allow
     *     and treats anything else, including "always", as deny) — this is
     *     the critical half that unblocks Claude right now.
     *  2. Best-effort allowlist persistence. termxd persists nothing when
     *     it reads a response, so the phone-side append IS the "always"
     *     semantics; if it fails the user just gets asked again next time.
     *
     * Pattern shape: `^<toolName>\|.*$` (toolName regex-escaped) — matches
     * the `<tool_name>|<cmd>` candidate termxd builds inside its allowlist
     * matcher. Power users can refine by hand-editing
     * `~/.termx/allowlist.txt`.
     */
    fun alwaysApprove(approvalId: String) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        val client = hub.clients.value[entry.serverId]?.client ?: return
        viewModelScope.launch {
            runCatching {
                client.respondToApproval(approvalId, ApprovalResponse.Decision.ALLOW)
            }
            // Swallowed on failure by design — see KDoc above.
            runCatching {
                client.appendAllowlistRule(allowlistPatternFor(entry.toolName))
            }
        }
    }

    private fun removeLocally(approvalId: String) {
        _pendingRequests.update { list ->
            list.filterNot { it.approvalId == approvalId }
        }
    }

    private fun respondTo(
        serverId: UUID,
        requestId: String,
        decision: ApprovalResponse.Decision,
        reason: String? = null,
    ) {
        val client = hub.clients.value[serverId]?.client ?: return
        viewModelScope.launch {
            runCatching { client.respondToApproval(requestId, decision, reason) }
        }
    }

    private fun handle(serverId: UUID, serverLabel: String, event: TermxEvent) {
        when (event) {
            is TermxEvent.PermissionRequested -> {
                val pending = PendingPermission(
                    serverId = serverId,
                    serverLabel = serverLabel,
                    sessionName = event.session,
                    approvalId = event.requestId,
                    toolName = event.toolName,
                    toolArgs = event.toolArgs,
                )
                _pendingRequests.update { list ->
                    if (list.any { it.approvalId == pending.approvalId }) list
                    else list + pending
                }
            }
            is TermxEvent.PermissionResolved -> removeLocally(event.requestId)
            else -> Unit
        }
    }

    override fun onCleared() {
        watcher.cancel()
        perServerJobs.values.forEach { it.cancel() }
        perServerJobs.clear()
        super.onCleared()
    }

    private companion object {
        fun allowlistPatternFor(toolName: String): String {
            // Escape regex metacharacters in the tool name so an exotic
            // name like "Notebook.Edit" doesn't silently match arbitrary
            // characters. Most real Claude tools are [A-Za-z]+ so the
            // escape is defensive.
            val safe = Regex.escape(toolName)
            return "^$safe\\|.*$"
        }
    }
}
