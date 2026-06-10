package dev.kuch.termx.feature.terminal.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalTypefaces
import com.termux.view.fontLineSpacingAndAscent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offscreen renderer of a live [TerminalEmulator] screen into a [Bitmap],
 * built for the home screen's active-session preview cards.
 *
 * View screenshots can't work here: a minimized session has no attached
 * [com.termux.view.TerminalView], and a detached view never draws. Instead
 * this paints the emulator buffer directly through the same vendored
 * [TerminalRenderer] the on-screen view uses — `TerminalRenderer.render`
 * is canvas-agnostic, so a `Canvas(Bitmap)` works exactly like the view's
 * canvas in `TerminalView.onDraw`.
 */
class TerminalThumbnailRenderer(context: Context) {

    // ~10sp at device density. The exact value barely matters visually —
    // render() scale-to-fits the whole grid into the target anyway, so the
    // text size only sets the natural raster's proportions — but a small
    // size keeps the per-frame glyph work cheap enough for the main thread
    // (see the threading contract on render()).
    private val textSizePx: Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        THUMBNAIL_TEXT_SIZE_SP,
        context.resources.displayMetrics,
    ).roundToInt().coerceAtLeast(1)

    // Same JetBrains Mono NL bundle the live TerminalView installs in
    // setTextSize. TerminalTypefaces.bundled is a process-wide singleton,
    // so this reuses the already-parsed faces; thumbnails therefore render
    // with identical glyph shapes to the real terminal, just smaller.
    private val renderer: TerminalRenderer =
        TerminalRenderer(textSizePx, TerminalTypefaces.bundled(context))

    /**
     * Render [emulator]'s live screen (not scrollback) into a fresh
     * [widthPx] x [heightPx] bitmap.
     *
     * THREADING CONTRACT: must be called on the main thread. The emulator
     * buffer is main-thread-confined in this fork —
     * `RemoteTerminalSession.feedRemoteBytes` posts every append onto the
     * main looper — so reading `TerminalBuffer` rows from any other thread
     * would race those appends (`TerminalRow.mText` is a bare char[] with
     * no synchronization). [thumbnails] handles the dispatch for you.
     */
    fun render(emulator: TerminalEmulator, widthPx: Int, heightPx: Int): Bitmap {
        require(widthPx > 0 && heightPx > 0) {
            "thumbnail dimensions must be positive, got ${widthPx}x$heightPx"
        }
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // TerminalRenderer.render only paints NON-default cell backgrounds
        // ("Only draw non-default background" in drawTextRun); on screen the
        // default background comes from the View itself (ThemeBinder sets
        // view.setBackgroundColor(theme.background), and TerminalView.onDraw
        // falls back to black with no emulator). There is no View here, so
        // mirror that pre-fill ourselves. Reading the LIVE palette — rather
        // than hardcoding the Sorcerer theme — yields the Sorcerer background
        // under ThemeBinder.installAsDefault while still honoring sessions
        // that changed it at runtime (OSC 11 etc.).
        val background = emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]
        canvas.drawColor(background)

        // Scale/crop decision: uniform scale-to-FIT the whole screen,
        // anchored top-left, applied as a canvas matrix. Chosen over (a)
        // cropping the top-left region, which would hide the bottom rows
        // where the prompt/activity lives, and over (b) rendering a
        // natural-size intermediate bitmap and down-sampling it, because a
        // matrix scale rasterizes glyphs directly at the final size (one
        // allocation, no filtering blur). The grid aspect won't match the
        // card's; the spare strip stays background-filled, which reads as
        // more terminal. Natural painted size comes from the renderer's own
        // metrics: width = columns * fontWidth, height = rows *
        // fontLineSpacing + fontLineSpacingAndAscent (the exact formula
        // TerminalView.updateSize inverts to size its grid).
        val naturalWidth = ceil(emulator.mColumns * renderer.fontWidth)
        val naturalHeight =
            (emulator.mRows * renderer.fontLineSpacing + renderer.fontLineSpacingAndAscent)
                .toFloat()
        val scale = min(widthPx / naturalWidth, heightPx / naturalHeight)
        canvas.scale(scale, scale)

        // topRow 0 = the live screen (negative values would show scrollback);
        // selection coordinates -1 = no selection, matching TerminalView's
        // mDefaultSelectors when nothing is selected.
        renderer.render(emulator, canvas, 0, -1, -1, -1, -1)
        return bitmap
    }

    /**
     * Poll-render [emulatorProvider]'s current emulator once per [periodMs]
     * while collected, emitting a fresh [widthPx] x [heightPx] bitmap per
     * tick. Ticks where the provider returns null (session not initialized
     * yet, or torn down) are skipped silently.
     *
     * Simple polling is the deliberate choice over hooking
     * `TerminalSessionClient.onTextChanged`: that callback fires per append
     * burst (hundreds of times per second under chatty output) and is
     * already consumed by the live view's repaint chain, so piggybacking
     * would need both debouncing and wire/unwire lifecycle plumbing per
     * session. A 1 Hz poll caps thumbnail work deterministically, and a
     * preview card that is at most one second stale is imperceptible. The
     * trade: idle sessions re-render an identical frame every tick — one
     * card-sized bitmap per second, cheap enough to not bother diffing.
     *
     * Each render hops to [Dispatchers.Main.immediate] because the emulator
     * buffer is main-thread-confined (see [render]); `.immediate` skips the
     * redundant post when the collector already runs on main. The bitmap is
     * drawn at final size in that single pass, so no off-main
     * post-processing step is needed; collectors are free to do any further
     * transformation off the main thread.
     */
    fun thumbnails(
        emulatorProvider: () -> TerminalEmulator?,
        widthPx: Int,
        heightPx: Int,
        periodMs: Long = 1_000L,
    ): Flow<Bitmap> = flow {
        while (true) {
            val bitmap = withContext(Dispatchers.Main.immediate) {
                emulatorProvider()?.let { render(it, widthPx, heightPx) }
            }
            if (bitmap != null) emit(bitmap)
            delay(periodMs)
        }
    }

    private companion object {
        const val THUMBNAIL_TEXT_SIZE_SP = 10f
    }
}
