package dev.kuch.termx.feature.terminal.gestures

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.termux.view.TerminalView

/**
 * Pure utilities for Task #17's terminal gestures — font-size clamping
 * and URL extraction from a tap coordinate.
 *
 * The handler stays stateless so the same instance can serve every
 * [TerminalView] the app creates (one per tab). State lives in
 * [dev.kuch.termx.feature.terminal.TerminalViewModel] and DataStore.
 */
object TerminalGestureHandler {

    const val MIN_SP: Int = 8
    const val MAX_SP: Int = 32

    private const val LOG_TAG = "TerminalGestures"

    /** Anchors the URL detection regex (tab-wide, no escaping gotchas). */
    private val URL_REGEX = Regex("https?://[^\\s<>\"']+")

    /** Characters commonly adjacent to URLs in prose that should be stripped. */
    private val TRAILING_PUNCT = charArrayOf(
        '.', ',', ')', ']', '}', '\'', '"', ';', ':',
    )

    /** Clamp a candidate font size (sp) into the supported range. */
    fun clampFontSize(sp: Int): Int = sp.coerceIn(MIN_SP, MAX_SP)

    /**
     * If the word under (x, y) on [view] parses as an http(s) URL, return
     * it (minus any trailing punctuation). Otherwise null.
     *
     * We synthesize a brief `ACTION_DOWN` so we can reuse Termux's
     * existing column/row math in [TerminalView.getColumnAndRow], then
     * look up the whole (wrap-aware) word via
     * [com.termux.terminal.TerminalBuffer.getWordAtLocation].
     * Everything that can legally be null is guarded; failures degrade to
     * "not a URL" rather than throwing.
     */
    fun extractUrlAt(view: TerminalView, x: Float, y: Float): String? {
        val emulator = view.mEmulator ?: return null
        val now = SystemClock.uptimeMillis()
        val synthetic = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_DOWN, x, y, 0,
        )
        try {
            val colRow = runCatching { view.getColumnAndRow(synthetic, true) }.getOrNull()
                ?: return null
            val column = colRow.getOrNull(0) ?: return null
            val row = colRow.getOrNull(1) ?: return null
            val word = runCatching {
                emulator.screen.getWordAtLocation(column, row)
            }.onFailure { t ->
                Log.w(LOG_TAG, "getWordAtLocation failed at ($column,$row)", t)
            }.getOrNull()?.trim()?.trimEnd(*TRAILING_PUNCT)
            if (word.isNullOrEmpty()) return null
            return if (URL_REGEX.matches(word)) word else null
        } finally {
            synthetic.recycle()
        }
    }
}
