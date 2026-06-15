/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.keys

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val LONG_PRESS_REPEAT_MS = 60L

/** Fixed key dimensions — keys keep their size regardless of screen width. */
private val KEY_WIDTH = 52.dp
private val KEY_HEIGHT = 40.dp

/** Width of the gradient fade hinting at off-screen keys. */
private val EDGE_FADE_WIDTH = 24.dp

/**
 * Extra-keys toolbar rendered above the soft keyboard.
 *
 * One horizontally-scrollable row of fixed-width keys (Moshi-style) with
 * a gradient fade at each edge hinting at more keys; the PTT mic and the
 * keyboard chip are pinned outside the scrollable area on the right.
 * Sticky CTRL/ALT; volume-down-as-Ctrl is handled by [TerminalScreen]
 * upstream. Every tap fires a [HapticFeedbackConstants.KEYBOARD_TAP].
 *
 * The double-tap → Locked promotion is implemented with a per-modifier
 * timestamp: a second tap within [DOUBLE_TAP_WINDOW_MS] upgrades
 * OneShot → Locked.
 *
 * Arrow / nav keys fire on RELEASE (not touch-down) so a drag that
 * scrolls the bar never leaks a keystroke, and they support
 * long-press repeat: a stationary hold past the platform long-press
 * timeout re-fires every [LONG_PRESS_REPEAT_MS] until the finger
 * lifts. See [KeyButton] for the gesture rationale.
 *
 * The mic is wired through plain lambdas ([onPttPressStart] /
 * [onPttRelease] / [onPttCancel] + the [pttRecording] tint flag) so this
 * module never depends on `:feature:ptt` — the host screen resolves the
 * PTT view-model and hands the callbacks down, mirroring [onComposeText].
 */
