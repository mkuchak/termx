package dev.kuch.termx.feature.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Bridge from Termux's [TerminalSessionClient] callbacks to Android system
 * services. Deliberately thin — anything that maps cleanly to a system
 * service does so here; anything that will grow richer in later phases
 * (title, text-changed, colors, cursor) is a no-op for now.
 *
 * The adapter does **not** hold the active [TerminalSession]; Termux hands
 * the session back through every callback argument, so the ViewModel can
 * stay the single owner of the session reference.
 */
internal class SshSessionClient(
    private val context: Context,
    private val onSessionFinished: () -> Unit,
) : TerminalSessionClient {

    override fun onTextChanged(changedSession: TerminalSession) {
        // Phase 3 (tmux hooks) will care about this; for Phase 1 the
        // TerminalView invalidates itself on emulator append.
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        // No tab bar yet. Phase 3 will surface the remote-set title.
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onSessionFinished.invoke()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("termx", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (session == null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val pasted = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (pasted.isEmpty()) return
        session.getEmulator()?.paste(pasted) ?: session.write(pasted)
    }

    override fun onBell(session: TerminalSession) {
        vibrate(BELL_VIBRATION_MILLIS)
    }

    override fun onColorsChanged(session: TerminalSession) {
        // Theme pack (Task #18) will listen; no-op for now.
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        // Cursor blink handled entirely by TerminalView.
    }

    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) {
        Log.e(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag ?: LOG_TAG, message.orEmpty())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, message.orEmpty(), e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(tag ?: LOG_TAG, "stack trace", e)
    }

    private fun vibrate(millis: Long) {
        val vibrator = resolveVibrator() ?: return
        runCatching {
            val effect = VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        }
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private companion object {
        const val LOG_TAG = "TerminalSession"
        const val BELL_VIBRATION_MILLIS = 30L
    }
}
