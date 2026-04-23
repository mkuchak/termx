package dev.kuch.termx

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import dev.kuch.termx.core.data.vault.VaultLifecycleObserver
import javax.inject.Inject

/**
 * Hilt-aware [Application] root.
 *
 * Registers [VaultLifecycleObserver] against [ProcessLifecycleOwner] so
 * the app-wide background-to-foreground transition drives the vault
 * auto-lock timer introduced in Task #20.
 */
@HiltAndroidApp
class TermxApplication : Application() {

    @Inject lateinit var vaultLifecycleObserver: VaultLifecycleObserver

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(vaultLifecycleObserver)
    }
}
