package dev.kuch.termx.core.domain.repository

import dev.kuch.termx.core.domain.model.TmuxSession
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Live view of the tmux sessions running on a given [dev.kuch.termx.core.domain.model.Server].
 *
 * Phase 3 (Task #25) implements this by polling `tmux ls` over SSH.
 * Phase 4 (Task #34) rewrites the backing transport to tail
 * `~/.termx/sessions/*.json` — but the interface stays stable so the
 * tab-bar UI (Task #26) and anything else upstream doesn't need to change.
 */
interface TmuxSessionRepository {

    /**
     * Stream of tmux sessions for [serverId].
     *
     * [foregroundHint] throttles polling: when `true` the repository
     * polls every 30 s; when `false` it slows to every 5 min. The hint
     * is collected inside the flow so switching foreground/background
     * changes the cadence without re-subscribing.
     *
     * Emissions are de-duplicated by the implementation — consumers can
     * rely on a refresh being driven purely by backend change, not timer
     * ticks.
     */
    fun observeSessions(serverId: UUID, foregroundHint: Flow<Boolean>): Flow<List<TmuxSession>>

    /**
     * One-shot refresh. Useful for pull-to-refresh UX and for the
     * tab bar's explicit-refresh affordance. Returns the latest list
     * or throws if the server is unreachable / auth fails.
     */
    suspend fun refresh(serverId: UUID): List<TmuxSession>
}
