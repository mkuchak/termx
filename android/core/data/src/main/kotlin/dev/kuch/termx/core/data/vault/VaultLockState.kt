package dev.kuch.termx.core.data.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holder for the lock lifecycle of [SecretVault].
 *
 * Written to by the biometric unlock flow ([markUnlocking] / [markUnlocked]),
 * by [VaultLifecycleObserver] on background timeout ([lock]), and by any
 * place that needs a paranoid-mode re-lock after a sensitive operation.
 *
 * Observed by:
 * - [FileSystemSecretVault] to gate store / load / delete calls.
 * - The navigation layer (see `TermxNavHost`) to route to the unlock screen
 *   whenever state flips to [State.Locked].
 */
@Singleton
class VaultLockState @Inject constructor() {

    enum class State { Locked, Unlocking, Unlocked }

    private val _state = MutableStateFlow(State.Locked)
    val state: StateFlow<State> = _state.asStateFlow()

    fun markUnlocking() {
        _state.value = State.Unlocking
    }

    fun markUnlocked() {
        _state.value = State.Unlocked
    }

    /** Public: auto-lock timer, paranoid-mode hooks, explicit "lock now" button. */
    fun lock() {
        _state.value = State.Locked
    }
}
