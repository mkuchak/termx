package dev.kuch.termx

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.kuch.termx.core.data.prefs.AppForegroundTracker
import dev.kuch.termx.core.data.vault.VaultLifecycleObserver
import javax.inject.Inject

/**
 * Hilt-aware [Application] root.
 *
 * Registers two process-wide lifecycle observers against
 * [ProcessLifecycleOwner]:
 *  - [VaultLifecycleObserver] drives the vault auto-lock timer (Task #20).
 *  - [AppForegroundTracker] exposes a StateFlow that the tmux session
 *    poller reads to switch its polling cadence (Task #25).
 */
@HiltAndroidApp
class TermxApplication : Application() {

    @Inject lateinit var vaultLifecycleObserver: VaultLifecycleObserver
    @Inject lateinit var appForegroundTracker: AppForegroundTracker

    override fun onCreate() {
        super.onCreate()
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(vaultLifecycleObserver)
        lifecycle.addObserver(appForegroundTracker)
    }
}
