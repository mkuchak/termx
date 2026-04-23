package dev.kuch.termx.feature.terminal

import com.termux.terminal.TerminalSession

/** View-facing state for [TerminalScreen]. */
sealed interface TerminalUiState {
    /** Initial state before `connect()` has been called. */
    data object Idle : TerminalUiState

    /** Connecting to the host / authenticating / opening shell. */
    data object Connecting : TerminalUiState

    /** Shell is open; [session] is bound to the Android `TerminalView`. */
    data class Connected(val session: TerminalSession) : TerminalUiState

    /** Connection attempt or live session failed with [message]. */
    data class Error(val message: String) : TerminalUiState

    /** Session closed cleanly (user, remote, or cancellation). */
    data object Disconnected : TerminalUiState
}
