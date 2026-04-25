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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The Push-to-talk surface anchored to the bottom-right of the terminal
 * screen. Consists of:
 *
 *  - The circular hold-to-record FAB, floating when [PttState] is
 *    [PttState.Idle] or [PttState.Error].
 *  - A full-width card at the bottom that replaces the FAB while
 *    recording / transcribing / previewing.
 *
 * The caller supplies [onSend]: the PTT module does not depend on
 * `:feature:terminal` (keeps the module graph acyclic), so the host
 * composable resolves the PTY writer and hands it down.
 *
 * Runtime permission handling: on first press-down we check
 * [Manifest.permission.RECORD_AUDIO]; if missing we launch the system
 * prompt and defer recording until the user answers.
 */
@Composable
fun PttSurface(
    onSend: (text: String, appendNewline: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PttViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val sourceLanguage by viewModel.sourceLanguage.collectAsStateWithLifecycle()
    val targetLanguage by viewModel.targetLanguage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

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

    val tryStart: () -> Unit = tryStart@{
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

    Box(modifier = modifier.fillMaxSize()) {
        val active = state !is PttState.Idle

        AnimatedVisibility(
            visible = active,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PttStatusCard(
                state = state,
                mode = mode,
                onModeChange = viewModel::setMode,
                onCancel = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    when (state) {
                        is PttState.Recording -> viewModel.cancelRecording()
                        else -> viewModel.dismiss()
                    }
                },
                onSend = {
                    val ready = state as? PttState.Ready ?: return@PttStatusCard
                    onSend(ready.text, ready.mode.appendNewline)
                    viewModel.consumeSend()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = !active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 80.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .pointerInput(Unit) {
                            // The FAB sits above an AndroidView-wrapped Termux
                            // TerminalView whose own onTouchEvent runs an active
                            // GestureDetector. The v1.1.9 fix consumed the
                            // first-down so the underlying view never sees the
                            // press; waitForUpOrCancellation() then gives us a
                            // clean release-vs-cancel signal. Cancel = silent
                            // abort; release = transcribe. Treating cancel as
                            // release used to fire stopRecordingAndTranscribe()
                            // mid-press, which is what produced the v1.1.8
                            // "FAB auto-released and hallucinated a transcript"
                            // bug.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                tryStart()
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    up.consume()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.stopRecordingAndTranscribe()
                                } else {
                                    viewModel.cancelRecording()
                                }
                            }
                        },
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    shadowElevation = 6.dp,
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = "Push to talk",
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
                if (sourceLanguage != targetLanguage) {
                    Spacer(Modifier.height(4.dp))
                    val label =
                        "${sourceLanguage.substringBefore('-').uppercase()} → " +
                            targetLanguage.substringBefore('-').uppercase()
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PttStatusCard(
    state: PttState,
    mode: PttMode,
    onModeChange: (PttMode) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(),
        shape = RoundedCornerShape(16.dp),
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
                    mode = mode,
                    onModeChange = onModeChange,
                    onCancel = onCancel,
                )
                is PttState.Transcribing -> TranscribingBody(
                    attempt = state.attempt,
                    maxAttempts = state.maxAttempts,
                )
                is PttState.Ready -> ReadyBody(
                    text = state.text,
                    mode = mode,
                    onModeChange = onModeChange,
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
    mode: PttMode,
    onModeChange: (PttMode) -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = "Listening… release to transcribe",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WaveformBars(amplitudes = amplitudes)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegmentedToggle(mode = mode, onModeChange = onModeChange)
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun TranscribingBody(attempt: Int, maxAttempts: Int) {
    val label = if (attempt > 1) "Retrying… ($attempt/$maxAttempts)" else "Transcribing…"
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadyBody(
    text: String,
    mode: PttMode,
    onModeChange: (PttMode) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Text(
        text = "Transcript",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegmentedToggle(mode = mode, onModeChange = onModeChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (mode.appendNewline) "Send" else "Insert")
            }
        }
    }
}

@Composable
private fun ErrorBody(message: String, onDismiss: () -> Unit) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun ModeSegmentedToggle(
    mode: PttMode,
    onModeChange: (PttMode) -> Unit,
) {
    val modes = listOf(PttMode.Command, PttMode.Text)
    SingleChoiceSegmentedButtonRow {
        modes.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onModeChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
            ) {
                Text(entry.label)
            }
        }
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
