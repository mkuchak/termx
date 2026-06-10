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
 * Task #46 (Moshi-style home) deleted the reorder / move-to-group /
 * collapse UI surface and the VM methods behind it. The Room group
 * model and the repository-layer reorder API
 * ([ServerRepository.reorder]) are intentionally untouched — only this
 * screen's affordances went away; `sortOrder` still drives the
 * within-group ordering of [buildGrouped].
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
        // No-op for Phase 2. Task #26/#27 will wire a real ping refresh.
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
        // created until they move a server into it (via the edit sheet).
        groups.sortedBy { it.sortOrder }.forEach { g ->
            val rows = groupedById[g.id].orEmpty()
            out += GroupedServers(
                group = g,
                servers = rows,
            )
        }

        // Ungrouped bucket last — only rendered if there are ungrouped
        // rows. A fully-grouped install simply omits the header.
        val ungrouped = groupedById[null].orEmpty()
        if (ungrouped.isNotEmpty()) {
            out += GroupedServers(
                group = null,
                servers = ungrouped,
            )
        }

        return out
    }

    internal companion object {
        const val UNDO_WINDOW_MILLIS = 5_000L
    }
}
