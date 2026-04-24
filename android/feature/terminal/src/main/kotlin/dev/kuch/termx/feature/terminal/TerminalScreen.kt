package dev.kuch.termx.feature.terminal

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.GestureDetectorCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.kuch.termx.core.domain.model.TmuxSession
import dev.kuch.termx.core.domain.theme.BuiltInThemes
import dev.kuch.termx.feature.terminal.gestures.TerminalGestureHandler
import dev.kuch.termx.feature.terminal.gestures.UrlTapConfirmDialog
import dev.kuch.termx.feature.terminal.keys.ExtraKey
import dev.kuch.termx.feature.terminal.keys.ExtraKeyBytes
import dev.kuch.termx.feature.terminal.keys.ExtraKeysBar
import dev.kuch.termx.feature.terminal.sessions.KillSessionDialog
import dev.kuch.termx.feature.terminal.sessions.NewSessionDialog
import dev.kuch.termx.feature.terminal.sessions.RenameSessionDialog
import dev.kuch.termx.feature.terminal.sessions.SessionTabActionsMenu
import dev.kuch.termx.feature.terminal.sessions.SessionTabBar
import dev.kuch.termx.feature.terminal.sessions.SessionTabBarViewModel
import dev.kuch.termx.feature.terminal.theme.ThemeBinder
import dev.kuch.termx.feature.ptt.PttSurface
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * The app's only terminal surface.
 *
 * Task #26 layered the multi-session tab bar on top of the original
 * Task #15 composable. Layout top-to-bottom:
 *
 *  1. [SessionTabBar] — tmux session pills with activity indicators +
 *     "+" button.
 *  2. The active tab's `TerminalView` via [AndroidView].
 *  3. [ExtraKeysBar] docked above the soft keyboard.
 *
 * Two ViewModels collaborate here:
 *  - [TerminalViewModel] owns the shared sshj [dev.kuch.termx.libs.sshnative.SshSession]
 *    plus a map of open [dev.kuch.termx.libs.sshnative.PtyChannel] by tab name,
 *    and exposes the currently-bound emulator as [TerminalUiState.activeSession].
 *  - [SessionTabBarViewModel] drives the tab list (via
 *    [dev.kuch.termx.core.domain.repository.TmuxSessionRepository.observeSessions])
 *    and the activity-flash set, plus the tmux write verbs
 *    (new/rename/kill) for the tab's context menu.
 */
