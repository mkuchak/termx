package dev.kuch.termx.feature.terminal

import com.termux.terminal.TerminalSession

/** View-facing state for [TerminalScreen]. */
sealed interface TerminalUiState {
    /** Initial state before `connect()` has been called. */
    data object Idle : TerminalUiState

    /** Connecting to the host / authenticating / opening shell. */
    data object Connecting : TerminalUiState

    /**
     * Shell is open; [session] is bound to the Android `TerminalView`.
     *
     * [tmuxMissing] is a soft banner flag set when the server had
     * `autoAttachTmux = true` but `tmux` was absent on the remote.
     * We fell back to a plain login shell so the screen is still
     * usable — the banner nudges the user to install it via the
     * companion (wired up in Task #33).
     */
    data class Connected(
        val session: TerminalSession,
        val tmuxMissing: Boolean = false,
    ) : TerminalUiState

    /** Connection attempt or live session failed with [message]. */
    data class Error(val message: String) : TerminalUiState

    /** Session closed cleanly (user, remote, or cancellation). */
    data object Disconnected : TerminalUiState
}
