package dev.kuch.termx.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.session.SessionRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Process-wide bridge that starts [TermxForegroundService] the first
 * time [SessionRegistry] flips from 0 to ≥1 active tabs.
 *
 * Sits in `:app` because the service class isn't visible from
 * `:feature:terminal`. Initialised from [dev.kuch.termx.TermxApplication]
 * so it runs before any ViewModel opens a tab. The service itself owns
 * its own stop condition (it self-terminates when the registry map goes
 * empty), so this class is start-only.
 *
 * `startForegroundService` is idempotent — repeated calls for the same
 * component are coalesced by ActivityManager — so we don't strictly
 * need the 0→1 guard, but it keeps the Logcat cleaner.
 */
@Singleton
class SessionServiceLauncher @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sessionRegistry: SessionRegistry,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            sessionRegistry.entries
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { anyActive ->
                    if (anyActive) {
                        TermxForegroundService.start(appContext)
                    }
                }
        }
    }
}
