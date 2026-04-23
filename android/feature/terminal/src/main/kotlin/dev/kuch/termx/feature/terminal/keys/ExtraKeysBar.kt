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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val LONG_PRESS_REPEAT_MS = 60L
private const val LONG_PRESS_INITIAL_DELAY_MS = 400L

/**
 * Extra-keys toolbar rendered above the soft keyboard.
 *
 * Two swipeable rows (via [HorizontalPager]) with indicator dots.
 * Sticky CTRL/ALT; volume-down-as-Ctrl is handled by [TerminalScreen]
 * upstream. Every tap fires a [HapticFeedbackConstants.KEYBOARD_TAP].
 *
 * The double-tap → Locked promotion is implemented with a per-modifier
 * timestamp: a second tap within [DOUBLE_TAP_WINDOW_MS] upgrades
 * OneShot → Locked.
 *
 * Arrow keys support long-press repeat: after
 * [LONG_PRESS_INITIAL_DELAY_MS] the key re-fires every
 * [LONG_PRESS_REPEAT_MS] until the finger lifts.
 */
@Composable
fun ExtraKeysBar(
    onKey: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
    state: ExtraKeysState = rememberExtraKeysState(),
    layoutRow1: List<ExtraKey> = ExtraKeysLayout.ROW_1,
    layoutRow2: List<ExtraKey> = ExtraKeysLayout.ROW_2,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
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
        Column {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val row = if (page == 0) layoutRow1 else layoutRow2
                KeyRow(
                    keys = row,
                    state = state,
                    onTap = handleTap,
                    onRepeatKey = { key ->
                        // Used by arrow keys while held. Bypasses haptic per
                        // repeat — only the initial press hapticks.
                        val bytes = ExtraKeyBytes.encode(key, state.ctrlActive, state.altActive)
                        if (bytes.isNotEmpty()) onKey(bytes)
                    },
                )
            }
            PageIndicator(
                pageCount = 2,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun KeyRow(
    keys: List<ExtraKey>,
    state: ExtraKeysState,
    onTap: (ExtraKey) -> Unit,
    onRepeatKey: (ExtraKey) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
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

    val gestureModifier = if (supportsRepeat) {
        Modifier.pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // Fire once immediately (same behavior as a tap).
                onTap(key)
                var repeatJob: Job? = scope.launch {
                    delay(LONG_PRESS_INITIAL_DELAY_MS)
                    while (isActive) {
                        onRepeatKey(key)
                        delay(LONG_PRESS_REPEAT_MS)
                    }
                }
                // Wait until all pointers are up (or the gesture is
                // cancelled by the pager pulling the pointer away).
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.all { !it.pressed }) break
                }
                repeatJob?.cancel()
                repeatJob = null
            }
        }
    } else {
        Modifier.clickable { onTap(key) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
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

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(pageCount) { idx ->
            val active = idx == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 6.dp else 5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
            )
        }
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
