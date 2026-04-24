package dev.kuch.termx.feature.terminal

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Pins the "bytes reach the emulator" side of the remote-terminal
 * handoff. Complements [SshSessionClientTest], which covers the
 * "emulator nudge reaches the view" side.
 */
@RunWith(AndroidJUnit4::class)
class RemoteTerminalSessionTest {

    @Test
    fun feedRemoteBytes_forwardsToEmulator_andFiresNotify() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CountingClient(context)
        val session = RemoteTerminalSession(
            client = client,
            transcriptRows = null,
            onInputBytes = { /* outbound bytes not under test here */ },
            onResize = { _, _ -> },
        )
        // Force emulator init; real code drives this from the view's
        // first measure pass. Picking 80x24 / 8x16 matches the
        // INITIAL_COLS/INITIAL_ROWS constants in TerminalViewModel.
        session.updateSize(80, 24, 8, 16)

        val greeting = "hello\n".toByteArray(Charsets.UTF_8)
        session.feedRemoteBytes(greeting)

        // feedRemoteBytes posts onto the main-thread Handler; drain it
        // so the emulator.append + notifyScreenUpdate actually run.
        shadowOf(Looper.getMainLooper()).idle()

        // The emulator's active screen should now show "hello" on row 0.
        val firstRow = session.emulator.screen
            .getSelectedText(0, 0, 80, 1)
            .trimEnd()
        assertEquals("hello", firstRow)

        // And the client must have seen at least one onTextChanged —
        // the UI-repaint hook that used to be a no-op.
        assertTrue(
            "feedRemoteBytes must trigger onTextChanged so the view invalidates; " +
                "observed ${client.textChangedCount} calls",
            client.textChangedCount >= 1,
        )
    }

    @Test
    fun feedRemoteBytes_ignoresEmptyArrays() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CountingClient(context)
        val session = RemoteTerminalSession(
            client = client,
            transcriptRows = null,
            onInputBytes = { /* unused */ },
            onResize = { _, _ -> },
        )
        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()
        val before = client.textChangedCount

        session.feedRemoteBytes(ByteArray(0))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "empty chunk must not bump text-changed count",
            before,
            client.textChangedCount,
        )
    }

    /**
     * Minimal [TerminalSessionClient] that just tallies onTextChanged
     * invocations. The production `SshSessionClient` does the same thing
     * plus the view.post() repaint; this test stays decoupled from that
     * class so a regression in either side fails a distinct test.
     */
    private class CountingClient(
        private val context: android.content.Context,
    ) : TerminalSessionClient {
        var textChangedCount = 0
            private set

        override fun onTextChanged(session: com.termux.terminal.TerminalSession) {
            textChangedCount += 1
        }

        override fun onTitleChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onSessionFinished(session: com.termux.terminal.TerminalSession) {}
        override fun onCopyTextToClipboard(session: com.termux.terminal.TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: com.termux.terminal.TerminalSession?) {}
        override fun onBell(session: com.termux.terminal.TerminalSession) {}
        override fun onColorsChanged(session: com.termux.terminal.TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
