package com.termux.terminal

import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the v1.7.8 mosh keyframe buffer-and-replay fix (gotcha #33).
 *
 * mosh is a DIFF protocol: its first frame is the full-screen keyframe
 * and every later frame is only a delta against it. The byte-pump starts
 * draining mosh-client before the [com.termux.view.TerminalView] has laid
 * out and built the emulator, so the OLD code dropped those pre-init bytes
 * — losing the keyframe and leaving the screen permanently desynced. The
 * fix buffers them and replays them, in order, the moment
 * [MoshRemoteTerminalSession.initializeEmulator] creates the emulator.
 *
 * Mirrors [RemoteTerminalSessionTest]'s harness: Robolectric (the emulator
 * is Android-coupled), and because [MoshRemoteTerminalSession.feedRemoteBytes]
 * marshals through the main-thread [android.os.Handler], every feed /
 * updateSize is followed by a looper idle before the screen is read back
 * via `emulator.screen` (otherwise the posts never run).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MoshRemoteTerminalSessionTest {

    // ── (1) pre-init bytes: buffered, then replayed in feed order ──
    @Test
    fun preInitBytes_areBufferedAndReplayedInOrder() {
        val session = newSession()

        // mosh delivers its keyframe before TerminalView has laid out, so
        // the emulator does not exist yet — these must be buffered, not
        // dropped. Two chunks so the replay also proves ordering.
        session.feedRemoteBytes("HELLO".toByteArray(Charsets.UTF_8))
        session.feedRemoteBytes(" WORLD".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        // First measure pass builds the emulator (80x24 / 8x16 matches the
        // INITIAL_COLS/INITIAL_ROWS constants) and must replay the buffer.
        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "pre-init keyframe must be replayed in order; screen row 0 was \"${row0(session)}\"",
            row0(session).contains("HELLO WORLD"),
        )
    }

    // ── (2) post-init bytes: straight through the normal append path ──
    @Test
    fun postInitBytes_passStraightThroughToScreen() {
        val session = newSession()
        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()

        session.feedRemoteBytes("PASSTHROUGH".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "post-init bytes must reach the screen; row 0 was \"${row0(session)}\"",
            row0(session).contains("PASSTHROUGH"),
        )
    }

    // ── (3) bytes after close: dropped ──
    @Test
    fun bytesAfterClose_areDropped() {
        val session = newSession()
        session.updateSize(80, 24, 8, 16)
        session.feedRemoteBytes("BASE".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        session.onRemoteSessionClosed()
        session.feedRemoteBytes("ZZZ".toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()

        val screen = row0(session)
        assertTrue("pre-close bytes must remain; row 0 was \"$screen\"", screen.contains("BASE"))
        assertFalse("bytes fed after close must be dropped; row 0 was \"$screen\"", screen.contains("ZZZ"))
    }

    /** Build a session with no-op recorders and a relaxed fake client. */
    private fun newSession(): MoshRemoteTerminalSession =
        MoshRemoteTerminalSession(
            client = RelaxedClient(),
            transcriptRows = null,
            onInputBytes = { /* outbound bytes not under test here */ },
            onResize = { _, _ -> },
        )

    /** Row 0 of the active screen, trailing blanks trimmed. */
    private fun row0(session: MoshRemoteTerminalSession): String =
        session.emulator.screen.getSelectedText(0, 0, 80, 1).trimEnd()

    /**
     * Minimal no-op [TerminalSessionClient]. Mirrors
     * RemoteTerminalSessionTest's CountingClient, which can't be reused
     * here (it is private to the dev.kuch.termx.feature.terminal package).
     * The screen-readback assertions don't lean on any callback behavior.
     */
    private class RelaxedClient : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession) {}
        override fun onTitleChanged(session: TerminalSession) {}
        override fun onSessionFinished(session: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
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
