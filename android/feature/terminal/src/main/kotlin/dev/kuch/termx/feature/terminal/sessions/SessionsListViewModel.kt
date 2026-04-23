package dev.kuch.termx.feature.terminal.sessions

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.data.remote.TmuxError
import dev.kuch.termx.core.data.remote.TmuxSessionRepositoryImpl
import dev.kuch.termx.core.domain.model.TmuxSession
import dev.kuch.termx.core.domain.repository.TmuxSessionRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Thin ViewModel that exposes the live tmux session list for a server.
 *
 * Task #25 only wires the data path and proves the Flow fires; Task #26
 * will build the multi-session tab bar UI on top of [sessionsFor].
 *
 * Foreground-hinted polling: we feed [AppForegroundTracker.isForeground]
 * into the repository so the cadence automatically slows to 5 min when
 * the user locks the phone and snaps back to 30 s when they bring
 * termx forward again.
 *
 * The VM also exposes [errors] — a SharedFlow that pushes side-channel
 * failures (notably `tmux` not being installed on the server) so the
 * UI can show a banner without the session list itself terminating.
 */
@HiltViewModel
class SessionsListViewModel @Inject constructor(
    private val repo: TmuxSessionRepository,
    private val foregroundTracker: AppForegroundTracker,
) : ViewModel() {

    fun sessionsFor(serverId: UUID): Flow<List<TmuxSession>> =
        repo.observeSessions(serverId, foregroundTracker.isForeground)

    suspend fun refresh(serverId: UUID): List<TmuxSession> = repo.refresh(serverId)

    val errors: Flow<TmuxError> =
        (repo as? TmuxSessionRepositoryImpl)?.errors ?: emptyFlow()
}
