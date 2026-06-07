package dev.kuch.termx

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.data.vault.VaultLifecycleObserver
import dev.kuch.termx.core.domain.theme.Sorcerer
import dev.kuch.termx.feature.terminal.theme.ThemeBinder
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
 *  - [AppForegroundTracker] exposes a StateFlow of foreground/background
 *    state that observers read to switch their polling cadence.
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
        // Install Sorcerer as the process-wide Termux palette default
        // before any session is created. This is what actually gets
        // the theme into every TerminalEmulator (their TerminalColors
        // constructor copies from the static defaults) AND keeps it
        // there across mid-session reset escapes (RIS, DECSTR, OSC
        // 104, OSC 110/111/112). See ThemeBinder.installAsDefault for
        // the full rationale — this single line replaces the broken
        // AndroidView factory/update apply path that v1.3.0 / v1.3.1
        // shipped, where ThemeBinder.apply silently no-op'd because
        // mEmulator is null at composition time.
        ThemeBinder.installAsDefault(Sorcerer.terminalTheme)
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
