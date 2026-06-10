package dev.kuch.termx.feature.terminal.connection

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Transition coverage for [TerminalSheetState] — the Task #47 singleton
 * behind the terminal sheet overlay. Deliberately a plain-JUnit file
 * (no Robolectric, no coroutines machinery): the holder is a bare
 * StateFlow wrapper and must stay that trivial — anything needing more
 * setup here is a sign transport concerns leaked into view state.
 */
class TerminalSheetStateTest {

    private val state = TerminalSheetState()

    private val serverA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val serverB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

    @Test
    fun `starts minimized`() {
        assertNull(state.maximizedServerId.value)
    }

    @Test
    fun `maximize exposes the server id`() {
        state.maximize(serverA)

        assertEquals(serverA, state.maximizedServerId.value)
    }

    @Test
    fun `maximize while maximized swaps to the new session`() {
        state.maximize(serverA)
        state.maximize(serverB)

        assertEquals(serverB, state.maximizedServerId.value)
    }

    @Test
    fun `minimize clears the maximized session`() {
        state.maximize(serverA)
        state.minimize()

        assertNull(state.maximizedServerId.value)
    }

    @Test
    fun `minimize when already minimized is a no-op`() {
        state.minimize()
        state.minimize()

        assertNull(state.maximizedServerId.value)
    }

    @Test
    fun `re-maximize after minimize works`() {
        state.maximize(serverA)
        state.minimize()
        state.maximize(serverA)

        assertEquals(serverA, state.maximizedServerId.value)
    }

    @Test
    fun `fallback registry sentinel is a valid maximized id`() {
        // The BuildConfig test-server session is keyed by the all-zeros
        // registry sentinel; the sheet must be able to maximize it (the
        // host translates it back to the manager's null-id path).
        state.maximize(ConnectionManager.FALLBACK_SERVER_ID)

        assertEquals(
            ConnectionManager.FALLBACK_SERVER_ID,
            state.maximizedServerId.value,
        )
    }
}
