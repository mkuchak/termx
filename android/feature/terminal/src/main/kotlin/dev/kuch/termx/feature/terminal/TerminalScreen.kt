package dev.kuch.termx.feature.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.UUID

/**
 * The app's only terminal surface (Phase 1).
 *
 * For the Phase 1 "no server manager yet" path [serverId] is always `null`
 * and the ViewModel falls back to `BuildConfig.TEST_SERVER_*` + a debug-only
 * test key in assets. Phase 2 (Task #21) will pass a real UUID.
 */
@Composable
fun TerminalScreen(
    serverId: UUID? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(serverId) {
        viewModel.connect(serverId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (val state = uiState) {
            TerminalUiState.Idle,
            TerminalUiState.Connecting -> ConnectingPane()

            is TerminalUiState.Connected -> TerminalPane(state.session)

            is TerminalUiState.Error -> ErrorPane(
                message = state.message,
                onRetry = { viewModel.connect(serverId) },
            )

            TerminalUiState.Disconnected -> DisconnectedPane(
                onReconnect = { viewModel.connect(serverId) },
            )
        }
    }
}

@Composable
private fun ConnectingPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "connecting to host…",
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) { Text("retry") }
        }
    }
}

@Composable
private fun DisconnectedPane(onReconnect: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "disconnected",
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(onClick = onReconnect) { Text("reconnect") }
        }
    }
}

@Composable
private fun TerminalPane(session: TerminalSession) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = TerminalView(ctx, null).apply {
                setTerminalViewClient(MinimalTerminalViewClient)
                setTextSize(DEFAULT_TERMINAL_TEXT_SIZE_SP)
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                isFocusable = true
                isFocusableInTouchMode = true
            }
            view.attachSession(session)
            view.requestFocus()
            view
        },
        update = { view ->
            if (view.currentSession !== session) {
                view.attachSession(session)
                view.requestFocus()
            }
        },
    )
}

/**
 * Placeholder client for TerminalView. Task #16 ships a real one with
 * extra-keys + sticky modifiers; Task #17 adds gestures. For now we
 * return sane defaults and forward nothing.
 */
private object MinimalTerminalViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3.0f)
    override fun onSingleTapUp(e: MotionEvent?) { /* focus is managed by the view itself */ }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag ?: "TerminalView", message.orEmpty()) }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag ?: "TerminalView", message.orEmpty()) }
    override fun logInfo(tag: String?, message: String?) { android.util.Log.i(tag ?: "TerminalView", message.orEmpty()) }
    override fun logDebug(tag: String?, message: String?) { android.util.Log.d(tag ?: "TerminalView", message.orEmpty()) }
    override fun logVerbose(tag: String?, message: String?) { android.util.Log.v(tag ?: "TerminalView", message.orEmpty()) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        android.util.Log.e(tag ?: "TerminalView", message.orEmpty(), e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) {
        android.util.Log.e(tag ?: "TerminalView", "stack trace", e)
    }
}

private const val DEFAULT_TERMINAL_TEXT_SIZE_SP = 14
