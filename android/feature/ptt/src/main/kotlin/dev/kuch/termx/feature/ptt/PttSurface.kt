package dev.kuch.termx.feature.ptt

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The push-to-talk status overlay: a full-width card pinned to the
 * bottom of the terminal area rendering the Recording / Transcribing /
 * Ready / Error lifecycle. Idle renders nothing.
 *
 * The hold-to-record mic is NOT here anymore. It used to be a floating
 * 64.dp FAB in this file; it now lives in the pinned trailing area of
 * the host's extra-keys bar (Moshi-style, next to the keyboard chip).
 * This module can't render it itself — `:feature:ptt` must not depend
 * on `:feature:terminal` (module cycle) — so the host wires plain
 * lambdas into its bar: [rememberPttStartAction] for press-start,
 * [PttViewModel.stopRecordingAndTranscribe] for release, and
 * [PttViewModel.cancelRecording] (guarded to Recording at the call
 * site) for cancel.
 *
 * The move is also a structural fix: a bar button is ALWAYS composed,
 * which eliminates the AnimatedVisibility-detach gesture trap the
 * floating FAB needed defenses for (an exit transition mid-hold
 * detached the LayoutNode and killed the pointerInput coroutine at
 * `waitForUpOrCancellation` → stuck-Recording or phantom "too short"
 * errors). The surviving gesture laws are documented on the mic button
 * in `ExtraKeysBar`.
 *
 * The caller supplies [onSend]: the host composable resolves the PTY
 * writer and hands it down, again to keep the module graph acyclic.
 */
