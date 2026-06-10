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
 * *because* the session dropped). Keeping the channel separate avoids
 * coupling the "is alive" map to "should we try to open".
 *
 * Consumer: the process-wide `ConnectionManager` collects [requests] in
 * its `init` and redials the matching server — deliberately NOT a
 * ViewModel collector, because the reconnect action must work with no
 * terminal screen alive at all. Fire-and-forget —
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
