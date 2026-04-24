package dev.kuch.termx.feature.terminal

import com.termux.terminal.RemoteTerminalSession
import java.util.UUID

/**
 * View-facing state for `TerminalScreen`.
 *
 * Task #15 shipped a sealed hierarchy (`Idle`, `Connecting`,
 * `Connected(session)`, `Error`, `Disconnected`); Task #26 flattens that
 * into a single data class because the multi-session tab bar needs to
 * render concurrent per-status fields — e.g. one tab active-and-connected
 * while another is still spinning up. The status enum absorbs what the
 * old sealed interface carried.
 *
 *  - [status] drives the whole-screen chrome (connecting spinner,
 *    disconnected banner, error pane).
 *  - [activeSession] is the Termux [RemoteTerminalSession] currently
 *    bound to the on-screen [com.termux.view.TerminalView]. Null when
 *    we haven't opened any PTY yet (pre-connect or mid-swap).
 *  - [activeTabName] is the tmux session name the active PTY attached
 *    to. Drives the tab-bar highlight.
 *  - [openTabs] is the set of tmux session names we currently have an
 *    open [dev.kuch.termx.libs.sshnative.PtyChannel] for. Used to gate
 *    swipe-up-detach and kill-session affordances.
 *  - [tmuxMissing] surfaces the "we wanted tmux but it's not installed"
 *    banner from the auto-attach path (Task #25).
 *  - [tmuxBacked] is `true` once the initial PTY successfully attached
 *    to a tmux session (Task #28 gate for the copy-mode scrollback
 *    gesture). Mutually exclusive with [tmuxMissing] in practice.
 *  - [error] is the connection-level failure message; reset on the
 *    next `connect()` attempt.
 */
data class TerminalUiState(
    val status: Status = Status.Idle,
    val activeSession: RemoteTerminalSession? = null,
    val activeTabName: String? = null,
    val openTabs: Set<String> = emptySet(),
    val tmuxMissing: Boolean = false,
    val tmuxBacked: Boolean = false,
    val error: String? = null,
    /**
     * When non-null, the UI should render a password prompt dialog. Set
     * when `connect()` hits a password-auth server with no cached entry;
     * cleared on submit or cancel.
     */
    val awaitingPassword: AwaitingPasswordInfo? = null,
    /**
     * URL pending a confirmation dialog (Task #17 double-tap). Set by
     * [TerminalViewModel.onUrlDoubleTap]; cleared by
     * [TerminalViewModel.onUrlTapConfirmed] or
     * [TerminalViewModel.onUrlTapDismissed].
     */
    val pendingUrlTap: String? = null,
) {
    enum class Status { Idle, Connecting, Connected, Disconnected }
}

/**
 * Identifies a server waiting for a user-entered password. Carried in
 * [TerminalUiState.awaitingPassword] so the prompt dialog knows which
 * server label to show and which id to pass back to
 * [TerminalViewModel.submitPassword].
 */
data class AwaitingPasswordInfo(
    val serverId: UUID,
    val serverLabel: String,
)
