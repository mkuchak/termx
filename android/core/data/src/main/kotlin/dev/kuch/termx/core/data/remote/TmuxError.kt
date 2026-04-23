package dev.kuch.termx.core.data.remote

import java.util.UUID

/**
 * Side-channel error surface for the tmux session poller.
 *
 * [observeSessions] stays on its happy path (empty list) when `tmux` is
 * absent or transient transport errors hit; the specific failure is
 * published via `TmuxSessionRepositoryImpl.errors` so the feature layer
 * can show a banner ("tmux not found on server", "unreachable", etc.)
 * without the Flow itself blowing up and restarting the polling loop.
 */
sealed class TmuxError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    abstract val serverId: UUID

    /** `tmux ls` returned exit code 127 → tmux isn't installed on the server. */
    data class TmuxNotFound(override val serverId: UUID) :
        TmuxError("tmux not found on server")

    /** Anything else: transport dropped, auth flipped, etc. */
    data class TransportFailure(
        override val serverId: UUID,
        override val cause: Throwable,
    ) : TmuxError("tmux poll transport failure: ${cause.message}", cause)
}
