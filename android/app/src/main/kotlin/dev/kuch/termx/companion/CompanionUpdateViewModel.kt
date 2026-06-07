package dev.kuch.termx.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.remote.CompanionUpdateRepository
import dev.kuch.termx.core.data.remote.CompanionUpdateState
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Thin Hilt bridge over [CompanionUpdateRepository] so the server-list
 * `CompanionUpdateBanner` can `hiltViewModel()` it. The repository is the
 * singleton state owner — this VM just relays its [StateFlow] and forwards
 * the banner's taps. Mirrors `:feature:updater`'s `UpdaterViewModel`.
 *
 * Lives in `:app` (not `:feature:servers`) so the server-list module stays
 * free of any banner dependency — the NavHost injects the banner into a
 * composable slot, exactly as it does for the APK `UpdateBanner`.
 */
@HiltViewModel
class CompanionUpdateViewModel @Inject constructor(
    private val repository: CompanionUpdateRepository,
) : ViewModel() {

    val state: StateFlow<CompanionUpdateState> = repository.state

    /** "Install" tap — runs the existing preview → install-over-SSH pipeline. */
    fun install(serverId: UUID, downloadUrl: String) {
        viewModelScope.launch { repository.install(serverId, downloadUrl) }
    }

    /** "Later" tap — remember the dismissal for this (server, tag) pair. */
    fun skip(serverId: UUID, tag: String) {
        viewModelScope.launch { repository.skip(serverId, tag) }
    }

    /** Dismiss a terminal (Installed / Error) banner back to hidden. */
    fun dismiss() = repository.dismiss()
}