@Composable
fun TerminalScreen(
    serverId: UUID? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
    tabBarViewModel: SessionTabBarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val activityFlashes by tabBarViewModel.activityFlashes.collectAsStateWithLifecycle()
    val sessionsFlow = remember(serverId) {
        serverId?.let { tabBarViewModel.sessions(it) }
    }
    val sessionList by (sessionsFlow?.collectAsStateWithLifecycle(initialValue = emptyList())
        ?: remember { mutableStateOf<List<TmuxSession>>(emptyList()) })

    LaunchedEffect(serverId) {
        viewModel.connect(serverId)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    // Dialog state — the tab bar long-press opens a dropdown menu which
    // kicks off one of these flows.
    var menuForSession by remember { mutableStateOf<TmuxSession?>(null) }
    var renameTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var killTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var showNewSession by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Hoisted reference to the currently-mounted [TerminalView] so the
    // tap-to-focus handler here AND the keyboard-toggle button in the
    // SessionTabBar can both target it. Assigned from the AndroidView
    // factory block in [TerminalPane]; nulled on dispose.
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val context = LocalContext.current
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    // First-show-on-entry — once the SSH session reaches Connected the
    // TerminalView is attached and ready. Request focus and pop the IME
    // so the user can type immediately without an extra tap.
    LaunchedEffect(uiState.status) {
        if (uiState.status == TerminalUiState.Status.Connected) {
            terminalViewRef.value?.let { v ->
                v.requestFocus()
                imm.showSoftInput(v, 0)
            }
        }
    }

    val showKeyboardOnTerminalTap: () -> Unit = {
        terminalViewRef.value?.let { v ->
            v.requestFocus()
            imm.showSoftInput(v, 0)
        }
    }

    val onToggleKeyboard: () -> Unit = {
        terminalViewRef.value?.let { v ->
            if (v.isFocused) {
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            } else {
                v.requestFocus()
                imm.showSoftInput(v, 0)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (serverId != null) {
                SessionTabBar(
                    sessions = sessionList,
                    activeSessionName = uiState.activeTabName,
                    activityFlashes = activityFlashes,
                    onTabSelected = viewModel::selectTab,
                    onNewSession = { showNewSession = true },
                    onLongPressTab = { menuForSession = it },
                    onSwipeUpTab = { viewModel.detachTab(it.name) },
                    onToggleKeyboard = onToggleKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                )
                SessionTabActionsMenu(
                    session = menuForSession,
                    onDismiss = { menuForSession = null },
                    onRename = {
                        renameTarget = it
                        menuForSession = null
                    },
                    onKill = {
                        killTarget = it
                        menuForSession = null
                    },
                    onClose = {
                        viewModel.detachTab(it.name)
                        menuForSession = null
                    },
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (uiState.status) {
                    TerminalUiState.Status.Idle,
                    TerminalUiState.Status.Connecting -> ConnectingPane()
                    TerminalUiState.Status.Connected -> {
                        val active = uiState.activeSession
                        if (active != null) {
                            ConnectedPane(
                                session = active,
                                tmuxBacked = uiState.tmuxBacked,
                                onWriteToPty = viewModel::writeToPty,
                                viewModel = viewModel,
                                terminalViewRef = terminalViewRef,
                                onTerminalTapShowKeyboard = showKeyboardOnTerminalTap,
                            )
                            // Task #39/#42 — push-to-talk surface sits on
                            // top of the terminal area. The FAB floats
                            // bottom-right; transcript card expands full
                            // width at the bottom while recording.
                            PttSurface(
                                onSend = { text, appendNewline ->
                                    val payload = if (appendNewline) text + "\n" else text
                                    viewModel.writeToPty(payload.toByteArray())
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            ConnectingPane()
                        }
                    }
                    TerminalUiState.Status.Disconnected -> {
                        val err = uiState.error
                        if (err != null) {
                            ErrorPane(message = err, onRetry = {
                                viewModel.clearError()
                                viewModel.connect(serverId)
                            })
                        } else {
                            DisconnectedPane(onReconnect = { viewModel.connect(serverId) })
                        }
                    }
                }
            }
        }
    }

    if (showNewSession && serverId != null) {
        NewSessionDialog(
            onConfirm = { name ->
                showNewSession = false
                scope.launch {
                    runCatching {
                        tabBarViewModel.newSession(serverId, name)
                        // Force a refresh so the tab appears immediately,
                        // then auto-switch to it.
                        tabBarViewModel.refresh(serverId)
                        viewModel.selectTab(name)
                    }
                }
            },
            onDismiss = { showNewSession = false },
        )
    }

    val renaming = renameTarget
    if (renaming != null && serverId != null) {
        RenameSessionDialog(
            currentName = renaming.name,
            onConfirm = { newName ->
                renameTarget = null
                scope.launch {
                    runCatching {
                        tabBarViewModel.renameSession(serverId, renaming.name, newName)
                        tabBarViewModel.refresh(serverId)
                    }
                }
            },
            onDismiss = { renameTarget = null },
        )
    }

    val killing = killTarget
    if (killing != null && serverId != null) {
        KillSessionDialog(
            sessionName = killing.name,
            onConfirm = {
                killTarget = null
                scope.launch {
                    runCatching {
                        // Detach locally first so we don't try to write
                        // to a channel whose tmux target is gone.
                        if (uiState.openTabs.contains(killing.name)) {
                            viewModel.detachTab(killing.name)
                        }
                        tabBarViewModel.killSession(serverId, killing.name)
                        tabBarViewModel.refresh(serverId)
                    }
                }
            },
            onDismiss = { killTarget = null },
        )
    }

    val pendingUrl = uiState.pendingUrlTap
    if (pendingUrl != null) {
        UrlTapConfirmDialog(
            url = pendingUrl,
            onDismiss = { viewModel.onUrlTapDismissed() },
        )
    }

    uiState.awaitingPassword?.let { info ->
        PasswordPromptDialog(
            serverLabel = info.serverLabel,
            onSubmit = { pw -> viewModel.submitPassword(info.serverId, pw) },
            onDismiss = { viewModel.cancelPasswordPrompt() },
        )
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

/**
 * The terminal + extra-keys bar vertical stack. Wraps the
 * [TerminalPane] in a [Column] so the extra-keys bar docks above the
 * IME. Volume-Down-as-Ctrl interception is attached both as a
 * Compose-level [onKeyEvent] and as a native
 * [android.view.View.OnKeyListener] on the [TerminalView] itself so it
 * fires regardless of which child holds focus at the time.
 */
@Composable
private fun ConnectedPane(
    session: TerminalSession,
    tmuxBacked: Boolean,
    onWriteToPty: (ByteArray) -> Unit,
    viewModel: TerminalViewModel,
    terminalViewRef: androidx.compose.runtime.MutableState<TerminalView?>,
    onTerminalTapShowKeyboard: () -> Unit,
) {
    val volState = remember { VolDownState() }
    val focusRequester = remember { FocusRequester() }
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    val themeId by viewModel.activeThemeId.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                handleVolDownAwareKey(
                    keyCode = event.nativeKeyEvent.keyCode,
                    native = event.nativeKeyEvent,
                    isDown = event.type == KeyEventType.KeyDown,
                    isUp = event.type == KeyEventType.KeyUp,
                    state = volState,
                    onWriteToPty = onWriteToPty,
                )
            },
    ) {
        TerminalPane(
            session = session,
            volState = volState,
            onWriteToPty = onWriteToPty,
            fontSizeSp = fontSizeSp,
            themeId = themeId,
            onFontSizeChanged = viewModel::onFontSizeChanged,
            onUrlDoubleTap = viewModel::onUrlDoubleTap,
            tmuxBacked = tmuxBacked,
            onStartCopyMode = viewModel::startCopyMode,
            onEndCopyMode = viewModel::endCopyMode,
            terminalViewRef = terminalViewRef,
            onTap = onTerminalTapShowKeyboard,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        ExtraKeysBar(
            onKey = onWriteToPty,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Shared mutable state for the Vol-Down modifier detector. Held by a
 * `remember {}` in [ConnectedPane] and read from both the Compose
 * `onKeyEvent` handler and the native `TerminalView.setOnKeyListener`.
 */
private class VolDownState {
    var pressedAtMs: Long = 0L
    var consumedAsModifier: Boolean = false
}

/**
 * Shared dispatch function for Vol-Down-as-Ctrl. Returns `true` if the
 * event was consumed.
 *
 *  - Vol-Down key-down → remember the timestamp, swallow (no volume UI).
 *  - Vol-Down key-up → if nothing consumed it as a modifier and it
 *    was held for >500ms, let the OS have it back (pass-through so the
 *    user can still lower the volume by holding alone). Otherwise
 *    swallow.
 *  - Any other key-down while Vol-Down is held → encode as Ctrl+<key>
 *    and write to the PTY; mark modifier-consumed so the Vol-Down-up
 *    doesn't fall through.
 */
private fun handleVolDownAwareKey(
    keyCode: Int,
    native: KeyEvent,
    isDown: Boolean,
    isUp: Boolean,
    state: VolDownState,
    onWriteToPty: (ByteArray) -> Unit,
): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
        return when {
            isDown -> {
                if (state.pressedAtMs == 0L) state.pressedAtMs = System.currentTimeMillis()
                true
            }
            isUp -> {
                val heldAlone = !state.consumedAsModifier
                val heldMs = System.currentTimeMillis() - state.pressedAtMs
                state.pressedAtMs = 0L
                state.consumedAsModifier = false
                !(heldAlone && heldMs > VOL_DOWN_PASSTHROUGH_MS)
            }
            else -> false
        }
    }
    if (state.pressedAtMs != 0L && isDown) {
        val extra = extraKeyFromNativeKeyCode(keyCode, native)
        if (extra != null) {
            val bytes = ExtraKeyBytes.encode(extra, ctrl = true, alt = false)
            onWriteToPty(bytes)
            state.consumedAsModifier = true
            return true
        }
    }
    return false
}

/**
 * Map an Android native keycode onto an [ExtraKey] for the
 * Vol-Down+<key>=Ctrl+<key> binding. Returns `null` for keycodes the
 * toolbar can't sensibly encode (the caller falls through to let the
 * terminal view handle them normally).
 */
private fun extraKeyFromNativeKeyCode(keyCode: Int, event: KeyEvent): ExtraKey? {
    if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        val c = ('a' + (keyCode - KeyEvent.KEYCODE_A))
        return ExtraKey.Char(c)
    }
    if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
        val c = ('0' + (keyCode - KeyEvent.KEYCODE_0))
        return ExtraKey.Char(c)
    }
    return when (keyCode) {
        KeyEvent.KEYCODE_SPACE -> ExtraKey.Char(' ')
        KeyEvent.KEYCODE_ENTER -> ExtraKey.Enter
        KeyEvent.KEYCODE_TAB -> ExtraKey.Tab
        KeyEvent.KEYCODE_ESCAPE -> ExtraKey.Escape
        else -> {
            val ch = event.unicodeChar
            if (ch != 0) ExtraKey.Char(ch.toChar()) else null
        }
    }
}

@Composable
private fun TerminalPane(
    session: TerminalSession,
    volState: VolDownState,
    onWriteToPty: (ByteArray) -> Unit,
    fontSizeSp: Int,
    themeId: String,
    onFontSizeChanged: (Int) -> Unit,
    onUrlDoubleTap: (String) -> Unit,
    tmuxBacked: Boolean,
    onStartCopyMode: () -> Unit,
    onEndCopyMode: () -> Unit,
    terminalViewRef: androidx.compose.runtime.MutableState<TerminalView?>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Single pinch-zoom state bag per composable instance. Holds the
    // "live" sp during a scale gesture so we don't have to ping
    // DataStore on every frame — the persist happens once in
    // `onScaleEnd`.
    val pinchState = remember { PinchZoomState(fontSizeSp) }

    // Task #28 — tmux-aware two-finger scrollback. The state bag holds
    // the rolling per-pointer Y anchor so we can forward arrow keys as
    // the user drags. Re-created per composition so a tab swap resets.
    val tmuxScrollState = remember { TmuxScrollState() }
    // Keep the latest [tmuxBacked] in a mutable holder so the
    // onTouchListener (installed in `factory` once) always sees the
    // current value without being recreated on every recomposition.
    val tmuxBackedRef = remember { BooleanHolder(tmuxBacked) }
    tmuxBackedRef.value = tmuxBacked

    // Track the first time the view is attached so the caller's
    // onTap handler can skip re-requesting focus if it's already
    // focused. Also clears the hoisted ref on dispose so a stale
    // reference doesn't point at a detached view.
    DisposableEffect(Unit) {
        onDispose {
            terminalViewRef.value = null
        }
    }

    Box(
        modifier = modifier.pointerInput(Unit) {
            // AndroidView swallows touches by default when the hosted
            // View has its own onTouchListener — which ours does. This
            // sibling pointer handler fires on a *completed* tap only,
            // so single-finger selection/scroll/pinch still reach
            // TerminalView. We don't consume; the View gets the tap too.
            detectTapGestures(onTap = { onTap() })
        },
    ) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = TerminalView(ctx, null).apply {
                setTerminalViewClient(MinimalTerminalViewClient)
                setTextSize(pinchState.currentSp)
                setBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                isFocusable = true
                isFocusableInTouchMode = true
                setOnKeyListener { _, keyCode, ev ->
                    handleVolDownAwareKey(
                        keyCode = keyCode,
                        native = ev,
                        isDown = ev.action == KeyEvent.ACTION_DOWN,
                        isUp = ev.action == KeyEvent.ACTION_UP,
                        state = volState,
                        onWriteToPty = onWriteToPty,
                    )
                }
            }
            view.attachSession(session)

            // Paint the initial theme before the first frame so the
            // user never sees the Termux default palette flash.
            ThemeBinder.apply(BuiltInThemes.byId(themeId), view)

            // Scale-gesture detector for pinch-to-zoom. Runs ahead of
            // TerminalView's own onTouchEvent so two fingers never
            // accidentally trigger a selection — but we still forward
            // events downstream so single-finger selection + scroll
            // keep working.
            val scaleDetector = ScaleGestureDetector(
                ctx,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val next = TerminalGestureHandler.clampFontSize(
                            (pinchState.currentSp * detector.scaleFactor).toInt(),
                        )
                        if (next != pinchState.currentSp) {
                            pinchState.currentSp = next
                            view.setTextSize(next)
                        }
                        return true
                    }

                    override fun onScaleEnd(detector: ScaleGestureDetector) {
                        onFontSizeChanged(pinchState.currentSp)
                    }
                },
            )

            val doubleTapDetector = GestureDetectorCompat(
                ctx,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        val url = TerminalGestureHandler.extractUrlAt(view, e.x, e.y)
                            ?: return false
                        onUrlDoubleTap(url)
                        return true
                    }
                },
            )

            view.setOnTouchListener { _, ev ->
                // Feed both detectors first so pinch + double-tap win
                // when they apply; otherwise fall through to Termux's
                // native onTouchEvent for single-finger selection, scroll,
                // fling, etc. We deliberately don't consume on match —
                // scaleDetector returns true for in-progress scales, which
                // would starve single-finger moves if we returned from here.
                scaleDetector.onTouchEvent(ev)
                doubleTapDetector.onTouchEvent(ev)
                // Task #28 — two-finger vertical scroll on a tmux-backed
                // tab enters tmux copy-mode and forwards drag deltas as
                // arrow keys. Consumes the event (doesn't forward to
                // Termux's onTouchEvent) so the local ring-buffer scroll
                // doesn't fight tmux's larger scrollback.
                val consumedByTmux = if (tmuxBackedRef.value) {
                    handleTmuxScrollGesture(
                        ev = ev,
                        scaleInProgress = scaleDetector.isInProgress,
                        state = tmuxScrollState,
                        onStart = onStartCopyMode,
                        onEnd = onEndCopyMode,
                        onWriteToPty = onWriteToPty,
                    )
                } else {
                    false
                }
                if (!consumedByTmux) {
                    view.onTouchEvent(ev)
                }
                true
            }

            view.requestFocus()
            // Hoist the ref so TerminalScreen's tap handler and the
            // tab bar's keyboard-toggle button can both target it.
            terminalViewRef.value = view
            view
        },
        update = { view ->
            if (view.currentSession !== session) {
                view.attachSession(session)
                ThemeBinder.apply(BuiltInThemes.byId(themeId), view)
                view.requestFocus()
            }

            // Font size may have changed out-of-band (Settings slider
            // wrote DataStore while another tab was active).
            if (pinchState.currentSp != fontSizeSp) {
                pinchState.currentSp = fontSizeSp
                view.setTextSize(fontSizeSp)
            }

            // Repaint the palette on every theme change. apply() is
            // cheap (~20 int writes + invalidate).
            ThemeBinder.apply(BuiltInThemes.byId(themeId), view)

            // Keep the hoisted ref in sync in case a new AndroidView
            // instance was created (e.g. after a configuration change).
            terminalViewRef.value = view
        },
    )
    }
}

