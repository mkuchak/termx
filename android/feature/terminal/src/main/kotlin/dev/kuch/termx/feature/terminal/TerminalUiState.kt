package dev.kuch.termx.feature.terminal

import com.termux.terminal.TerminalSession
import java.util.UUID

/**
 * View-facing state for `TerminalScreen`.
 *
 * Each connection is backed by a single plain login shell, so this is a
 * flat data class rather than a sealed hierarchy: [status] carries the
 * lifecycle that an `Idle`/`Connecting`/`Connected`/`Error`/`Disconnected`
 * sealed interface used to.
 *
 *  - [status] drives the whole-screen chrome (connecting spinner,
 *    disconnected banner, error pane).
 *  - [activeSession] is the Termux [TerminalSession] currently bound
 *    to the on-screen [com.termux.view.TerminalView]. Null when we
 *    haven't opened the PTY yet (pre-connect). The concrete subclass is
 *    either [com.termux.terminal.RemoteTerminalSession] (sshj PTY) or
 *    [com.termux.terminal.MoshRemoteTerminalSession] (local
 *    mosh-client process) depending on which transport won the
 *    Phase 3 handshake race.
 *  - [moshBacked] is `true` when the active connection is a
 *    mosh-client child process (Phase 3 Task #27). Drives the small
 *    "via mosh" badge / subtitle in the terminal header.
 *  - [error] is the connection-level failure message; reset on the
 *    next `connect()` attempt.
 */
data class TerminalUiState(
    val status: Status = Status.Idle,
    val activeSession: TerminalSession? = null,
    val moshBacked: Boolean = false,
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
