package dev.kuch.termx.core.data.prefs

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, per-server SSH password cache.
 *
 * The vault (Task #20) doesn't yet persist server passwords — adding that
 * surface needs a migration plus a biometric-gated unlock flow and we
 * haven't shipped either. Until then we need SOMEWHERE to hold a password
 * the user typed so follow-up SSH channels (tmux poll, reconnect after
 * background) don't make them re-enter it.
 *
 * Values live in-memory for the process lifetime and are **never** written
 * to disk. A process kill or app reinstall clears the cache; that's the
 * correct property for a plain `String` password. The wizard seeds entries
 * during Setup Step 3; the terminal prompt dialog seeds them on first
 * connect.
 */
@Singleton
class PasswordCache @Inject constructor() {
    private val cache = ConcurrentHashMap<UUID, String>()

    fun put(serverId: UUID, password: String) {
        if (password.isBlank()) {
            cache.remove(serverId)
        } else {
            cache[serverId] = password
        }
    }

    fun get(serverId: UUID): String? = cache[serverId]

    fun clear(serverId: UUID) {
        cache.remove(serverId)
    }

    fun clearAll() {
        cache.clear()
    }
}
