package dev.kuch.termx.feature.terminal

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.MoshRemoteTerminalSession
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
    fun feedRemoteBytes_buffersBeforeInit_andReplaysOnInitializeEmulator() {
        // Regression test for the v1.1.21 → v1.1.22 black-screen bug.
        // Bytes that arrived before the AndroidView attached (and
        // therefore before initializeEmulator ran) used to be silently
        // dropped by `mEmulator ?: return`. For mosh that meant the
        // server's initial state-sync was lost and every subsequent
        // diff landed on an empty emulator. Same race exists on the
        // SSH path; this test pins the symmetric defensive fix.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CountingClient(context)
        val session = RemoteTerminalSession(
            client = client,
            transcriptRows = null,
            onInputBytes = { /* unused */ },
            onResize = { _, _ -> },
        )

        // Feed bytes BEFORE initializeEmulator has run.
        session.feedRemoteBytes("queued-".toByteArray(Charsets.UTF_8))
        session.feedRemoteBytes("output\n".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()
        // Pre-init: there is no emulator yet, so nothing has rendered
        // and onTextChanged hasn't fired.
        assertEquals(0, client.textChangedCount)

        // Now lay out — initializeEmulator runs and must drain the
        // queue into the freshly-built emulator.
        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()

        val firstRow = session.emulator.screen
            .getSelectedText(0, 0, 80, 1)
            .trimEnd()
        assertEquals(
            "buffered bytes must be replayed into the emulator on initializeEmulator",
            "queued-output",
            firstRow,
        )
        assertTrue(
            "drain must trigger at least one onTextChanged so the view repaints",
            client.textChangedCount >= 1,
        )
    }

    @Test
    fun feedRemoteBytes_appliesPostInitBytesNormally_afterReplay() {
        // Half-and-half: some bytes pre-init (replayed), some
        // post-init (applied directly). Ensures the replay path
        // doesn't break ordering or re-fire on later writes.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CountingClient(context)
        val session = RemoteTerminalSession(
            client = client,
            transcriptRows = null,
            onInputBytes = { /* unused */ },
            onResize = { _, _ -> },
        )

        session.feedRemoteBytes("pre-".toByteArray(Charsets.UTF_8))
        session.updateSize(80, 24, 8, 16)
        session.feedRemoteBytes("post\n".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        val firstRow = session.emulator.screen
            .getSelectedText(0, 0, 80, 1)
            .trimEnd()
        assertEquals("pre-post", firstRow)
    }

    @Test
    fun moshRemoteTerminalSession_buffersBeforeInit_andReplaysOnInitializeEmulator() {
        // The v1.1.22 fix's primary target: the mosh transport
        // pushes a server state-sync within milliseconds of the UDP
        // handshake closing. Without buffering, the prompt is
        // silently dropped; the user sees a black screen with the
        // cursor at (1, 1). This test pins the mosh-side fix
        // directly.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = CountingClient(context)
        val session = MoshRemoteTerminalSession(
            client = client,
            transcriptRows = null,
            onInputBytes = { /* unused */ },
            onResize = { _, _ -> },
        )

        // Mosh session pushes DECCKM_ON to the channel before
        // anything else; here we simulate a similar very-early
        // emission landing before the AndroidView has attached.
        session.feedRemoteBytes("[?1h".toByteArray(Charsets.UTF_8))
        session.feedRemoteBytes("vps:~$ ".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, client.textChangedCount)

        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()

        val firstRow = session.emulator.screen
            .getSelectedText(0, 0, 80, 1)
            .trimEnd()
        assertEquals("vps:~\$", firstRow)
        assertTrue(client.textChangedCount >= 1)
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
