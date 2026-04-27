package dev.kuch.termx

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.data.vault.VaultLifecycleObserver
import dev.kuch.termx.feature.updater.UpdaterRepository
import dev.kuch.termx.notification.NotificationChannels
import dev.kuch.termx.service.SessionServiceLauncher
import javax.inject.Inject

/**
 * Hilt-aware [Application] root.
 *
 * Registers two process-wide lifecycle observers against
 * [ProcessLifecycleOwner]:
 *  - [VaultLifecycleObserver] drives the vault auto-lock timer (Task #20).
 *  - [AppForegroundTracker] exposes a StateFlow that the tmux session
 *    poller reads to switch its polling cadence (Task #25).
 *
 * Also primes [SessionServiceLauncher] so the foreground service spins
 * up the first time a ViewModel registers an active tab (Task #43).
 */
@HiltAndroidApp
class TermxApplication : Application() {

    @Inject lateinit var vaultLifecycleObserver: VaultLifecycleObserver
    @Inject lateinit var appForegroundTracker: AppForegroundTracker
    @Inject lateinit var sessionServiceLauncher: SessionServiceLauncher
    @Inject lateinit var notificationChannels: NotificationChannels
    @Inject lateinit var updaterRepository: UpdaterRepository

    override fun onCreate() {
        super.onCreate()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(vaultLifecycleObserver)
        lifecycle.addObserver(appForegroundTracker)
        // Channels must exist before anyone posts into them — Task #43's
        // service also creates its `termx.service` channel lazily on
        // first build, but the event router assumes these four are
        // already registered.
        notificationChannels.ensureAll()
        sessionServiceLauncher.start()
        // In-app updater (v1.1.17): cold-start GitHub-Release check
        // gated by 24h DataStore cache + F-Droid suppression. The
        // server-list banner observes the resulting state.
        updaterRepository.checkOnLaunch(installedVersion = BuildConfig.VERSION_NAME)
    }
}
