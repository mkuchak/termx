package dev.kuch.termx

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldAutoMinimizeSheet] — the Task #47 auto-minimize-on-nav
 * rule wired into TermxNavHost's `OnDestinationChangedListener`.
 *
 * Contract: the terminal sheet survives ONLY over the home server list.
 * Every other destination pulls it down so settings/keys/diff/unlock
 * are never buried under a maximized terminal. The home exemption is
 * ALSO what keeps a cold-start notification maximize alive: the start
 * destination (`servers`) fires the listener once composition lands,
 * after MainActivity already maximized the session.
 */
class TerminalSheetAutoMinimizeTest {

    @Test
    fun `home keeps the sheet`() {
        assertFalse(shouldAutoMinimizeSheet("servers"))
    }

    @Test
    fun `unlock minimizes - the vault lock decision`() {
        assertTrue(shouldAutoMinimizeSheet("unlock"))
    }

    @Test
    fun `every non-home destination minimizes`() {
        listOf(
            "onboarding",
            "setup-wizard",
            "keys",
            "keys/generate",
            "keys/import",
            "keys/{id}",
            "settings",
            "diff/{diffId}/{serverId}",
        ).forEach { route ->
            assertTrue(
                "route '$route' must auto-minimize the sheet",
                shouldAutoMinimizeSheet(route),
            )
        }
    }

    @Test
    fun `null route (no destination yet) minimizes defensively`() {
        assertTrue(shouldAutoMinimizeSheet(null))
    }
}
