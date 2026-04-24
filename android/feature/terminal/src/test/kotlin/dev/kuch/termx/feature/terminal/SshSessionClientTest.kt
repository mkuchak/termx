package dev.kuch.termx.feature.terminal

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Regression coverage for the "invalidate never fires for remote bytes"
 * bug. The Termux contract is
 *
 *   TerminalSession.notifyScreenUpdate → client.onTextChanged
 *     → TerminalView.onScreenUpdated → invalidate()
 *
 * Our [SshSessionClient.onTextChanged] used to be a literal no-op,
 * dead-ending the chain and leaving the UI frozen until a full activity
 * cycle forced a layout pass. These tests pin the new behaviour.
 *
 * We observe the chain at the view layer via Robolectric's
 * `wasInvalidated()` shadow on the real [TerminalView]. The view needs a
 * live emulator for `onScreenUpdated()` to reach the `invalidate()` call
 * (early-return guard on line 463 of TerminalView.java), so we wire in a
 * real [RemoteTerminalSession] + call `updateSize` to force emulator
 * construction — same path the production code takes on first layout.
 */
@RunWith(AndroidJUnit4::class)
class SshSessionClientTest {

    @Test
    fun onTextChanged_invalidatesView_whenViewIsBound() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = SshSessionClient(context = context, onSessionFinished = {})
        client.terminalView = view

        // Build a real RemoteTerminalSession + initialise its emulator so
        // TerminalView.onScreenUpdated() doesn't early-return on
        // `mEmulator == null`. Assign mEmulator directly because our
        // view has zero width/height in a headless Robolectric
        // environment, so the normal attachSession → updateSize() path
        // skips the emulator hook-up.
        val session = RemoteTerminalSession(
            client = client,
            onInputBytes = { /* unused in this test */ },
            onResize = { _, _ -> },
        )
        session.updateSize(80, 24, 8, 16)
        view.mEmulator = session.emulator

        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(view)
        // Reset the flag so the assertion below only catches what
        // onTextChanged triggers.
        shadow.clearWasInvalidated()

        client.onTextChanged(session)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "expected onTextChanged to reach view.onScreenUpdated → invalidate()",
            shadow.wasInvalidated(),
        )
    }

    @Test
    fun onTextChanged_isNoOp_whenViewIsNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = SshSessionClient(context = context, onSessionFinished = {})
        val session = StubSession(client)

        // Must not throw; there's nothing else observable to assert.
        client.onTextChanged(session)
        assertEquals(null, client.terminalView)
    }

    @Test
    fun onTextChanged_isNoOp_afterViewUnbound() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = SshSessionClient(context = context, onSessionFinished = {})
        client.terminalView = view
        client.terminalView = null

        val session = StubSession(client)
        shadowOf(Looper.getMainLooper()).idle()
        val shadow = shadowOf(view)
        shadow.clearWasInvalidated()

        client.onTextChanged(session)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "unbound client must not reach the view",
            false,
            shadow.wasInvalidated(),
        )
    }

    /**
     * A hand-rolled [TerminalSession] subclass that side-steps the
     * upstream constructor's JNI subprocess spawn. Upstream's
     * `TerminalSession` constructor just stores its fields; the JNI
     * call only happens later in `initializeEmulator`. Matches the
     * pattern [com.termux.terminal.RemoteTerminalSession] uses in
     * production.
     */
    private class StubSession(client: SshSessionClient) : TerminalSession(
        /* shellPath      = */ "",
        /* cwd            = */ "",
        /* args           = */ emptyArray<String>(),
        /* env            = */ emptyArray<String>(),
        /* transcriptRows = */ null,
        /* client         = */ client,
    )
}
