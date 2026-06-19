package dev.kuch.termx.feature.terminal.connection

import com.termux.terminal.TerminalSession
import io.mockk.mockk
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [activeSessionCardModels] — the
 * `ConnectionManager.connections` → "ACTIVE SESSIONS" card mapping
 * behind the home screen's rail (Task #46).
 *
 * Kept deliberately isolated: plain JUnit, no coroutines machinery, no
 * manager instance — slots are built directly via [TermxConnection]'s
 * internal constructor with their state set by hand.
 */
class ActiveSessionsMappingTest {

    private fun slot(
        label: String,
        state: TransportState,
        id: UUID = UUID.randomUUID(),
    ): TermxConnection = TermxConnection(id).apply {
        serverLabel = label
        mutableState.value = state
    }

    private fun connected(moshBacked: Boolean): TransportState.Connected =
        TransportState.Connected(
            session = mockk<TerminalSession>(),
            moshBacked = moshBacked,
            transportFallbackReason = null,
        )

    @Test
    fun `empty slot collection maps to no cards`() {
        assertTrue(activeSessionCardModels(emptyList()).isEmpty())
    }

    @Test
    fun `only Connected slots produce cards`() {
        val live = slot("live", connected(moshBacked = false))
        val slots = listOf(
            slot("connecting", TransportState.Connecting),
            slot("prompting", TransportState.AwaitingPassword(UUID.randomUUID(), "prompting")),
            live,
            slot("failed", TransportState.Error("boom")),
            slot("gone", TransportState.Disconnected),
        )

        val cards = activeSessionCardModels(slots)

        assertEquals(listOf(live.serverId), cards.map { it.serverId })
    }

    @Test
    fun `card snapshots id label live transport and session`() {
        val id = UUID.randomUUID()
        val state = connected(moshBacked = true)
        val cards = activeSessionCardModels(listOf(slot("prod-vps", state, id)))

        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(id, card.serverId)
        assertEquals("prod-vps", card.label)
        assertTrue(card.moshBacked)
        assertEquals(state.session, card.session)
    }

    @Test
    fun `moshBacked reflects the live transport not a preference`() {
        // A server whose row prefers mosh but whose connect fell back to
        // SSH carries moshBacked=false in Connected — the card must show
        // the truthful transport.
        val fallback = TransportState.Connected(
            session = mockk<TerminalSession>(),
            moshBacked = false,
            transportFallbackReason = "no response in time — slow start or blocked UDP",
        )

        val cards = activeSessionCardModels(listOf(slot("mosh-pref", fallback)))

        assertEquals(false, cards.single().moshBacked)
    }

    @Test
    fun `cards preserve slot iteration order`() {
        val first = slot("a", connected(moshBacked = true))
        val second = slot("b", connected(moshBacked = false))
        val third = slot("c", connected(moshBacked = true))

        val cards = activeSessionCardModels(listOf(first, second, third))

        assertEquals(listOf("a", "b", "c"), cards.map { it.label })
    }
}
