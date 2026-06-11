package dev.kuch.termx.feature.terminal

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.view.TerminalView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * View-level focus contract canary for the 2026-06-11 tap-before-type
 * fix.
 *
 * The fix made the TerminalView the SINGLE focus claimant in the
 * terminal screen: TerminalPane's AndroidView factory sets
 * `isFocusable` / `isFocusableInTouchMode` and calls `requestFocus()`,
 * and every later claim (the Connected LaunchedEffect, tap-to-focus,
 * restoreTerminalFocus after card/dialog dismissal) is a bare
 * `requestFocus()` on that same view. All of that is only sound if the
 * vendored view honors the platform focus contract: with those two
 * flags set, `requestFocus()` on a window-attached TerminalView must
 * actually TAKE focus — including stealing it back from a sibling that
 * currently holds it (the production analogue: the PTT card's text
 * field or an AlertDialog releasing focus to the terminal).
 *
 * This is deliberately NOT a Compose test — the repo has no
 * compose-ui-test infrastructure, and Robolectric can't observe
 * Compose's FocusManager. What it CAN pin cheaply is the layer below:
 * a vendored TerminalView upgrade that started overriding
 * requestFocus/onFocusChanged, force-clearing focusability, or
 * consuming the focus claim would fail here long before a device test.
 * The Compose-side guarantee ("composition never steals focus back")
 * stays with checklist item 11 in docs/PRE_RELEASE_CHECKLIST.md.
 *
 * Robolectric windows run in non-touch mode, so `isFocusableInTouchMode`
 * is set exactly as the factory sets it but only `isFocusable` is
 * load-bearing for the assertion — the touch-mode half of the contract
 * is device-only territory (same checklist item).
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewFocusContractTest {

    @Test
    fun factoryFlags_letRequestFocusTakeFocus_evenFromAFocusedSibling() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        // A competing focus claimant, stand-in for the PTT card / dialogs.
        val sibling = View(activity).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Exactly the flags TerminalPane's AndroidView factory sets — the
        // vendored view does NOT set them itself, so they are load-bearing.
        val terminal = TerminalView(activity, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val container = LinearLayout(activity).apply {
            addView(sibling, LinearLayout.LayoutParams(10, 10))
            addView(terminal, LinearLayout.LayoutParams(100, 100))
        }
        activity.setContentView(container)

        assertTrue("sibling must be able to take focus first", sibling.requestFocus())
        assertTrue(sibling.isFocused)

        // The production focus claim (factory / Connected effect /
        // restoreTerminalFocus is each just this call).
        assertTrue("TerminalView.requestFocus() must take focus", terminal.requestFocus())
        assertTrue("TerminalView must end up focused", terminal.isFocused)
        assertFalse("focus is exclusive — the sibling must have lost it", sibling.isFocused)
        assertSame(
            "the window's focus search must resolve to the TerminalView",
            terminal,
            activity.window.decorView.findFocus(),
        )
    }
}
