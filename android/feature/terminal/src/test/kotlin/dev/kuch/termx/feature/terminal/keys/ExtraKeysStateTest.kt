package dev.kuch.termx.feature.terminal.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-state tests for [ExtraKeysState]'s sticky-modifier machine.
 *
 * Bug A from v1.1.13's grilling traced back to "the bar's sticky
 * state never reaches IME letters" — the *encoding* of Ctrl+letter
 * is covered by [ExtraKeyBytesTest], but the state machine that
 * decides "is Ctrl active right now?" lives here. If
 * [ExtraKeysState.resetOneShots] ever stops clearing OneShot, every
 * IME letter after the first sticky tap would still be encoded as
 * Ctrl+letter — the user would see runaway control bytes.
 *
 * The Locked promotion (double-tap) lives in the Composable
 * (`ExtraKeysBar.handleTap`), not in this state holder, so we only
 * test the post-promotion behaviour here.
 */
class ExtraKeysStateTest {

    @Test fun `defaults are Off`() {
        val state = ExtraKeysState()
        assertEquals(ModifierState.Off, state.ctrl)
        assertEquals(ModifierState.Off, state.alt)
        assertFalse(state.ctrlActive)
        assertFalse(state.altActive)
    }

    @Test fun `tapCtrlOnce from Off goes OneShot`() {
        val state = ExtraKeysState()
        state.tapCtrlOnce()
        assertEquals(ModifierState.OneShot, state.ctrl)
        assertTrue(state.ctrlActive)
    }

    @Test fun `tapAltOnce from Off goes OneShot`() {
        val state = ExtraKeysState()
        state.tapAltOnce()
        assertEquals(ModifierState.OneShot, state.alt)
        assertTrue(state.altActive)
    }

    @Test fun `tapCtrlOnce from OneShot stays OneShot (debouncer window)`() {
        // The Composable's double-tap detector relies on the second
        // tap NOT bumping us out of OneShot; the debouncer alone
        // decides whether to call lockCtrl().
        val state = ExtraKeysState(initialCtrl = ModifierState.OneShot)
        state.tapCtrlOnce()
        assertEquals(ModifierState.OneShot, state.ctrl)
    }

    @Test fun `tapCtrlOnce from Locked turns it Off`() {
        // Single tap on a Locked chip is the user disabling it.
        val state = ExtraKeysState(initialCtrl = ModifierState.Locked)
        state.tapCtrlOnce()
        assertEquals(ModifierState.Off, state.ctrl)
        assertFalse(state.ctrlActive)
    }

    @Test fun `tapAltOnce from Locked turns it Off`() {
        val state = ExtraKeysState(initialAlt = ModifierState.Locked)
        state.tapAltOnce()
        assertEquals(ModifierState.Off, state.alt)
    }

    @Test fun `lockCtrl promotes to Locked regardless of prior state`() {
        val a = ExtraKeysState()
        a.lockCtrl()
        assertEquals(ModifierState.Locked, a.ctrl)

        val b = ExtraKeysState(initialCtrl = ModifierState.OneShot)
        b.lockCtrl()
        assertEquals(ModifierState.Locked, b.ctrl)
    }

    @Test fun `resetOneShots clears OneShot but preserves Locked`() {
        val state = ExtraKeysState(
            initialCtrl = ModifierState.OneShot,
            initialAlt = ModifierState.Locked,
        )
        state.resetOneShots()
        assertEquals(ModifierState.Off, state.ctrl)
        assertEquals(ModifierState.Locked, state.alt) // unchanged
        assertFalse(state.ctrlActive)
        assertTrue(state.altActive) // still active because Locked
    }

    @Test fun `resetOneShots is a no-op when both are Off`() {
        val state = ExtraKeysState()
        state.resetOneShots()
        assertEquals(ModifierState.Off, state.ctrl)
        assertEquals(ModifierState.Off, state.alt)
    }

    @Test fun `resetOneShots is a no-op when both are Locked`() {
        val state = ExtraKeysState(
            initialCtrl = ModifierState.Locked,
            initialAlt = ModifierState.Locked,
        )
        state.resetOneShots()
        assertEquals(ModifierState.Locked, state.ctrl)
        assertEquals(ModifierState.Locked, state.alt)
    }

    @Test fun `ctrlActive and altActive return true for both OneShot and Locked`() {
        val a = ExtraKeysState(
            initialCtrl = ModifierState.OneShot,
            initialAlt = ModifierState.OneShot,
        )
        assertTrue(a.ctrlActive)
        assertTrue(a.altActive)

        val b = ExtraKeysState(
            initialCtrl = ModifierState.Locked,
            initialAlt = ModifierState.Locked,
        )
        assertTrue(b.ctrlActive)
        assertTrue(b.altActive)
    }

    @Test fun `Ctrl and Alt evolve independently`() {
        // Tapping CTRL must not touch ALT and vice versa.
        val state = ExtraKeysState()
        state.tapCtrlOnce()
        assertEquals(ModifierState.OneShot, state.ctrl)
        assertEquals(ModifierState.Off, state.alt)

        state.tapAltOnce()
        assertEquals(ModifierState.OneShot, state.alt)
        assertEquals(ModifierState.OneShot, state.ctrl) // still OneShot

        state.lockAlt()
        assertEquals(ModifierState.Locked, state.alt)
        assertEquals(ModifierState.OneShot, state.ctrl)
    }

    @Test fun `resetOneShots after typing a letter ONLY clears OneShots, leaving Locked Ctrl alive`() {
        // The IME-commitText path (BugA, v1.1.14) calls
        // resetOneShots() after applying sticky modifiers to one
        // letter. If the user double-tapped CTRL → Locked, every
        // subsequent letter must still see ctrl. Locked should NOT
        // get cleared by the per-letter reset.
        val state = ExtraKeysState(initialCtrl = ModifierState.Locked)
        repeat(5) { state.resetOneShots() } // five "letters typed"
        assertEquals(ModifierState.Locked, state.ctrl)
        assertTrue(state.ctrlActive)
    }
}
