package dev.kuch.termx.core.data.session

import dev.kuch.termx.libs.companion.EventStreamClient
import dev.kuch.termx.libs.sshnative.SshSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide registry of live [SshSession] instances keyed by server id
 * alongside a lazily-built [EventStreamClient] per server.
 *
 * Producer: `TerminalViewModel` publishes a session on successful connect
 * and retracts it on `disconnect()`. Consumer: the notification event
 * router in `:app` observes [clients] and spins up per-server subscription
 * jobs so phone-in-pocket alerts fire even while the UI is off screen.
 *
 * Pragmatic choice: the hub stores the raw [EventStreamClient] rather
 * than a shared event flow. Each consumer calls `stream()` independently
 * so the router and (later) the permission UI can subscribe without
 * having to coordinate sharing semantics — `EventStreamClient.stream()`
 * is already buffer-backed and DROP_OLDEST, so multiple collectors cost
 * multiple `tail` processes but zero correctness risk.
 *
 * Ownership: the [SshSession] still belongs to `TerminalViewModel`; when
 * the VM calls [unpublish] the hub drops its client reference without
 * closing the session. Resurrecting the client on reconnect is cheap
 * (no state besides a lazy `$HOME` cache).
 */
@Singleton
class EventStreamHub @Inject constructor() {

    /**
     * Snapshot of one published session. `serverLabel` lets the router
     * render "Claude needs approval on prod-1" without a DB lookup.
     */
    data class Entry(
        val serverId: UUID,
        val serverLabel: String,
        val client: EventStreamClient,
    )

    private val _clients = MutableStateFlow<Map<UUID, Entry>>(emptyMap())
    val clients: StateFlow<Map<UUID, Entry>> = _clients.asStateFlow()

    /**
     * Register [client] as the event source for [serverId]. Replaces any
     * existing entry — on reconnect the TerminalViewModel calls
     * [unpublish] via `cleanupQuietly()` first, so the caller pattern
     * keeps this idempotent.
     */
    fun publish(serverId: UUID, serverLabel: String, client: EventStreamClient) {
        _clients.update { it + (serverId to Entry(serverId, serverLabel, client)) }
    }

    /** Drop the client entry for [serverId]. No-op if nothing was published. */
    fun unpublish(serverId: UUID) {
        _clients.update { it - serverId }
    }
}