/** Live font-size cache for the pinch-zoom gesture. */
private class PinchZoomState(var currentSp: Int)

/**
 * Mutable holder so the long-lived [android.view.View.OnTouchListener]
 * installed once in the AndroidView factory always sees the current
 * value of a Compose prop. Re-assigning the value in the outer body
 * costs nothing (no recomposition).
 */
private class BooleanHolder(var value: Boolean)

/**
 * Per-tab rolling state for the Task #28 two-finger scroll gesture.
 *
 *  - [active]: `true` once we've seen a 2-pointer ACTION_MOVE that
 *    crossed [TMUX_SCROLL_SLOP_PX] of vertical travel. Stays true until
 *    the gesture ends (last pointer up or ACTION_CANCEL).
 *  - [lastY]: average Y of the two pointers at the last frame we
 *    emitted an arrow key for. Drag deltas smaller than
 *    [TMUX_SCROLL_STEP_PX] are accumulated, not forwarded.
 *  - [anchorY]: initial average Y on the first 2-pointer DOWN. Used
 *    to decide "first drag direction" for the slop check.
 */
private class TmuxScrollState {
    var active: Boolean = false
    var anchorY: Float = 0f
    var lastY: Float = 0f
}

/**
 * Task #28 — handle a raw [MotionEvent] as part of the two-finger
 * vertical-scroll gesture. Returns `true` when the event belongs to
 * the gesture (and the caller should *not* forward it to
 * [TerminalView.onTouchEvent]).
 *
 * Flow:
 *  1. Two fingers land → remember anchor Y; don't enter copy-mode yet
 *     (the user might be pinching).
 *  2. As they drag vertically past [TMUX_SCROLL_SLOP_PX] without a
 *     pinch, flip [TmuxScrollState.active], fire [onStart] so the VM
 *     sends Ctrl-B `[`.
 *  3. Each subsequent [TMUX_SCROLL_STEP_PX] of drag emits one arrow
 *     key (up = scroll history up, down = scroll history down).
 *  4. Pointer count drops to <2 or ACTION_CANCEL → fire [onEnd] so
 *     the VM sends `q` to quit copy-mode.
 */
