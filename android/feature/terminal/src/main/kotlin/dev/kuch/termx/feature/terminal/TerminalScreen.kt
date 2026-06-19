package dev.kuch.termx.feature.terminal

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.MoshRemoteTerminalSession
import com.termux.terminal.RemoteTerminalSession
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.kuch.termx.core.domain.theme.Sorcerer
import dev.kuch.termx.feature.terminal.gestures.TerminalGestureHandler
import dev.kuch.termx.feature.terminal.gestures.UrlTapConfirmDialog
import dev.kuch.termx.feature.terminal.keys.ExtraKey
import dev.kuch.termx.feature.terminal.keys.ExtraKeyBytes
import dev.kuch.termx.feature.terminal.keys.ExtraKeysBar
import dev.kuch.termx.feature.ptt.PttState
import dev.kuch.termx.feature.ptt.PttSurface
import dev.kuch.termx.feature.ptt.rememberPttStartAction
import java.util.UUID

/**
 * The app's only terminal surface.
 *
 * A single plain login shell per connection. Layout top-to-bottom:
 *
 *  1. The active session's `TerminalView` via [AndroidView], which
 *     fills the available space.
 *  2. [ExtraKeysBar] docked above the soft keyboard.
 *
 * The transport (sshj [dev.kuch.termx.libs.sshnative.SshSession] /
 * mosh session plus the single open
 * [dev.kuch.termx.libs.sshnative.PtyChannel]) is owned by the
 * process-wide [dev.kuch.termx.feature.terminal.connection.ConnectionManager];
 * [TerminalViewModel] is a binder that exposes the currently-bound
 * emulator as [TerminalUiState.activeSession]. Sessions OUTLIVE this
 * screen: back/leaving just unbinds the view, and the only in-screen
 * way to end a session is the explicit Disconnect overlay action.
 *
 * HOSTING (Task #47): this screen is no longer a nav destination. It is
 * composed inside [TerminalSheetHost]'s draggable overlay, which passes
 * the maximized server id (from `TerminalSheetState`) and a per-server
 * keyed [TerminalViewModel]. Back/minimize hides the sheet; the
 * `statusBarsPadding` calls below resolve to zero inside the sheet (the
 * host consumes the status-bar inset after sizing its drag handle) but
 * are kept so the screen stays correct if ever hosted full-window again.
 */
