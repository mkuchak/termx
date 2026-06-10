package dev.kuch.termx.feature.terminal.thumbnail

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the offscreen emulator -> Bitmap path that the home screen's
 * session preview cards rely on. Needs Robolectric: Bitmap / Canvas /
 * Typeface require an Android runtime.
 *
 * GraphicsMode NATIVE is load-bearing: it routes Bitmap / Canvas / Paint /
 * Typeface through real Skia (RNG) so pixels are actually rasterized. The
 * default LEGACY shadows merely record draw calls — getPixel() returns 0
 * for every pixel — which would make every pixel assertion here vacuous.
 * (NATIVE only becomes the default in Robolectric 4.14; this repo pins
 * 4.13.)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalThumbnailRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun render_producesRequestedDimensions_withForegroundPixels() {
        val renderer = TerminalThumbnailRenderer(context)
        val emulator = newEmulator()
        feed(emulator, "hello world")

        val bitmap = renderer.render(emulator, widthPx = 240, heightPx = 135)

        assertEquals(240, bitmap.width)
        assertEquals(135, bitmap.height)

        // The bitmap must not be uniformly the background fill — glyph (and
        // cursor) pixels prove the renderer actually painted the buffer.
        val background = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
        val nonBackground = countNonBackgroundPixels(bitmap, background)
        assertTrue(
            "expected foreground pixels from 'hello world', but every pixel " +
                "was the background color",
            nonBackground > 0,
        )
        // ... and a mostly-empty 80x24 screen must still be mostly the
        // background fill. This guards against the assertion above passing
        // vacuously if graphics ever stopped rasterizing for real (e.g. a
        // shadow Bitmap returning 0 for every pixel would make ALL pixels
        // "non-background" against an opaque palette color).
        assertTrue(
            "expected the empty screen area to carry the background fill; " +
                "$nonBackground of ${bitmap.width * bitmap.height} pixels were non-background",
            nonBackground < bitmap.width * bitmap.height / 2,
        )
    }

    @Test
    fun render_reflectsNewEmulatorOutput() {
        val renderer = TerminalThumbnailRenderer(context)
        val emulator = newEmulator()
        feed(emulator, "hello world")

        val before = renderer.render(emulator, widthPx = 240, heightPx = 135)
        feed(emulator, "\r\nmore output 0123456789")
        val after = renderer.render(emulator, widthPx = 240, heightPx = 135)

        assertFalse(
            "feeding more bytes must change the rendered thumbnail",
            before.sameAs(after),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thumbnails_pollsOncePerPeriod_andSkipsNullEmulator() = runTest {
        // Route Dispatchers.Main onto the test scheduler so the flow's
        // withContext(Main.immediate) hop and its delay() both run in
        // virtual time, making the 1 Hz cadence assertable.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val renderer = TerminalThumbnailRenderer(context)
            var emulator: TerminalEmulator? = null
            val frames = mutableListOf<Bitmap>()

            val collector = launch {
                renderer.thumbnails({ emulator }, widthPx = 80, heightPx = 48, periodMs = 1_000)
                    .collect { frames += it }
            }

            // Polls at t=0 / 1000 / 2000 all see a null emulator -> no frames.
            advanceTimeBy(2_500)
            assertEquals(0, frames.size)

            emulator = newEmulator().also { feed(it, "hi") }

            // Polls at t=3000 and t=4000 -> exactly two frames.
            advanceTimeBy(2_000)
            assertEquals(2, frames.size)
            assertEquals(80, frames[0].width)
            assertEquals(48, frames[0].height)

            collector.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    // ----- helpers -------------------------------------------------------

    /**
     * Construct the vendored emulator directly — no session, no view —
     * exactly the way RemoteTerminalSession.initializeEmulator does, just
     * with a standalone TerminalOutput sink instead of the session itself.
     * 80x24 / 8x16 matches the INITIAL_* convention used elsewhere in this
     * module's tests; a thumbnail must not depend on any particular grid.
     */
    private fun newEmulator(columns: Int = 80, rows: Int = 24): TerminalEmulator =
        TerminalEmulator(
            /* session          = */ NoopOutput(),
            /* columns          = */ columns,
            /* rows             = */ rows,
            /* cellWidthPixels  = */ 8,
            /* cellHeightPixels = */ 16,
            /* transcriptRows   = */ null,
            /* client           = */ NoopClient(),
        )

    /**
     * Append bytes synchronously. Safe here because the Robolectric test
     * thread IS the main thread, matching the production confinement
     * (feedRemoteBytes posts appends to the main looper).
     */
    private fun feed(emulator: TerminalEmulator, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    private fun countNonBackgroundPixels(bitmap: Bitmap, background: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != background) count++
            }
        }
        return count
    }

    /** Emulator replies (cursor reports, OSC responses) go nowhere. */
    private class NoopOutput : TerminalOutput() {
        override fun write(data: ByteArray?, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private class NoopClient : TerminalSessionClient {
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
