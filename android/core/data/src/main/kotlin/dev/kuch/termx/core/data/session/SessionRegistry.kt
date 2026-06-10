package dev.kuch.termx.core.data.session

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of live terminal tabs.
 *
 * The `ConnectionManager` (`:feature:terminal`) is the producer — on
 * shell open it calls [register], on teardown it calls [unregister].
 * Entries persist with the manager-owned connection, NOT with any
 * screen: leaving the terminal keeps the entry (and therefore the
 * foreground service) alive. The foreground service
 * (`TermxForegroundService` in `:app`) is the consumer — it observes
 * [entries] to decide when to start, when to stop, and what to render in
 * its persistent notification.
 *
 * Pure data — no Android types — so this stays in `:core:data` and is
 * reachable from both `:app` and `:feature:terminal` without bouncing
 * through Hilt entry points.
 *
 * Process-death resurrection is out of scope — connections die with the
 * process (documented boundary on `ConnectionManager`).
 */
@Singleton
class SessionRegistry @Inject constructor() {

    /**
     * One active tab. Keyed by `(serverId, tabName)` in [entries] so a
     * single server can host multiple tabs without collision.
     */
    data class Entry(
        val serverId: UUID,
        val serverLabel: String,
        val tabName: String,
    )

    private val _entries = MutableStateFlow<Map<Pair<UUID, String>, Entry>>(emptyMap())
    val entries: StateFlow<Map<Pair<UUID, String>, Entry>> = _entries.asStateFlow()

    /**
     * Broadcast signal consumed by the process-wide `ConnectionManager`
     * (collected in its `init`, NOT in any ViewModel — it must work
     * with zero terminal screens alive). The notification's
     * "Disconnect all" action posts into this flow; the manager tears
     * down every connection in response.
     *
     * `extraBufferCapacity = 1` with `tryEmit` makes the signal
     * fire-and-forget — we don't need to suspend the caller, and a
     * dropped emit just means the manager already saw a more recent one.
     */
    private val _disconnectAllRequest = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val disconnectAllRequest: SharedFlow<Unit> = _disconnectAllRequest.asSharedFlow()

    fun register(serverId: UUID, serverLabel: String, tabName: String) {
        _entries.update {
            it + (Pair(serverId, tabName) to Entry(serverId, serverLabel, tabName))
        }
    }

    fun unregister(serverId: UUID, tabName: String) {
        _entries.update { it - Pair(serverId, tabName) }
    }

    fun requestDisconnectAll() {
        _disconnectAllRequest.tryEmit(Unit)
    }
}
