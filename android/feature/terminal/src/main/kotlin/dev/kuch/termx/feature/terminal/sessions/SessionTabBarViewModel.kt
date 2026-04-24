/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.domain.model.TmuxSession
import dev.kuch.termx.core.domain.repository.ExecResult
import dev.kuch.termx.core.domain.repository.TmuxSessionRepository
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Drives the [SessionTabBar].
 *
 * Two responsibilities:
 *
 *  1. Expose the live tmux session list for a server via
 *     [sessions] — identical to [SessionsListViewModel.sessionsFor] but
 *     with an `onEach` side-channel that detects per-session activity
 *     bumps (from `tmux`'s `session_activity` field) and flashes them
 *     on the tab for 2 s so the user sees which background tab moved.
 *  2. Expose the tmux write-side verbs the tab bar needs: create,
 *     rename, kill. These all funnel through
 *     [TmuxSessionRepository.exec] so the poll transport is reused.
 *
 * Activity-flash mechanism: each poll tick gives us a
 * `name → Instant` map. Names whose activity strictly advanced since
 * the previous tick enter [activityFlashes] for 2 s — long enough for
 * one render-frame cycle of the pulsing dot in [SessionTab] to be
 * perceptible but short enough not to lag behind the next 30 s poll.
 */
@HiltViewModel
class SessionTabBarViewModel @Inject constructor(
    private val repo: TmuxSessionRepository,
    private val foregroundTracker: AppForegroundTracker,
) : ViewModel() {

    private val _activityFlashes = MutableStateFlow<Set<String>>(emptySet())
    val activityFlashes: StateFlow<Set<String>> = _activityFlashes.asStateFlow()

    private var lastActivityMap: Map<String, Instant> = emptyMap()

    fun sessions(serverId: UUID): Flow<List<TmuxSession>> =
        repo.observeSessions(serverId, foregroundTracker.isForeground)
            .onEach { detectActivity(it) }

    private fun detectActivity(new: List<TmuxSession>) {
        val newMap = new.associate { it.name to it.activity }
        val flashed = newMap.filter { (name, act) ->
            val prev = lastActivityMap[name]
            prev != null && act > prev
        }.keys
        if (flashed.isNotEmpty()) {
            _activityFlashes.value = _activityFlashes.value + flashed
            viewModelScope.launch {
                delay(FLASH_DURATION_MS)
                _activityFlashes.value = _activityFlashes.value - flashed
            }
        }
        lastActivityMap = newMap
    }

    suspend fun newSession(serverId: UUID, name: String): ExecResult =
        repo.exec(serverId, "tmux new-session -d -s '${escape(name)}'")

    suspend fun renameSession(serverId: UUID, old: String, new: String): ExecResult =
        repo.exec(
            serverId,
            "tmux rename-session -t '${escape(old)}' '${escape(new)}'",
        )

    suspend fun killSession(serverId: UUID, name: String): ExecResult =
        repo.exec(serverId, "tmux kill-session -t '${escape(name)}'")

    /**
     * Force-refresh the session list. Useful after a write action
     * (new / rename / kill) so the tab bar reflects the change without
     * waiting up to 30 s for the next poll.
     */
    suspend fun refresh(serverId: UUID): List<TmuxSession> = repo.refresh(serverId)

    /**
     * Defense-in-depth against single quotes in session names.
     * The tmux session-name validator in `:libs:ssh-native` rejects
     * `'` already, but if any caller bypasses that we don't want a
     * broken shell quote to silently rewrite commands.
     */
    private fun escape(raw: String): String = raw.replace("'", "")

    private companion object {
        const val FLASH_DURATION_MS = 2000L
    }
}
