/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.sessions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuch.termx.core.domain.model.TmuxSession

/**
 * Horizontally-scrollable row of tmux session tabs plus a trailing
 * "new session" button.
 *
 * Each [SessionTab] renders as a pill with:
 *  - session name (ellipsized after 12 chars)
 *  - optional `[claude]` badge when `session.claudeDetected == true`
 *  - pulsing activity dot when the session's name is in
 *    [activityFlashes]
 *
 * Tab interactions:
 *  - tap → [onTabSelected] with the tmux session name
 *  - long-press → [onLongPressTab] (the host composable shows a
 *    DropdownMenu anchored near the tab)
 *  - swipe up (>40.dp vertical drag) → interpreted by the host as
 *    "detach from this session" — the drag handler calls
 *    [onSwipeUpTab]. The tmux session itself keeps running remotely.
 */
@Composable
fun SessionTabBar(
    sessions: List<TmuxSession>,
    activeSessionName: String?,
    activityFlashes: Set<String>,
    onTabSelected: (String) -> Unit,
    onNewSession: () -> Unit,
    onLongPressTab: (TmuxSession) -> Unit,
    onSwipeUpTab: (TmuxSession) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp,
                    vertical = 6.dp,
                ),
            ) {
                items(
                    items = sessions,
                    key = { it.name },
                ) { session ->
                    SessionTab(
                        session = session,
                        isActive = session.name == activeSessionName,
                        isFlashing = session.name in activityFlashes,
                        onTap = { onTabSelected(session.name) },
                        onLongPress = { onLongPressTab(session) },
                        onSwipeUp = { onSwipeUpTab(session) },
                    )
                }
            }
            IconButton(onClick = onNewSession) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "new session",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SessionTab(
    session: TmuxSession,
    isActive: Boolean,
    isFlashing: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isActive) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .pointerInput(session.name) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            }
            .pointerInput(session.name) {
                // Swipe-up detection — threshold chosen to distinguish
                // a deliberate upward flick from accidental scroll.
                var cumulativeY = 0f
                detectDragGestures(
                    onDragStart = { cumulativeY = 0f },
                    onDragEnd = {
                        if (cumulativeY < -SWIPE_UP_DETACH_PX) onSwipeUp()
                        cumulativeY = 0f
                    },
                    onDragCancel = { cumulativeY = 0f },
                    onDrag = { _, dragAmount -> cumulativeY += dragAmount.y },
                )
            }
            .padding(horizontal = 12.dp),
    ) {
        if (isFlashing) {
            PulsingActivityDot()
        }
        Text(
            text = ellipsize(session.name),
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (session.claudeDetected) {
            ClaudeBadge()
        }
    }
}

@Composable
private fun PulsingActivityDot() {
    val transition = rememberInfiniteTransition(label = "session-activity-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = InfiniteRepeatableSpec(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "session-activity-dot-alpha",
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(Color(0xFF4CAF50).copy(alpha = alpha)),
    )
}

@Composable
private fun ClaudeBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = CLAUDE_PURPLE,
    ) {
        Text(
            text = "claude",
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun ellipsize(name: String, max: Int = 12): String =
    if (name.length <= max) name else name.take(max - 1) + "…"

/**
 * Host-side helper for the long-press DropdownMenu. Callers anchor it
 * against whatever `remember`ed anchor they pick — the most common
 * pattern is to track the long-pressed session in state, render this
 * at the tab-bar scope, and let the caller hand back to the dialog
 * flow in their own composable.
 *
 * Kept here so the tab bar and its menu options stay colocated.
 */
@Composable
fun SessionTabActionsMenu(
    session: TmuxSession?,
    onDismiss: () -> Unit,
    onRename: (TmuxSession) -> Unit,
    onKill: (TmuxSession) -> Unit,
    onClose: (TmuxSession) -> Unit,
) {
    if (session == null) return
    var expanded by remember { mutableStateOf(true) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            expanded = false
            onDismiss()
        },
    ) {
        DropdownMenuItem(
            text = { Text("Rename") },
            onClick = {
                expanded = false
                onRename(session)
            },
        )
        DropdownMenuItem(
            text = { Text("Kill session") },
            onClick = {
                expanded = false
                onKill(session)
            },
        )
        DropdownMenuItem(
            text = { Text("Close (detach)") },
            onClick = {
                expanded = false
                onClose(session)
            },
        )
    }
}

// Not a Compose dp — we need raw pixels because detectDragGestures
// reports drag amounts in pixels. 40.dp at mdpi ≈ 40 px; at xxxhdpi
// ≈ 160 px. Using a fixed threshold is the simplest approximation;
// the tab is only 36.dp tall so this is always at least one tab's
// height of upward travel.
private const val SWIPE_UP_DETACH_PX = 80f

private val CLAUDE_PURPLE = Color(0xFF7C3AED)