@Composable
fun PttSurface(
    onSend: (text: String, appendNewline: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PttViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        val cardVisible = state !is PttState.Idle

        AnimatedVisibility(
            visible = cardVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PttStatusCard(
                state = state,
                onCancel = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (state) {
                        is PttState.Recording -> viewModel.cancelRecording()
                        else -> viewModel.dismiss()
                    }
                },
                onSend = { text, appendNewline ->
                    onSend(text, appendNewline)
                    viewModel.consumeSend()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Press-start action for a host-rendered hold-to-record mic (the
 * extra-keys bar mic in `:feature:terminal`). The host calls this on
 * the first pointer down; release / cancel go straight to
 * [PttViewModel.stopRecordingAndTranscribe] /
 * [PttViewModel.cancelRecording].
 *
 * Encapsulates the two start-side rules that used to live on the FAB:
 *
 *  - **Idle gating.** Starting a recording is a no-op unless PTT is
 *    Idle. The FAB enforced this structurally (it was only composed in
 *    Idle/Recording); the bar mic is always composed, so the gate is
 *    explicit here. It reads [PttViewModel.state] directly rather than
 *    a composition snapshot so a press racing a state flip can't slip
 *    through, and it also keeps a gated press from popping the
 *    permission dialog over a Ready transcript.
 *
 *  - **RECORD_AUDIO runtime permission.** On press-down we check the
 *    permission; if missing we launch the system prompt and defer
 *    recording until the user answers. (The press is long gone by the
 *    time the user grants, so the grant starts an open-ended recording
 *    the user finishes from the status card — same behavior the FAB
 *    had.)
 *
 * The returned lambda is remembered: the host's `pointerInput(Unit)`
 * block captures it once and never restarts, so it must not be a
 * fresh-per-recomposition closure over stale state.
 */
@Composable
fun rememberPttStartAction(
    viewModel: PttViewModel = hiltViewModel(),
): () -> Unit {
    val context = LocalContext.current

    var pendingStartAfterGrant by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val wasPending = pendingStartAfterGrant
        pendingStartAfterGrant = false
        if (granted && wasPending) {
            viewModel.startRecording()
        }
    }

    return remember(viewModel, permissionLauncher) {
        fun() {
            if (viewModel.state.value != PttState.Idle) return
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                viewModel.startRecording()
            } else {
                pendingStartAfterGrant = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
private fun PttStatusCard(
    state: PttState,
    onCancel: () -> Unit,
    onSend: (text: String, appendNewline: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(),
        shape = RoundedCornerShape(PillRadius),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                is PttState.Recording -> RecordingBody(
                    amplitudes = state.amplitudes,
                    onCancel = onCancel,
                )
                is PttState.Transcribing -> TranscribingBody(
                    attempt = state.attempt,
                    maxAttempts = state.maxAttempts,
                )
                is PttState.Ready -> ReadyBody(
                    text = state.text,
                    requestFocus = state.requestFocus,
                    onCancel = onCancel,
                    onSend = onSend,
                )
                is PttState.Error -> ErrorBody(message = state.message, onDismiss = onCancel)
                PttState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun RecordingBody(
    amplitudes: List<Int>,
    onCancel: () -> Unit,
) {
    Text(
        text = "Listening… release to transcribe",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Same outlined-pill silhouette as the Ready compose box so the
    // card reads as one surface morphing through the PTT lifecycle.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PillRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(PillBorderWidth, MaterialTheme.colorScheme.primary),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            WaveformBars(amplitudes = amplitudes)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun TranscribingBody(attempt: Int, maxAttempts: Int) {
    val label = if (attempt > 1) "Retrying… ($attempt/$maxAttempts)" else "Transcribing…"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PillRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(PillBorderWidth, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Editable transcript preview, styled as a Moshi-like compose box:
 * an outlined pill text field with a compact ✕ dismiss inside it and
 * a circular filled send button to its right. The field holds a local
 * [draft] keyed off the incoming [text] so a fresh transcription
 * resets it but the user's edits survive recomposition while they're
 * still working on the same one.
 *
 *  - **✕** (in-pill): drop the transcript, back to Idle.
 *  - **Send**: write [draft] followed by `\n`; the shell executes it
 *    immediately. Disabled on a blank draft so we don't waste a
 *    round-trip writing nothing.
 *
 * Note [onSend]'s `appendNewline` flag stays in the signature for the
 * host contract (`TerminalScreen` encodes it into the PTY payload),
 * but this card now only ever sends with `appendNewline = true` —
 * the old "Insert without newline" button was removed in the pill
 * redesign.
 */
@Composable
private fun ReadyBody(
    text: String,
    requestFocus: Boolean,
    onCancel: () -> Unit,
    onSend: (text: String, appendNewline: Boolean) -> Unit,
) {
    var draft by remember(text) { mutableStateOf(text) }
    val canSend = draft.isNotBlank()
    val focusRequester = remember { FocusRequester() }

    // Compose-text long-press path enters Ready with requestFocus=true
    // so the IME pops immediately. The post-Gemini path leaves it false
    // because the common case there is "tap Send," not "edit."
    LaunchedEffect(requestFocus) {
        if (requestFocus) focusRequester.requestFocus()
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(PillRadius),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(PillBorderWidth, MaterialTheme.colorScheme.primary),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 14.dp)
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = "Type a command…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        FilledIconButton(
            onClick = { onSend(draft, true) },
            enabled = canSend,
            shape = CircleShape,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Send,
                contentDescription = "Send",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ErrorBody(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PillRadius),
        color = MaterialTheme.colorScheme.surface,
        // In Sorcerer error == primary by design, so this pill matches
        // the Ready/Recording outline exactly. Keep the semantic token.
        border = BorderStroke(PillBorderWidth, MaterialTheme.colorScheme.error),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/**
 * Paints a row of amplitude bars. Each bar's height is scaled to the
 * max amplitude in the current window (relative visualisation — we care
 * about "is the user talking louder" rather than absolute dB).
 */
@Composable
private fun WaveformBars(amplitudes: List<Int>) {
    val downsampled = remember(amplitudes) {
        if (amplitudes.size <= 48) amplitudes else amplitudes.filterIndexed { i, _ -> i % 2 == 0 }
    }
    val peak = (downsampled.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val barList = downsampled.ifEmpty { List(EMPTY_BAR_FILL) { 0 } }
        barList.forEach { value ->
            val normalised = value.toFloat() / peak.toFloat()
            val barHeight = (8 + (normalised * 48f)).coerceIn(6f, 56f)
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private const val EMPTY_BAR_FILL = 8

/** Corner radius shared by the status card and the pill surfaces inside it. */
private val PillRadius = 28.dp

/** Outline width of the pill surfaces (Moshi-style compose-box border). */
private val PillBorderWidth = 2.dp