private fun handleTmuxScrollGesture(
    ev: MotionEvent,
    scaleInProgress: Boolean,
    state: TmuxScrollState,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onWriteToPty: (ByteArray) -> Unit,
): Boolean {
    val pointerCount = ev.pointerCount
    when (ev.actionMasked) {
        MotionEvent.ACTION_POINTER_DOWN -> {
            if (pointerCount == 2) {
                val avgY = (ev.getY(0) + ev.getY(1)) / 2f
                state.anchorY = avgY
                state.lastY = avgY
                // Don't claim the event yet — the user might pinch, and
                // the scale detector already saw the ACTION_POINTER_DOWN.
            }
            return false
        }
        MotionEvent.ACTION_MOVE -> {
            if (scaleInProgress) {
                // Pinch wins — abandon any in-progress scrollback and
                // bail out of copy-mode if we entered it.
                if (state.active) {
                    state.active = false
                    onEnd()
                }
                return false
            }
            if (pointerCount < 2) return state.active // still consume if active
            val avgY = (ev.getY(0) + ev.getY(1)) / 2f
            if (!state.active) {
                if (kotlin.math.abs(avgY - state.anchorY) > TMUX_SCROLL_SLOP_PX) {
                    state.active = true
                    state.lastY = avgY
                    onStart()
                    return true
                }
                return false
            }
            val delta = avgY - state.lastY
            val steps = (delta / TMUX_SCROLL_STEP_PX).toInt()
            if (steps != 0) {
                state.lastY += steps * TMUX_SCROLL_STEP_PX
                val bytes = if (steps > 0) TerminalGestureHandler.ARROW_UP
                else TerminalGestureHandler.ARROW_DOWN
                repeat(kotlin.math.abs(steps)) { onWriteToPty(bytes) }
            }
            return true
        }
        MotionEvent.ACTION_POINTER_UP,
        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> {
            if (state.active) {
                state.active = false
                onEnd()
                return true
            }
            return false
        }
        else -> return state.active
    }
}

private const val TMUX_SCROLL_SLOP_PX = 24f
private const val TMUX_SCROLL_STEP_PX = 32f

/**
 * Placeholder client for TerminalView. Task #17 adds real gesture
 * handling (pinch-zoom, two-finger scroll, long-press select, URL
 * tap). For now we return sane defaults and forward nothing.
 */
private object MinimalTerminalViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3.0f)
    override fun onSingleTapUp(e: MotionEvent?) { /* focus is managed by the view itself */ }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?): Boolean = false
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

private const val VOL_DOWN_PASSTHROUGH_MS = 500L

/**
 * Prompts the user for a password when the server row uses password auth
 * but the in-memory cache is empty. The entered value is cached by
 * [TerminalViewModel.submitPassword] for the process lifetime; a kill or
 * reinstall clears it.
 */
@Composable
private fun PasswordPromptDialog(
    serverLabel: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password for $serverLabel") },
        text = {
            Column {
                Text("Your password is not stored — it's kept in memory for this session only.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (visible) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank(),
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
