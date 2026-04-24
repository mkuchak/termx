package dev.kuch.termx.feature.terminal.gestures

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.termux.view.TerminalView
import dev.kuch.termx.libs.sshnative.PtyChannel

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

    /**
     * Tmux prefix key (Ctrl-B, assumes default binding) followed by `[` —
     * the canonical sequence to enter tmux `copy-mode`. Task #28's
     * two-finger scroll on a tmux-backed tab sends these bytes before
     * forwarding drag deltas as arrow keys so the user can scrub tmux's
     * scrollback without ever leaving the terminal surface.
     */
    private val TMUX_ENTER_COPY_MODE: ByteArray = byteArrayOf(0x02, '['.code.toByte())

    /** Single `q` keystroke to exit tmux `copy-mode`. */
    private val TMUX_EXIT_COPY_MODE: ByteArray = byteArrayOf('q'.code.toByte())

    /** ANSI `ESC [ A` — cursor/arrow up. Used as tmux copy-mode scroll-up. */
    val ARROW_UP: ByteArray = byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte())

    /** ANSI `ESC [ B` — cursor/arrow down. Used as tmux copy-mode scroll-down. */
    val ARROW_DOWN: ByteArray = byteArrayOf(0x1b, '['.code.toByte(), 'B'.code.toByte())

    /** Clamp a candidate font size (sp) into the supported range. */
    fun clampFontSize(sp: Int): Int = sp.coerceIn(MIN_SP, MAX_SP)

    /**
     * Send Ctrl-B `[` to [channel] to enter tmux copy-mode. Safe to call
     * from a coroutine context; callers typically dispatch on Dispatchers.IO.
     */
    suspend fun enterTmuxCopyMode(channel: PtyChannel) {
        runCatching { channel.write(TMUX_ENTER_COPY_MODE) }
            .onFailure { t -> Log.w(LOG_TAG, "enterTmuxCopyMode failed", t) }
    }

    /** Send `q` to [channel] to exit tmux copy-mode. */
    suspend fun exitTmuxCopyMode(channel: PtyChannel) {
        runCatching { channel.write(TMUX_EXIT_COPY_MODE) }
            .onFailure { t -> Log.w(LOG_TAG, "exitTmuxCopyMode failed", t) }
    }

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
