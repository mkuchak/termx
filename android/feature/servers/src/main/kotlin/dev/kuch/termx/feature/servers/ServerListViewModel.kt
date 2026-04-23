package dev.kuch.termx.feature.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [ServerListScreen].
 *
 * Combines the flows from [ServerRepository] and [ServerGroupRepository]
 * into a grouped-and-sorted snapshot the screen can render without any
 * further reshuffling. Deletion keeps the just-deleted [Server] on hand
 * for [UNDO_WINDOW_MILLIS] so the snackbar's "Undo" action can restore
 * it verbatim (same id, same order, same key pair link).
 *
 * Reorder for Task #21 ships as arrow-button "reorder mode" — real
 * drag-to-reorder is deferred to the follow-up task referenced in the
 * task body. The `onReorder` API is still here so either implementation
 * plugs in without churning the ViewModel surface.
 */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val serverGroupRepository: ServerGroupRepository,
) : ViewModel() {

    val state: StateFlow<ServerListUiState> =
        combine(
            serverRepository.observeAll(),
            serverGroupRepository.observeAll(),
        ) { servers, groups ->
            if (servers.isEmpty() && groups.isEmpty()) {
                ServerListUiState.Empty
            } else {
                ServerListUiState.Loaded(buildGrouped(servers, groups))
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServerListUiState.Loading,
        )

    private val _pendingUndo = MutableStateFlow<Server?>(null)

    /**
     * The server just swiped off the list, held for the 5 s undo window.
     * Non-null = snackbar visible. Collectors on this flow drive the
     * snackbar host without ceremony.
     */
    val pendingUndo: StateFlow<Server?> = _pendingUndo.asStateFlow()

    private var undoJob: Job? = null

    /**
     * Soft-delete: remove the row and stash a copy for 5 s so the user
     * can tap "Undo". If the user swipes a second row while a first undo
     * is still pending we simply overwrite — the earlier deletion is
     * already committed to Room, which is the correct semantics.
     */
    fun onDelete(id: UUID) {
        viewModelScope.launch {
            val server = serverRepository.getById(id) ?: return@launch
            serverRepository.delete(id)
            _pendingUndo.value = server
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MILLIS)
                if (_pendingUndo.value?.id == server.id) {
                    _pendingUndo.value = null
                }
            }
        }
    }

    /** Restore a server that's currently in the undo window. */
    fun onUndoDelete(server: Server) {
        undoJob?.cancel()
        undoJob = null
        _pendingUndo.value = null
        viewModelScope.launch {
            serverRepository.upsert(server)
        }
    }

    /** Mark the undo snackbar as dismissed without undoing the delete. */
    fun onUndoDismissed() {
        _pendingUndo.value = null
    }

    /**
     * Rewrite the `sortOrder` of every server in [reordered] so position
     * N gets sortOrder N. [groupId] is informational — the reorder map
     * is built by the caller against the visible list for that group.
     */
    fun onReorder(@Suppress("UNUSED_PARAMETER") groupId: UUID?, reordered: List<UUID>) {
        if (reordered.isEmpty()) return
        val map = reordered.mapIndexed { index, id -> id to index }.toMap()
        viewModelScope.launch {
            serverRepository.reorder(map)
        }
    }

    /** Move server [serverId] up by one slot within its current group. */
    fun onMoveUp(serverId: UUID) = shiftWithinGroup(serverId, delta = -1)

    /** Move server [serverId] down by one slot within its current group. */
    fun onMoveDown(serverId: UUID) = shiftWithinGroup(serverId, delta = 1)

    private fun shiftWithinGroup(serverId: UUID, delta: Int) {
        val loaded = (state.value as? ServerListUiState.Loaded) ?: return
        val bucket = loaded.groupsWithServers.firstOrNull { g ->
            g.servers.any { it.id == serverId }
        } ?: return
        val ids = bucket.servers.map { it.id }.toMutableList()
        val i = ids.indexOf(serverId)
        val j = i + delta
        if (j !in ids.indices) return
        val tmp = ids[i]
        ids[i] = ids[j]
        ids[j] = tmp
        onReorder(bucket.group?.id, ids)
    }

    /** Flip the collapsed state of a server group. No-op for "Ungrouped". */
    fun onToggleGroupCollapse(groupId: UUID) {
        viewModelScope.launch {
            val loaded = (state.value as? ServerListUiState.Loaded) ?: return@launch
            val bucket = loaded.groupsWithServers.firstOrNull { it.group?.id == groupId }
            val current = bucket?.group ?: return@launch
            serverGroupRepository.upsert(current.copy(isCollapsed = !current.isCollapsed))
        }
    }

    /** Move [serverId] into group [newGroupId] (null = ungrouped). */
    fun onMoveToGroup(serverId: UUID, newGroupId: UUID?) {
        viewModelScope.launch {
            val server = serverRepository.getById(serverId) ?: return@launch
            serverRepository.upsert(server.copy(groupId = newGroupId))
        }
    }

    /**
     * Clone a server row: fresh UUID, "(copy)" suffix, same everything else.
     * The copy inherits the same [dev.kuch.termx.core.domain.model.KeyPair]
     * reference so there's no vault re-wiring needed.
     */
    fun onDuplicate(serverId: UUID) {
        viewModelScope.launch {
            val source = serverRepository.getById(serverId) ?: return@launch
            val clone = source.copy(
                id = UUID.randomUUID(),
                label = "${source.label} (copy)",
                lastConnected = null,
                pingMs = null,
                companionInstalled = false,
            )
            serverRepository.upsert(clone)
        }
    }

    /**
     * Phase-3 placeholder. Pull-to-refresh currently just bumps the
     * subscription so Room replays, it doesn't hit the network.
     */
    fun onRefresh() {
        // No-op for Phase 2. Task #26/#27 will wire real ping + tmux refresh.
    }

    private fun buildGrouped(
        servers: List<Server>,
        groups: List<ServerGroup>,
    ): List<GroupedServers> {
        val groupedById: Map<UUID?, List<Server>> = servers
            .sortedBy { it.sortOrder }
            .groupBy { it.groupId }

        val out = mutableListOf<GroupedServers>()

        // Explicit groups first, in their own sortOrder. Empty groups
        // stay rendered so the user can still see the folder they
        // created until they move a server into it.
        groups.sortedBy { it.sortOrder }.forEach { g ->
            val rows = groupedById[g.id].orEmpty()
            out += GroupedServers(
                group = g,
                servers = rows,
                isCollapsed = g.isCollapsed,
            )
        }

        // Ungrouped bucket last — only rendered if there are ungrouped
        // rows. A fully-grouped install simply omits the header.
        val ungrouped = groupedById[null].orEmpty()
        if (ungrouped.isNotEmpty()) {
            out += GroupedServers(
                group = null,
                servers = ungrouped,
                isCollapsed = false,
            )
        }

        return out
    }

    internal companion object {
        const val UNDO_WINDOW_MILLIS = 5_000L
    }
}