@Composable
fun TerminalScreen(
    serverId: UUID? = null,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    // Share the PTT view-model instance with PttSurface (which grabs
    // its own via hiltViewModel() too — same NavBackStackEntry → same
    // VM). We need a handle here because ExtraKeysBar must stay
    // decoupled from :feature:ptt (module cycle), so this screen wires
    // plain lambdas into the bar for both the keyboard chip's
    // long-press compose-text hop (Issue 1, v1.1.13) and the
    // hold-to-record mic that replaced the floating FAB.
    val pttViewModel: dev.kuch.termx.feature.ptt.PttViewModel = hiltViewModel()
    val pttState by pttViewModel.state.collectAsStateWithLifecycle()
    // Press-start half of the mic gesture: Idle gating + RECORD_AUDIO
    // permission flow live inside :feature:ptt (see the helper's KDoc).
    val startPttRecording = rememberPttStartAction(pttViewModel)
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    // Bind-if-alive (Task #43): the manager returns a live slot
    // untouched, so re-entering this screen for a connected server
    // rebinds the view to the existing emulator (scrollback intact)
    // instead of redialing. There is deliberately NO dispose-side
    // disconnect — leaving the screen keeps the session running
    // (minimize semantics); ending it is the explicit Disconnect
    // action in the corner overlay below.
    LaunchedEffect(serverId) {
        viewModel.connect(serverId)
    }

    // Hoisted reference to the currently-mounted [TerminalView] so the
    // tap-to-focus handler here and the extra-keys bar's keyboard-toggle
    // button can both target it. Assigned from the AndroidView factory
    // block in [TerminalPane]; nulled on dispose.
    val terminalViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val context = LocalContext.current
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    // First-show-on-entry — once the SSH session reaches Connected the
    // TerminalView is attached and ready. Request focus and pop the IME
    // so the user can type immediately without an extra tap.
    //
    // This is THE single focus claim for the terminal (the 2026-06-11
    // tap-before-type fix). It also covers re-maximize: TerminalSheetHost
    // wraps the sheet in key(serverId) and unmounts it on minimize, so
    // every maximize recomposes this screen from scratch and this effect
    // relaunches even when status is already Connected.
    LaunchedEffect(uiState.status) {
        if (uiState.status == TerminalUiState.Status.Connected) {
            // Wait one frame so composition (and the AndroidView
            // attach/layout pass) settles before claiming focus —
            // requesting focus mid-composition let the IME bind against
            // a view the window was still rearranging, leaving the
            // keyboard visible but its input connection dead.
            withFrameNanos { }
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

    // Focus RESTORE (Task #50) — the counterpart to
    // [showKeyboardOnTerminalTap] for the secondary focus claimants
    // (the PTT card's text field, the URL/password AlertDialogs). They
    // take focus legitimately while open but release it to nowhere on
    // dismissal, leaving keystrokes targeting nothing until a repair
    // tap. Deliberately requestFocus ONLY — no showSoftInput. A tap on
    // the terminal is the user saying "I want to type" (force the IME
    // up); a card/dialog closing says nothing about IME intent, so we
    // re-point key routing at the terminal and leave IME visibility
    // exactly as the user had it: if the keyboard is up (e.g. the PTT
    // compose field popped it), it rebinds to the terminal and typing
    // continues; if the user had it hidden, it stays hidden.
    val restoreTerminalFocus: () -> Unit = {
        terminalViewRef.value?.requestFocus()
    }

    // PTT-card dismissal → focus return. The Ready card's BasicTextField
    // is a legitimate focus claimant while open (the keyboard-chip
    // long-press compose flow DEPENDS on its auto-focus — load-bearing,
    // do not disturb), but on send/✕/error-dismiss the card vanishes
    // and focus dies with it. :feature:ptt must not know about terminal
    // types (module cycle), so instead of widening PttSurface's API
    // with an onDismissed callback, the host observes the shared VM's
    // state machine and reacts to the transitions that mean "card
    // closed": Ready → Idle (Send → consumeSend, ✕ → dismiss) and
    // Error → Idle (Dismiss → dismiss) — both pinned in
    // PttViewModelTest. Transitions OUT of Idle (the compose-card
    // entry, Idle → Ready(requestFocus=true)) can never match, so the
    // card's auto-focus path is untouched. Recording → Idle is
    // deliberately excluded: RecordingBody has no focusable field, so
    // the terminal never lost focus on that path.
    LaunchedEffect(pttViewModel) {
        var previous: PttState = pttViewModel.state.value
        pttViewModel.state.collect { current ->
            val cardReleasedFocus = current is PttState.Idle &&
                (previous is PttState.Ready || previous is PttState.Error)
            if (cardReleasedFocus) restoreTerminalFocus()
            previous = current
        }
    }

    val onToggleKeyboard: () -> Unit = {
        terminalViewRef.value?.let { v ->
            // `isFocused` isn't a reliable IME-visibility signal: the
            // TerminalView keeps focus across hide/show, so checking it
            // made the toggle latch to "hide" after the first tap. Read
            // the actual IME inset from WindowInsets instead.
            val imeVisible = ViewCompat.getRootWindowInsets(v)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (imeVisible) {
                imm.hideSoftInputFromWindow(v.windowToken, 0)
            } else {
                v.requestFocus()
                imm.showSoftInput(v, 0)
            }
        }
    }

    // PTY-write-failure snackbar host. The TerminalViewModel surfaces
    // silent send failures here (Issue 2A, v1.1.13); tapping
    // "Reconnect" re-establishes the SSH session for the active server.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(serverId, snackbarHostState) {
        viewModel.writeErrors.collect { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg,
                actionLabel = "Reconnect",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && serverId != null) {
                viewModel.connect(serverId)
            }
        }
    }

    // One-shot transport notices ("Connected via SSH — mosh unavailable:
    // <reason>"). Informational only — the connection IS up, just over
    // SSH instead of the requested mosh — so no action button; the
    // persistent subtitle above the terminal carries the detail.
    LaunchedEffect(snackbarHostState) {
        viewModel.transportNotices.collect { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short,
            )
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Truthful-transport subtitle: rendered where the "via mosh"
            // badge would sit, only when a mosh-requested connection
            // actually came up over SSH. Persistent (unlike the one-shot
            // snackbar) so the user can still see WHY after the snackbar
            // is gone.
            val fallbackReason = uiState.transportFallbackReason
            if (uiState.status == TerminalUiState.Status.Connected &&
                !uiState.moshBacked && fallbackReason != null
            ) {
                TransportFallbackSubtitle(reason = fallbackReason)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars),
                    ),
            ) {
                when (uiState.status) {
                    TerminalUiState.Status.Idle,
                    TerminalUiState.Status.Connecting -> ConnectingPane()
                    TerminalUiState.Status.Connected -> {
                        val active = uiState.activeSession
                        if (active != null) {
                            ConnectedPane(
                                session = active,
                                onWriteToPty = viewModel::writeToPty,
                                viewModel = viewModel,
                                terminalViewRef = terminalViewRef,
                                onTerminalTapShowKeyboard = showKeyboardOnTerminalTap,
                                onToggleKeyboard = onToggleKeyboard,
                                onComposeText = pttViewModel::composeText,
                                onPttPressStart = startPttRecording,
                                onPttRelease = pttViewModel::stopRecordingAndTranscribe,
                                onPttCancel = {
                                    // cancelRecording resets to Idle from ANY
                                    // state (test-pinned semantics), which
                                    // would clobber a Ready transcript if the
                                    // user merely brushed the always-composed
                                    // mic. Gate it the same way the status
                                    // card's Cancel button does, reading the
                                    // VM's StateFlow directly so a press-
                                    // start→cancel race inside one frame
                                    // can't slip past a stale snapshot.
                                    if (pttViewModel.state.value is PttState.Recording) {
                                        pttViewModel.cancelRecording()
                                    }
                                },
                                pttRecording = pttState is PttState.Recording,
                            )
                            // Push-to-talk status overlay (Recording /
                            // Transcribing / Ready pill) expands full width
                            // at the bottom of the terminal area. The mic
                            // itself lives in ExtraKeysBar's pinned trailing
                            // area — the floating FAB is gone (task #39).
                            PttSurface(
                                // Two-phase submit (Task #53): text →
                                // transport-sized delay → lone CR, with
                                // bracketed-paste wrapping when the
                                // remote app enabled DECSET 2004. NOT
                                // writeToPty(encodePttPayload(...)) —
                                // one buffer carrying text+"\r" is the
                                // exact shape Claude Code's >=64-char
                                // stdin chunks refuse to submit. See
                                // ConnectionManager.buildSubmitSequence.
                                onSend = { text -> viewModel.submitLine(text) },
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
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars),
                    ),
            )
            // Explicit disconnect affordance (Task #43). With the
            // lifecycle flip, back/leaving keeps the session running —
            // ending it is a deliberate act, so a dedicated action must
            // exist on the screen. The terminal is intentionally
            // chrome-less (no top bar), so this rides as a small
            // overlay in the status-bar corner; only shown while
            // Connected (Error/Disconnected panes have their own
            // actions, and there is nothing to end mid-Connecting that
            // back-out doesn't already leave harmless).
            if (uiState.status == TerminalUiState.Status.Connected) {
                IconButton(
                    onClick = { viewModel.disconnect() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = "Disconnect",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val pendingUrl = uiState.pendingUrlTap
    if (pendingUrl != null) {
        UrlTapConfirmDialog(
            url = pendingUrl,
            // The AlertDialog is its own window and takes focus while
            // open; closing it hands focus back to nothing, so we
            // re-point it at the terminal (Task #50). One path suffices:
            // the dialog funnels Open through the same onDismiss after
            // firing the browser intent, so Cancel, scrim/back, and
            // Open all land here. On Open the browser covers us anyway;
            // when the user returns, the window regains focus with the
            // TerminalView already its focus target.
            onDismiss = {
                viewModel.onUrlTapDismissed()
                restoreTerminalFocus()
            },
        )
    }

    uiState.awaitingPassword?.let { info ->
        PasswordPromptDialog(
            serverLabel = info.serverLabel,
            // No focus restore on confirm — submitPassword retries the
            // connect, so status walks Disconnected(AwaitingPassword) →
            // Connecting → Connected, and the LaunchedEffect(uiState
            // .status) above re-fires on the change TO Connected,
            // granting focus AND popping the IME (the right call after
            // a fresh connect). A restore here would race that effect
            // against a TerminalView that isn't mounted yet.
            onSubmit = { pw -> viewModel.submitPassword(info.serverId, pw) },
            // Cancel keeps status at Disconnected, so the Connected
            // effect never fires — restore explicitly. While the prompt
            // is up there is normally no mounted TerminalView
            // (AwaitingPassword maps to status Disconnected, so
            // ConnectedPane is gone and the ref is null), making this a
            // defensive no-op today; it matters if the prompt ever
            // overlays a live terminal (e.g. a future re-auth flow).
            onDismiss = {
                viewModel.cancelPasswordPrompt()
                restoreTerminalFocus()
            },
        )
    }
}

/**
 * One-line "via SSH" subtitle shown above the terminal when the server
 * requested mosh but the connection fell back to plain SSH
 * ([TerminalUiState.transportFallbackReason] non-null). The reason
 * strings are the short, user-actionable ones minted in
 * [TerminalViewModel] ("no response in time — slow start or blocked
 * UDP", "mosh-server not installed", …). M3 tokens only — the muted
 * onSurfaceVariant keeps it legible without competing with terminal
 * content.
 */
@Composable
private fun TransportFallbackSubtitle(reason: String) {
    Text(
        text = "via SSH — mosh unavailable: $reason",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
    )
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
 * IME. Volume-Down-as-Ctrl interception lives solely on the
 * [TerminalView]'s native [android.view.View.OnKeyListener] (installed
 * in [TerminalPane]'s factory) — see the WHY on the [Column] below for
 * why this composable must NOT carry a focus/key path of its own.
 */
@Composable
private fun ConnectedPane(
    session: TerminalSession,
    onWriteToPty: (ByteArray) -> Unit,
    viewModel: TerminalViewModel,
    terminalViewRef: androidx.compose.runtime.MutableState<TerminalView?>,
    onTerminalTapShowKeyboard: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onComposeText: () -> Unit,
    onPttPressStart: () -> Unit,
    onPttRelease: () -> Unit,
    onPttCancel: () -> Unit,
    pttRecording: Boolean,
) {
    val volState = remember { VolDownState() }
    val fontSizeSp by viewModel.fontSizeSp.collectAsStateWithLifecycle()
    // Hoisted out of ExtraKeysBar so the IME-key path below can read
    // the sticky CTRL/ALT state. Without this hoist, tapping CTRL on
    // the bar then typing 'c' on the Android keyboard sends a plain
    // 'c' — the bar's local state never reaches TerminalView's key
    // handler. Issue 3, v1.1.13.
    val extraKeysState = dev.kuch.termx.feature.terminal.keys.rememberExtraKeysState()

    // WHY no focusRequester/focusable/onKeyEvent on this Column: a
    // Compose-side focusable here steals view-focus from the embedded
    // TerminalView after every sheet open — Compose effects dispatch in
    // tree order, so this pane's focus claim ran AFTER the screen-level
    // Connected effect and won, AndroidComposeView reclaimed view-focus
    // from the TerminalView, and the IME restarted its input connection
    // against the ComposeView (no editable target): keyboard visible but
    // typing dead until a repair tap (the 2026-06-11 tap-before-type
    // bug). Vol-Down-as-Ctrl does not need a Compose key path either —
    // the TerminalView's own setOnKeyListener (TerminalPane factory)
    // runs the same [handleVolDownAwareKey] whenever the view holds
    // focus, which the fix guarantees is the steady state. ONE source
    // of truth: the view.
    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        TerminalPane(
            session = session,
            volState = volState,
            extraKeysState = extraKeysState,
            onWriteToPty = onWriteToPty,
            fontSizeSp = fontSizeSp,
            onFontSizeChanged = viewModel::onFontSizeChanged,
            onUrlDoubleTap = viewModel::onUrlDoubleTap,
            terminalViewRef = terminalViewRef,
            onTap = onTerminalTapShowKeyboard,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        // Rendered unconditionally while connected — the bar (and its
        // mic) must remain composed through PttState.Recording; see the
        // INVARIANT on MicKey in ExtraKeysBar.
        ExtraKeysBar(
            onKey = onWriteToPty,
            onToggleKeyboard = onToggleKeyboard,
            onComposeText = onComposeText,
            onPttPressStart = onPttPressStart,
            onPttRelease = onPttRelease,
            onPttCancel = onPttCancel,
            pttRecording = pttRecording,
            state = extraKeysState,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Mutable state for the Vol-Down modifier detector. Held by a
 * `remember {}` in [ConnectedPane] and read from the native
 * `TerminalView.setOnKeyListener` (the single Vol-Down key path — see
 * the WHY on [ConnectedPane]'s Column).
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
    extraKeysState: dev.kuch.termx.feature.terminal.keys.ExtraKeysState,
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
    // Sticky CTRL/ALT from the extra-keys bar applies to the next IME
    // (or hardware) key press too. Without this branch, the bar's
    // sticky state is functionally only useful for keys tapped on the
    // bar itself — there's no a-z chip, so Ctrl+letter never worked.
    // Issue 3, v1.1.13. Only fires on the down event; the up event is
    // unmodified so the terminal doesn't see a phantom release.
    if (isDown && (extraKeysState.ctrlActive || extraKeysState.altActive)) {
        val extra = extraKeyFromNativeKeyCode(keyCode, native)
        if (extra != null) {
            val bytes = ExtraKeyBytes.encode(
                extra,
                ctrl = extraKeysState.ctrlActive,
                alt = extraKeysState.altActive,
            )
            if (bytes.isNotEmpty()) onWriteToPty(bytes)
            extraKeysState.resetOneShots()
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
    extraKeysState: dev.kuch.termx.feature.terminal.keys.ExtraKeysState,
    onWriteToPty: (ByteArray) -> Unit,
    fontSizeSp: Int,
    onFontSizeChanged: (Int) -> Unit,
    onUrlDoubleTap: (String) -> Unit,
    terminalViewRef: androidx.compose.runtime.MutableState<TerminalView?>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Single pinch-zoom state bag per composable instance. Holds the
    // "live" sp during a scale gesture so we don't have to ping
    // DataStore on every frame — the persist happens once in
    // `onScaleEnd`.
    val pinchState = remember { PinchZoomState(fontSizeSp) }

    // Track the first time the view is attached so the caller's
    // onTap handler can skip re-requesting focus if it's already
    // focused. Also clears the hoisted ref on dispose so a stale
    // reference doesn't point at a detached view.
    //
    // `session` is in the key list so a tab swap releases the view
    // binding on the OUTGOING session's client before the new session's
    // factory / update binds it to the new view; otherwise we'd leave a
    // detached TerminalView pinned in the background tab's client and
    // never repaint the active one.
    DisposableEffect(session) {
        onDispose {
            unbindViewFromSession(session)
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
                setTerminalViewClient(MinimalTerminalViewClient(extraKeysState))
                setTextSize(pinchState.currentSp)
                setBackgroundColor(dev.kuch.termx.core.domain.theme.Sorcerer.BACKGROUND.toInt())
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
                        extraKeysState = extraKeysState,
                        onWriteToPty = onWriteToPty,
                    )
                }
            }
            view.attachSession(session)

            // Sorcerer is already painted into the freshly-constructed
            // emulator via the static default installed in
            // TermxApplication.onCreate. No need (and no use) for a
            // ThemeBinder.apply call here — the View has not been
            // laid out yet, mEmulator is null, the apply would no-op.
            // The factory's earlier setBackgroundColor(Sorcerer.BACKGROUND)
            // covers the View chrome.

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
                // when they apply, then always fall through to Termux's
                // native onTouchEvent. That handler drives the emulator's
                // own transcript ring buffer: a vertical drag (one or two
                // fingers) reaches GestureAndScaleRecognizer.onScroll →
                // TerminalView.doScroll, which walks `mTopRow` back
                // through the scrollback, and a flick feeds onFling.
                // We deliberately don't consume on match — scaleDetector
                // returns true for in-progress scales, which would starve
                // single-finger moves if we returned from here.
                scaleDetector.onTouchEvent(ev)
                doubleTapDetector.onTouchEvent(ev)
                view.onTouchEvent(ev)
                true
            }

            view.requestFocus()
            // Hoist the ref so TerminalScreen's tap handler and the
            // tab bar's keyboard-toggle button can both target it.
            terminalViewRef.value = view
            // Wire the view into the active session's client so remote
            // bytes landing in the emulator fire SshSessionClient
            // .onTextChanged → view.onScreenUpdated → invalidate().
            // Without this, nothing invalidates the view after the first
            // frame and every update waits for a full activity cycle.
            bindViewToSession(session, view)
            view
        },
        update = { view ->
            if (view.currentSession !== session) {
                view.attachSession(session)
                view.requestFocus()
                // Tab swap: unbind the previous session's client and
                // bind the new one. The DisposableEffect(session) above
                // handles the outgoing side; this side re-arms on the
                // next active session.
                //
                // Sorcerer is already inside the new emulator's mColors
                // (TerminalColors() constructor inherits from the
                // ThemeBinder-installed static defaults), so no
                // palette re-apply is needed on tab swap.
                bindViewToSession(session, view)
            }

            // Font size may have changed out-of-band (Settings slider
            // wrote DataStore while another tab was active).
            if (pinchState.currentSp != fontSizeSp) {
                pinchState.currentSp = fontSizeSp
                view.setTextSize(fontSizeSp)
            }


            // Keep the hoisted ref in sync in case a new AndroidView
            // instance was created (e.g. after a configuration change).
            terminalViewRef.value = view
            // Re-assert the binding on every update pass — harmless if
            // already set, recovery path if the view was detached +
            // re-attached by the Compose host.
            bindViewToSession(session, view)
        },
    )
    }
}

/**
 * Wire [view] into [session]'s [SshSessionClient] so that every
 * `TerminalSession.notifyScreenUpdate()` inside the emulator translates
 * into `view.onScreenUpdated()` on the UI thread. See
 * [SshSessionClient.onTextChanged] for the contract; this helper is the
 * only place that ever assigns the reference.
 */
private fun bindViewToSession(session: TerminalSession, view: TerminalView) {
    when (session) {
        is RemoteTerminalSession -> session.sessionClient()?.terminalView = view
        is MoshRemoteTerminalSession -> session.sessionClient()?.terminalView = view
    }
}

/**
 * Counterpart to [bindViewToSession]. Clears the client's view ref so
 * the Compose host doesn't leak a detached [TerminalView] after a tab
 * swap or composable dispose.
 */
private fun unbindViewFromSession(session: TerminalSession) {
    when (session) {
        is RemoteTerminalSession -> session.sessionClient()?.terminalView = null
        is MoshRemoteTerminalSession -> session.sessionClient()?.terminalView = null
    }
}

/** Live font-size cache for the pinch-zoom gesture. */
private class PinchZoomState(var currentSp: Int)

/**
 * Minimal [TerminalViewClient] for the embedded Termux view.
 *
 * Pre-v1.1.14 this was a stateless `object`. v1.1.14 makes it a class
 * carrying the hoisted [ExtraKeysState] so the IME-commit path inside
 * [com.termux.view.TerminalView.sendTextToTerminal] can read the bar's
 * sticky CTRL/ALT (Bug A from the v1.1.13 grilling). Without this,
 * tapping CTRL on the bar then typing 'b' on Gboard sends a plain 'b'
 * — the modifier never reaches commitText-delivered letters.
 */
private class MinimalTerminalViewClient(
    private val extraKeysState: dev.kuch.termx.feature.terminal.keys.ExtraKeysState,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale.coerceIn(0.5f, 3.0f)
    override fun onSingleTapUp(e: MotionEvent?) { /* focus is managed by the view itself */ }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    // Vendored Termux [TerminalViewClient] hook fired on text-selection
    // start/stop (the fork calls it with isSelectingText()); nothing to
    // do here.
    override fun copyModeChanged(selecting: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false
    override fun readControlKey(): Boolean = false
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun readStickyCtrl(): Boolean = extraKeysState.ctrlActive
    override fun readStickyAlt(): Boolean = extraKeysState.altActive
    override fun consumeStickyModifiers() = extraKeysState.resetOneShots()
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
 * Pattern matching every Unicode codepoint that a downstream terminal
 * is plausibly going to render as a line break visually but that the
 * shell's `accept-line` keybinding does NOT recognize:
 *
 *  - `\r` (CR, U+000D) — kept as the canonical Enter byte; not a leak.
 *  - `\n` (LF, U+000A) — original target; bash readline accepts it but
 *    raw-mode shells over mosh render the literal newline glyph
 *    without submitting (v1.1.12 fix).
 *  - `` (NEL, Next Line) — IBM legacy; some terminals advance.
 *  - `` (VT, Vertical Tab) — xterm interprets as cursor-down-1.
 *  - `` (FF, Form Feed) — xterm interprets as cursor-down-1.
 *  - ` ` (LSEP, Line Separator) — Word documents emit this for
 *    soft line breaks; clipboard pastes deliver it; LLM transcribers
 *    occasionally pick it for sentence boundaries.
 *  - ` ` (PSEP, Paragraph Separator) — same, but for paragraph
 *    boundaries.
 *
 * Empirical: a probe test (`PttPayloadProbeTest`) confirmed the
 * pre-v1.3.3 encoder leaked all of these untouched into the byte
 * stream. With the widened regex below, any run of mixed line-break
 * codepoints collapses to a single `\r` — the only thing readline +
 * mosh agree means "submit this line."
 *
 * `internal` (not private) since Task #53: the live PTT submit path —
 * `ConnectionManager.sanitizePtySubmitText` — reuses this exact regex
 * so the two layers can never drift on what counts as a line break.
 */
internal val ANY_LINE_BREAK = Regex("[\\r\\n\\u0085\\u000B\\u000C\\u2028\\u2029]+")

/**
 * Convert a PTT transcript / typed draft into the bytes a PTY expects.
 *
 *  - Every run of mixed line-break codepoints (see [ANY_LINE_BREAK])
 *    collapses to a single `\r` (carriage-return). Real keyboards emit
 *    `\r` for Enter, and bash/readline + zsh/zle in raw mode bind `\r`
 *    to accept-line; the other codepoints either don't trigger
 *    accept-line, or render as line-break glyphs without submitting.
 *  - When [appendNewline] is true (the Send button), a trailing `\r`
 *    is appended so the shell executes the last line. Insert leaves
 *    the cursor mid-line so the user keeps editing in the shell.
 *
 * Pure function — no Android, no Compose. Lives at file scope so the
 * unit-test suite (`PttPayloadTest`) can hammer it without spinning
 * up a Robolectric runtime.
 *
 * NO LONGER the live Send path (Task #53): PTT Send goes through
 * `TerminalViewModel.submitLine` → `ConnectionManager.submitLine`,
 * because appending the CR to the SAME buffer is exactly what broke
 * Claude Code submits (its stdin tokenizer only treats `\r` as the
 * Enter KEY in chunks <64 chars — see `buildSubmitSequence`). This
 * function and its tests (`PttPayloadTest`/`PttPayloadProbeTest`)
 * stay as the pinned reference for the [ANY_LINE_BREAK] collapse the
 * submit path's sanitizer shares.
 */
internal fun encodePttPayload(text: String, appendNewline: Boolean): ByteArray {
    val normalized = ANY_LINE_BREAK.replace(text, "\r")
    val payload = if (appendNewline) "$normalized\r" else normalized
    return payload.toByteArray()
}

/**
 * Prompts the user for a password when the server row uses password auth
 * but neither the vault nor the in-memory cache has a value. The entered
 * value is persisted to the app's sandboxed vault by
 * [TerminalViewModel.submitPassword] so cold-start reconnects don't
 * re-prompt.
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
                Text("Saved to this app's private, biometric-gated vault once you connect.")
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
