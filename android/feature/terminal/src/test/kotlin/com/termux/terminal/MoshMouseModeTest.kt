package com.termux.terminal

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Pins the v1.7.9 mouse-mode fix (gotcha #34).
 *
 * mosh keeps only ONE mouse reporting mode and ONE encoding, collapsing a
 * multiplexer's `1000/1002/1003` + `1006/1015` enable sequence down to
 * whichever it saw last. herdr (and zellij — see mosh#1364) enable any-event
 * tracking (1003), so over mosh the only reporting mode that reaches the
 * on-device emulator is 1003. The old emulator ignored 1003, so mouse mode
 * stayed OFF: finger-scroll fell back to arrow keys (recalling the agent's
 * prompt history) and taps never reached the multiplexer. SSH kept 1002 via
 * raw passthrough and worked. The fix honors 1003 (and urxvt 1015 encoding)
 * so the emulator enters mouse mode whatever mosh collapses to.
 *
 * Reuses the [MoshRemoteTerminalSession] harness: it builds the real
 * [TerminalEmulator], `feedRemoteBytes` drives DECSET parsing, and the
 * emulator's mouse reports come back out through `onInputBytes` (the write
 * sink), which we record. `feedRemoteBytes` marshals through the main-thread
 * [android.os.Handler], so every feed is followed by a looper idle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MoshMouseModeTest {

    /** ESC / CSI introducer (ASCII 27). Built from the code point to avoid a raw control byte in source. */
    private val esc = 27.toChar().toString()
    private val outbound = ByteArrayOutputStream()

    // ── (1) the core regression: any-event tracking must turn mouse mode on ──
    @Test
    fun anyEventTracking1003_activatesMouseMode() {
        val session = liveSession()
        // Over mosh this is the ONLY mouse-enable that survives the collapse.
        feed(session, "$esc[?1003h")
        assertTrue(
            "DECSET 1003 (any-event) must enable mouse tracking — else scroll/taps break over mosh",
            session.emulator.isMouseTrackingActive(),
        )
    }

    // ── (2) the three reporting modes are mutually exclusive (last wins) ──
    @Test
    fun reportingModes_areMutuallyExclusive() {
        val session = liveSession()
        // All three arrive over SSH (raw passthrough); the last one wins.
        feed(session, "$esc[?1000h$esc[?1002h$esc[?1003h")
        assertTrue(session.emulator.isMouseTrackingActive())
        // Disabling the active (last) mode must leave NO reporting mode set —
        // proving the earlier two were cleared, not merely shadowed.
        feed(session, "$esc[?1003l")
        assertFalse(
            "clearing the active reporting mode must leave mouse tracking off",
            session.emulator.isMouseTrackingActive(),
        )
    }

    // ── (3) SGR encoding (1006) → CSI < b ; x ; y M ──
    @Test
    fun sgrEncoding_emitsSgrMouseReport() {
        val session = liveSession()
        feed(session, "$esc[?1003h$esc[?1006h")
        outbound.reset()
        session.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 10, 5, true)
        assertEquals("$esc[<0;10;5M", outbound.toString("UTF-8"))
    }

    // ── (4) urxvt encoding (1015) → CSI Cb ; x ; y M, Cb = button + 32 ──
    @Test
    fun urxvtEncoding_emitsUrxvtMouseReport() {
        val session = liveSession()
        // mosh may collapse the encoding to urxvt; the emulator must then emit
        // decimal CSI Cb;Cx;Cy M (Cb = button + 32), not SGR, or herdr — which
        // is then parsing in urxvt mode — mis-reads every report.
        feed(session, "$esc[?1003h$esc[?1015h")
        outbound.reset()
        session.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 10, 5, true)
        assertEquals("$esc[32;10;5M", outbound.toString("UTF-8"))
        // Release reports button 3 (+32 = 35).
        outbound.reset()
        session.emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 10, 5, false)
        assertEquals("$esc[35;10;5M", outbound.toString("UTF-8"))
    }

    /** A mosh session whose emulator is already built (80x24), recording outbound bytes. */
    private fun liveSession(): MoshRemoteTerminalSession {
        val session = MoshRemoteTerminalSession(
            client = RecordingClient(),
            transcriptRows = null,
            onInputBytes = { outbound.write(it, 0, it.size) },
            onResize = { _, _ -> },
        )
        session.updateSize(80, 24, 8, 16)
        shadowOf(Looper.getMainLooper()).idle()
        return session
    }

    private fun feed(session: MoshRemoteTerminalSession, ansi: String) {
        session.feedRemoteBytes(ansi.toByteArray(Charsets.UTF_8))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Minimal no-op [TerminalSessionClient]; the assertions don't lean on any callback. */
    private class RecordingClient : TerminalSessionClient {
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
