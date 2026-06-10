package dev.kuch.termx.feature.terminal.connection

import com.termux.terminal.TerminalSession
import java.util.UUID

/**
 * Render model for one card in the home screen's "ACTIVE SESSIONS" rail
 * (Task #46). One instance per [TermxConnection] whose transport is
 * currently [TransportState.Connected]; everything the card composable
 * needs is snapshotted here so the composable never touches the
 * connection slot directly.
 *
 * [moshBacked] is the LIVE transport truth from
 * [TransportState.Connected.moshBacked] — NOT the `Server.useMosh`
 * preference the saved-connection cards show. A mosh-preferring server
 * that fell back to plain SSH therefore shows "ssh" here while its
 * saved card still says "mosh"; that mismatch is the feature.
 *
 * [session] is the emulator-bearing [TerminalSession] the thumbnail
 * renderer polls via `session.emulator` (null until the emulator is
 * initialized — the thumbnail flow skips those ticks).
 */
data class ActiveSessionCardModel(
    val serverId: UUID,
    val label: String,
    val moshBacked: Boolean,
    val session: TerminalSession,
)

/**
 * Map the manager's connection slots to the list of active-session
 * cards, preserving the iteration order of [slots] (slot-creation order,
 * i.e. first-connected first — [ConnectionManager.connections] is built
 * by map accretion, which keeps insertion order).
 *
 * Only [TransportState.Connected] slots produce a card: Connecting /
 * AwaitingPassword / Error / Disconnected slots are filtered out, so the
 * rail (which hides itself on an empty list) only ever shows sessions
 * with a live emulator to preview. Slots persist after disconnect by
 * design (stable flow references), which is exactly why this filters on
 * state rather than map membership.
 *
 * Pure snapshot function: reads `state.value` / `serverLabel` at call
 * time. The caller (ActiveSessionsViewModel) is responsible for
 * re-invoking it whenever any slot's state flow emits.
 */
fun activeSessionCardModels(
    slots: Collection<TermxConnection>,
): List<ActiveSessionCardModel> = slots.mapNotNull { conn ->
    val connected = conn.state.value as? TransportState.Connected
        ?: return@mapNotNull null
    ActiveSessionCardModel(
        serverId = conn.serverId,
        label = conn.serverLabel,
        moshBacked = connected.moshBacked,
        session = connected.session,
    )
}
