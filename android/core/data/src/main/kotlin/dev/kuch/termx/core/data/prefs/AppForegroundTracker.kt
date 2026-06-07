package dev.kuch.termx.core.data.prefs

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide foreground/background signal exposed as a StateFlow.
 *
 * Registered against `ProcessLifecycleOwner` in
 * [dev.kuch.termx.TermxApplication.onCreate]. Consumers read [isForeground]
 * to throttle background work — polling faster in the foreground and
 * backing off when the app is backgrounded.
 *
 * The StateFlow starts `false`; `ProcessLifecycleOwner.onStart` fires on
 * the first Activity start (which happens immediately after Hilt wires
 * this up), so consumers that collect in a coroutine see the correct
 * value within the first frame.
 */
@Singleton
class AppForegroundTracker @Inject constructor() : DefaultLifecycleObserver {

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
    }
}
