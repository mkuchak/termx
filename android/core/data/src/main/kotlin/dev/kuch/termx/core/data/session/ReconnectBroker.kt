package dev.kuch.termx.core.data.session

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Broadcast channel for "please reconnect server X" requests coming from
 * notification actions.
 *
 * Why a dedicated broker rather than reusing [SessionRegistry]: the
 * registry tracks live tabs (post-connect state); a reconnect request
 * can fire when no tab is live (the `disconnect` notification fired
 * *because* the session dropped). Keeping the channel separate lets the
 * [dev.kuch.termx.feature.terminal.TerminalViewModel] listen without
 * coupling the "is alive" map to "should we try to open".
 *
 * Consumers: the terminal VM collects [requests] in `init` and decides
 * whether the incoming id matches its own server. Fire-and-forget —
 * `extraBufferCapacity = 1` + `tryEmit` so the receiver's
 * BroadcastReceiver callback doesn't have to suspend.
 */
@Singleton
class ReconnectBroker @Inject constructor() {
    private val _requests = MutableSharedFlow<UUID>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val requests: SharedFlow<UUID> = _requests.asSharedFlow()

    fun requestReconnect(serverId: UUID) {
        _requests.tryEmit(serverId)
    }
}
