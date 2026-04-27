package dev.kuch.termx.feature.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AppPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinates the in-app updater: checks GitHub, decides whether to
 * auto-download (Wi-Fi) or prompt (cellular), runs the download,
 * hands off to the system installer.
 *
 * Single source of truth for [UpdaterState] across the app — both
 * the server-list banner and the Settings card observe [state].
 *
 * F-Droid installs short-circuit at startup: F-Droid's own client
 * handles updates, so [checkOnLaunch] becomes a no-op there.
 *
 * The 24h cache key (`updater_last_check_epoch_ms`) keeps cold-start
 * latency at 0 ms after the first daily check; force-bypass via
 * [refreshNow] for the Settings "Check for updates" button.
 */
@Singleton
class UpdaterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val appPreferences: AppPreferences,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdaterState>(UpdaterState.Idle)
    val state: StateFlow<UpdaterState> = _state.asStateFlow()

    private var inFlightCheck: Job? = null
    private var inFlightDownload: Job? = null

    /**
     * Cold-start hook. Called once from [TermxApplication.onCreate].
     * No-op for F-Droid installs and when the 24h cache is fresh.
     */
    fun checkOnLaunch(installedVersion: String) {
        if (UpdateInstallerSource.detect(context) == UpdateInstallerSource.FDroid) {
            Log.i(LOG_TAG, "F-Droid install detected; skipping in-app update check")
            return
        }
        scope.launch {
            val lastCheck = appPreferences.updaterLastCheckEpochMs.first()
            val age = System.currentTimeMillis() - lastCheck
            if (age in 0 until CHECK_TTL_MS) {
                Log.i(LOG_TAG, "skipping update check; last check ${age}ms ago (TTL ${CHECK_TTL_MS}ms)")
                return@launch
            }
            performCheck(installedVersion, autoDownloadIfWifi = true)
        }
    }

    /**
     * "Check for updates" button in Settings. Bypasses the 24h cache
     * and surfaces the result via the same banner — but never
     * auto-downloads (the user came here deliberately and may want to
     * inspect the version before committing data).
     */
    fun refreshNow(installedVersion: String) {
        scope.launch { performCheck(installedVersion, autoDownloadIfWifi = false) }
    }

    private suspend fun performCheck(installedVersion: String, autoDownloadIfWifi: Boolean) {
        if (inFlightCheck?.isActive == true) return
        inFlightCheck = scope.launch {
            _state.value = UpdaterState.Checking
            val result = checker.check(installedVersion)
            // Mark the check as done before deciding what to surface
            // so a downstream Available/Error doesn't trigger another
            // launch hitting the network within the next 24h.
            appPreferences.setUpdaterLastCheckEpochMs(System.currentTimeMillis())

            when (result) {
                is UpdateChecker.Result.UpToDate -> {
                    _state.value = UpdaterState.UpToDate(installedVersion)
                }
                is UpdateChecker.Result.Available -> {
                    val skipped = appPreferences.updaterSkippedVersion.first()
                    if (skipped == result.version) {
                        Log.i(LOG_TAG, "version ${result.version} was previously skipped")
                        _state.value = UpdaterState.Skipped
                        return@launch
                    }
                    val available = UpdaterState.Available(
                        version = result.version,
                        downloadUrl = result.downloadUrl,
                        sizeBytes = result.sizeBytes,
                    )
                    // Reuse a previous successful download if it's
                    // still in cache — saves the user a re-fetch if
                    // they cancelled the install dialog earlier.
                    val cached = downloader.cachedFile(result.version)
                    if (cached != null) {
                        _state.value = UpdaterState.ReadyToInstall(result.version, cached)
                        return@launch
                    }
                    _state.value = available
                    if (autoDownloadIfWifi && isOnWifi()) {
                        startDownload(available)
                    }
                }
                is UpdateChecker.Result.Error -> {
                    _state.value = UpdaterState.Error(result.reason)
                }
            }
        }
    }

    /** User tapped "Download" on the banner. */
    fun startDownload(available: UpdaterState.Available) {
        if (inFlightDownload?.isActive == true) return
        inFlightDownload = scope.launch {
            _state.value = UpdaterState.Downloading(
                version = available.version,
                bytesRead = 0L,
                bytesTotal = available.sizeBytes,
            )
            try {
                downloader.download(available.downloadUrl, available.version)
                    .collect { progress ->
                        // Don't downgrade out of Downloading if the user
                        // already moved past it (e.g. tapped Cancel on
                        // the banner and we're racing the last emission).
                        val current = _state.value
                        if (current is UpdaterState.Downloading) {
                            _state.value = current.copy(
                                bytesRead = progress.bytesRead,
                                bytesTotal = progress.bytesTotal.coerceAtLeast(available.sizeBytes),
                            )
                        }
                    }
                val cached = downloader.cachedFile(available.version)
                    ?: throw IllegalStateException("Download finished but cached file is missing")
                _state.value = UpdaterState.ReadyToInstall(available.version, cached)
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "download failed", t)
                _state.value = UpdaterState.Error("Download failed: ${t.message ?: "unknown"}")
            }
        }
    }

    /** User tapped "Skip" on the banner. */
    fun skip(version: String) {
        scope.launch {
            appPreferences.setUpdaterSkippedVersion(version)
            _state.value = UpdaterState.Skipped
        }
    }

    /** User tapped "Install" once the APK is cached. */
    fun install(): ApkInstaller.Result {
        val ready = _state.value as? UpdaterState.ReadyToInstall ?: return ApkInstaller.Result.Error("Not ready")
        return installer.install(ready.apkFile)
    }

    /** Get the system-settings intent the UI fires when permission is missing. */
    fun grantInstallPermissionIntent() = installer.grantPermissionIntent()

    /** "Dismiss" / Cancel on Error / Available banners. Resets to Idle without persisting a skip. */
    fun dismiss() {
        inFlightDownload?.cancel()
        inFlightDownload = null
        _state.value = UpdaterState.Idle
    }

    private fun isOnWifi(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = cm?.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(false)

    companion object {
        private const val LOG_TAG = "UpdaterRepository"

        /** 24h cache window; matches the user's expectation of "checks daily". */
        const val CHECK_TTL_MS: Long = 24L * 60L * 60L * 1000L
    }
}
