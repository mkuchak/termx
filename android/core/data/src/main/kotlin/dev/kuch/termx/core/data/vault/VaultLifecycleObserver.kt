package dev.kuch.termx.core.data.vault

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.kuch.termx.core.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide lifecycle observer that auto-locks the [SecretVault] when
 * the app has been in the background longer than
 * [AppPreferences.autoLockMinutes].
 *
 * Hooked up in [dev.kuch.termx.TermxApplication.onCreate] via
 * `ProcessLifecycleOwner.get().lifecycle.addObserver(...)`.
 *
 * The clock is simple wall-time — we record the epoch millis the app
 * entered background in [onStop], then on [onStart] compare elapsed time
 * against the user-configured threshold and call [VaultLockState.lock]
 * if it is exceeded. Zero minutes = lock on every backgrounding.
 */
@Singleton
class VaultLifecycleObserver @Inject constructor(
    private val lockState: VaultLockState,
    private val preferences: AppPreferences,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var backgroundedAtMs: Long = 0L

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAtMs = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val stampedAt = backgroundedAtMs
        if (stampedAt == 0L) return // first foreground launch

        scope.launch {
            val minutes = preferences.autoLockMinutes.first()
            val elapsedMs = System.currentTimeMillis() - stampedAt
            val thresholdMs = minutes.toLong() * 60_000L
            if (elapsedMs >= thresholdMs) {
                lockState.lock()
            }
        }
    }
}
