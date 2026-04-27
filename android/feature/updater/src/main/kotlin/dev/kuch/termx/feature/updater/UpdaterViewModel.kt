package dev.kuch.termx.feature.updater

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin Hilt-bridge wrapping [UpdaterRepository] so composables can
 * `hiltViewModel()` it directly. The repository itself is the
 * singleton state owner — multiple VMs (banner + Settings card) share
 * the same StateFlow.
 */
@HiltViewModel
class UpdaterViewModel @Inject constructor(
    private val repository: UpdaterRepository,
) : ViewModel() {

    val state: StateFlow<UpdaterState> = repository.state

    fun startDownload(available: UpdaterState.Available) = repository.startDownload(available)

    fun skip(version: String) = repository.skip(version)

    fun install(): ApkInstaller.Result = repository.install()

    fun dismiss() = repository.dismiss()

    fun refreshNow(installedVersion: String) = repository.refreshNow(installedVersion)

    fun grantInstallPermissionIntent() = repository.grantInstallPermissionIntent()
}