@Composable
fun ExtraKeysBar(
    onKey: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    onToggleKeyboard: () -> Unit = {},
    onComposeText: () -> Unit = {},
    onPttPressStart: () -> Unit = {},
    onPttRelease: () -> Unit = {},
    onPttCancel: () -> Unit = {},
    pttRecording: Boolean = false,
    state: ExtraKeysState = rememberExtraKeysState(),
    layout: List<ExtraKey> = ExtraKeysLayout.KEYS,
) {
    val view = LocalView.current

    // Per-modifier timestamps for the double-tap window.
    var lastCtrlTapMs by remember { mutableLongStateOf(0L) }
    var lastAltTapMs by remember { mutableLongStateOf(0L) }

    val handleTap: (ExtraKey) -> Unit = handle@{ key ->
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (key) {
            ExtraKey.Ctrl -> {
                val now = System.currentTimeMillis()
                if (state.ctrl == ModifierState.OneShot &&
                    now - lastCtrlTapMs < DOUBLE_TAP_WINDOW_MS
                ) {
                    state.lockCtrl()
                } else {
                    state.tapCtrlOnce()
                }
                lastCtrlTapMs = now
                return@handle
            }
            ExtraKey.Alt -> {
                val now = System.currentTimeMillis()
                if (state.alt == ModifierState.OneShot &&
                    now - lastAltTapMs < DOUBLE_TAP_WINDOW_MS
                ) {
                    state.lockAlt()
                } else {
                    state.tapAltOnce()
                }
                lastAltTapMs = now
                return@handle
            }
            else -> {
                val bytes = ExtraKeyBytes.encode(key, state.ctrlActive, state.altActive)
                if (bytes.isNotEmpty()) onKey(bytes)
                state.resetOneShots()
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScrollableKeyRow(
                keys = layout,
                state = state,
                onTap = handleTap,
                onRepeatKey = { key ->
                    // Used by arrow keys while held. Bypasses haptic per
                    // repeat — only the initial press hapticks.
                    val bytes = ExtraKeyBytes.encode(key, state.ctrlActive, state.altActive)
                    if (bytes.isNotEmpty()) onKey(bytes)
                },
                modifier = Modifier.weight(1f),
            )
            // Pinned (non-scrolling) action area on the right: the
            // hold-to-record mic docked next to the keyboard chip,
            // Moshi-style.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MicKey(
                    recording = pttRecording,
                    onPressStart = onPttPressStart,
                    onRelease = onPttRelease,
                    onCancel = onPttCancel,
                )
                // Tap toggles the IME; long-press opens an empty PTT
                // compose card so the user can type a command in a
                // friendlier text field than the raw terminal. v1.1.13
                // first tried Modifier.combinedClickable here but
                // onClick failed to fire on the user's device while
                // onLongClick worked — root cause unclear, but
                // detectTapGestures is the more direct primitive and
                // avoids whatever combinedClickable quirk we hit.
                // Long-press is a no-op when PTT is busy with
                // anything other than Idle (see PttViewModel.composeText).
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(40.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    onToggleKeyboard()
                                },
                                onLongPress = {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onComposeText()
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = "toggle keyboard",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Hold-to-record push-to-talk mic, pinned next to the keyboard chip.
 * Press starts a recording (haptic on the down), release transcribes,
 * a cancelled gesture aborts. A quick tap behaves exactly like the old
 * FAB's quick tap: the recording starts and immediately stops, and
 * PttViewModel's 1500 ms minimum-duration guard surfaces "Recording
 * too short — hold longer to speak." instead of wasting a Gemini call.
 *
 * INVARIANT (carried over from the retired floating FAB): this button
 * must stay composed for the entire hold. ConnectedPane (in
 * TerminalScreen.kt) renders ExtraKeysBar unconditionally while the
 * session is Connected, and nothing hides the bar while PTT is
 * Recording — the recording status card merely overlays it. That
 * always-composed property is what
 * structurally eliminates the FAB's AnimatedVisibility-detach trap:
 * an exit transition mid-hold used to detach the LayoutNode and kill
 * the pointerInput coroutine at `waitForUpOrCancellation`, leaving a
 * stuck-Recording state or a phantom "too short" error. If you ever
 * wrap this bar (or this key) in conditional composition, re-read
 * that history first.
 *
 * Unlike the arrow keys two siblings over, holding the mic must NOT
 * auto-repeat anything — the hold itself is the gesture — hence the
 * raw await loop below instead of [KeyButton]'s repeat machinery.
 */
@Composable
private fun MicKey(
    recording: Boolean,
    onPressStart: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .width(KEY_WIDTH)
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (recording) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .pointerInput(Unit) {
                // Two load-bearing details, ported from the old FAB:
                //
                // 1. We consume the first-down so the AndroidView
                //    interop wrapper around Termux's TerminalView
                //    dispatches ACTION_CANCEL to it instead of
                //    forwarding the press; the underlying
                //    GestureDetector then aborts and won't reclaim our
                //    pointer mid-hold. (At the bar's position the
                //    terminal view no longer sits underneath us, but
                //    the recording status card does overlay this spot
                //    mid-hold — keep the consume so nothing else can
                //    ever claim the pointer while the user is holding.)
                //
                // 2. The catch wraps the whole release/cancel branch so
                //    that if the pointerInput is ever detached
                //    mid-gesture, we still call onCancel instead of
                //    leaking the Recording state. The structural fix —
                //    this key is always composed (see INVARIANT above)
                //    — makes that impossible today, so the catch is
                //    defence in depth, not the primary mechanism.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onPressStart()
                    try {
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onRelease()
                        } else {
                            onCancel()
                        }
                    } catch (e: CancellationException) {
                        onCancel()
                        throw e
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "push to talk",
            tint = if (recording) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * The scrollable strip of keys. Fixed-width keys inside a plain
 * [horizontalScroll] Row (16 keys — no need for laziness), with a
 * [EDGE_FADE_WIDTH] gradient fade drawn over each edge that still has
 * content beyond it. The fade is a DstIn punch-through: the row is
 * composited offscreen and the gradient erases its alpha, letting the
 * bar's surfaceVariant background show through.
 */
@Composable
private fun ScrollableKeyRow(
    keys: List<ExtraKey>,
    state: ExtraKeysState,
    onTap: (ExtraKey) -> Unit,
    onRepeatKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .height(48.dp)
            // Offscreen compositing is required for BlendMode.DstIn to
            // affect only this row's pixels (not whatever is behind it).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fadePx = EDGE_FADE_WIDTH.toPx()
                // Only fade an edge while there are keys hidden past it.
                if (scrollState.value > 0) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = fadePx,
                        ),
                        size = Size(fadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
                if (scrollState.value < scrollState.maxValue) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = size.width - fadePx,
                            endX = size.width,
                        ),
                        topLeft = Offset(size.width - fadePx, 0f),
                        size = Size(fadePx, size.height),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scrollState)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        keys.forEach { key ->
            KeyButton(
                key = key,
                state = state,
                onTap = onTap,
                onRepeatKey = onRepeatKey,
                modifier = Modifier.width(KEY_WIDTH),
            )
        }
    }
}

@Composable
private fun KeyButton(
    key: ExtraKey,
    state: ExtraKeysState,
    onTap: (ExtraKey) -> Unit,
    onRepeatKey: (ExtraKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = remember(key) { labelFor(key) }
    val modState = when (key) {
        ExtraKey.Ctrl -> state.ctrl
        ExtraKey.Alt -> state.alt
        else -> ModifierState.Off
    }

    val containerColor = when (modState) {
        ModifierState.Off -> MaterialTheme.colorScheme.surface
        ModifierState.OneShot -> MaterialTheme.colorScheme.primaryContainer
        ModifierState.Locked -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (modState) {
        ModifierState.Off -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    // Long-press repeat is only sensible for arrow keys (and maybe
    // Home/End/PgUp/PgDn). For modifiers and one-shots it's a no-op.
    val supportsRepeat = key is ExtraKey.Arrow ||
        key == ExtraKey.PageUp || key == ExtraKey.PageDown ||
        key == ExtraKey.Home || key == ExtraKey.End

    val scope = rememberCoroutineScope()

    // Repeat-capable keys (arrows + nav) live inside a horizontalScroll
    // row, so their gesture handling MUST yield to a drag-to-scroll.
    // detectTapGestures does exactly that: it cancels its own tap /
    // long-press detection the moment a scrolling ancestor consumes the
    // pointer, so dragging across the bar scrolls it and fires ZERO keys.
    //
    // Behaviour:
    //   • quick tap  → onTap, fired on RELEASE (not touch-down)
    //   • drag       → scroller consumes; onTap/onLongPress never fire
    //   • held still → onLongPress at the platform long-press timeout:
    //                  fire once, then repeat every LONG_PRESS_REPEAT_MS
    //                  until the finger lifts (onPress's tryAwaitRelease
    //                  resumes and cancels the repeat job).
    //
    // This deliberately trades the old fire-on-touch-down behaviour (which
    // ignored consumption and leaked a keystroke on every scroll attempt —
    // the 2026-06-15 drag-fires-keys bug) for standard tap latency. Do NOT
    // regress to awaitFirstDown-fires-immediately. Non-repeat keys keep
    // Modifier.clickable, which already fires on release and yields to the
    // scroller.
    val gestureModifier = if (supportsRepeat) {
        Modifier.pointerInput(key) {
            // One reference shared by onLongPress (starts the repeat) and
            // onPress (stops it). The job's whole life is a single
            // press→release, so it needn't survive a recomposition.
            var repeatJob: Job? = null
            detectTapGestures(
                onPress = {
                    // Suspends until the finger lifts (returns true) or the
                    // scroller claims the drag (returns false). Either way,
                    // stop repeating — this is the disambiguation that
                    // keeps a scroll from leaking keystrokes.
                    tryAwaitRelease()
                    repeatJob?.cancel()
                    repeatJob = null
                },
                onTap = { onTap(key) },
                onLongPress = {
                    onTap(key)
                    repeatJob = scope.launch {
                        while (isActive) {
                            onRepeatKey(key)
                            delay(LONG_PRESS_REPEAT_MS)
                        }
                    }
                },
            )
        }
    } else {
        Modifier.clickable { onTap(key) }
    }

    Box(
        modifier = modifier
            .height(KEY_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .then(
                if (modState == ModifierState.Locked) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp),
                    )
                } else Modifier,
            )
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (modState == ModifierState.Locked) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

private fun labelFor(key: ExtraKey): String = when (key) {
    ExtraKey.Escape -> "ESC"
    ExtraKey.Tab -> "TAB"
    ExtraKey.Ctrl -> "CTRL"
    ExtraKey.Alt -> "ALT"
    ExtraKey.Enter -> "↵"
    ExtraKey.Home -> "HOME"
    ExtraKey.End -> "END"
    ExtraKey.PageUp -> "PGUP"
    ExtraKey.PageDown -> "PGDN"
    is ExtraKey.Arrow -> when (key.dir) {
        ArrowDir.Up -> "↑"
        ArrowDir.Down -> "↓"
        ArrowDir.Left -> "←"
        ArrowDir.Right -> "→"
    }
    is ExtraKey.Fn -> "F${key.n}"
    is ExtraKey.Char -> key.display
}
