package dev.kuch.termx.feature.terminal.permission

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.session.EventStreamHub
import dev.kuch.termx.libs.companion.events.CompanionCommand
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
 *  - On [approve] / [deny]: send a [CompanionCommand] via the server's
 *    [dev.kuch.termx.libs.companion.EventStreamClient] and optimistically
 *    remove the pending entry; if the send fails, the `PermissionResolved`
 *    event that eventually lands from termxd will just no-op.
 *  - On [alwaysApprove]: additionally emits a [CompanionCommand.UpdateAllowlist]
 *    so the server-side allowlist picks up the pattern for subsequent
 *    invocations. MVP pattern = exact tool name; power users can hand-edit
 *    `~/.termx/allowlist.txt` for richer rules.
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
     * dismisses even before the command lands — the server-side
     * `permission_resolved` event will confirm it.
     */
    fun approve(approvalId: String) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        val cmd = CompanionCommand.ApprovePermission(
            id = UUID.randomUUID().toString(),
            requestId = approvalId,
            remember = false,
        )
        sendTo(entry.serverId, cmd)
    }

    /**
     * User tapped Deny. Optional [reason] is surfaced to Claude's stderr
     * via the hook so the model can react.
     */
    fun deny(approvalId: String, reason: String? = null) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        val cmd = CompanionCommand.DenyPermission(
            id = UUID.randomUUID().toString(),
            requestId = approvalId,
            reason = reason,
        )
        sendTo(entry.serverId, cmd)
    }

    /**
     * Approve this request AND add a rule to the server's allowlist so
     * subsequent invocations of [toolName] bypass the broker entirely.
     *
     * Pattern shape: `^<toolName>\|.*$` — matches the `<tool_name>|<cmd>`
     * candidate termxd builds inside its allowlist matcher. Power users
     * can refine by hand-editing `~/.termx/allowlist.txt`.
     */
    fun alwaysApprove(approvalId: String) {
        val entry = _pendingRequests.value.firstOrNull { it.approvalId == approvalId } ?: return
        removeLocally(approvalId)
        val approve = CompanionCommand.ApprovePermission(
            id = UUID.randomUUID().toString(),
            requestId = approvalId,
            remember = true,
        )
        sendTo(entry.serverId, approve)
        val update = CompanionCommand.UpdateAllowlist(
            id = UUID.randomUUID().toString(),
            pattern = allowlistPatternFor(entry.toolName),
        )
        sendTo(entry.serverId, update)
    }

    private fun removeLocally(approvalId: String) {
        _pendingRequests.update { list ->
            list.filterNot { it.approvalId == approvalId }
        }
    }

    private fun sendTo(serverId: UUID, cmd: CompanionCommand) {
        val client = hub.clients.value[serverId]?.client ?: return
        viewModelScope.launch {
            runCatching { client.sendCommand(cmd) }
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
