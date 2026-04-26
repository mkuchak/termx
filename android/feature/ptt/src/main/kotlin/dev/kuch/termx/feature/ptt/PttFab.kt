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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import kotlinx.coroutines.CancellationException
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
        // The Card surfaces the recording / transcript UI. The FAB has
        // its own visibility rule below — critically, it stays rendered
        // through Recording so the user's hold-to-record gesture isn't
        // interrupted by the modifier being detached mid-press. See the
        // load-bearing comment on `fabVisible` below.
        val cardVisible = state !is PttState.Idle
        val recording = state is PttState.Recording

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

        // Render the FAB during Idle AND Recording. If we hid it the
        // moment Recording begins (the original design), AnimatedVisibility
        // would start an exit transition while the user is still holding,
        // and ~300ms later detach the LayoutNode. Detaching kills the
        // pointerInput coroutine via CancellationException at the
        // `waitForUpOrCancellation` suspension — the if/else after it
        // never resumes, and depending on the synthetic event Compose
        // dispatches at detach time you get either a stuck-in-Recording
        // state or a falsely-fired stopRecordingAndTranscribe with a
        // <1.5s duration that surfaces as "Recording too short". Both
        // bugs trace back to this layout, not to the gesture API. Keep
        // the FAB on screen until the user's gesture is genuinely done
        // (Transcribing / Ready / Error are all post-release states).
        val fabVisible = state is PttState.Idle || state is PttState.Recording

        AnimatedVisibility(
            visible = fabVisible,
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
                            // Two load-bearing details:
                            //
                            // 1. We consume the first-down so the
                            //    AndroidView interop wrapper around Termux's
                            //    TerminalView dispatches ACTION_CANCEL to it
                            //    instead of forwarding the press; the
                            //    underlying GestureDetector then aborts and
                            //    won't reclaim our pointer mid-press.
                            //
                            // 2. The catch wraps the whole release/cancel
                            //    branch so that if the pointerInput is ever
                            //    detached mid-gesture (e.g. a parent
                            //    AnimatedVisibility hiding us), we still
                            //    call cancelRecording instead of leaking
                            //    the Recording state. The structural fix
                            //    above keeps the FAB rendered through
                            //    Recording, so this catch is now defence
                            //    in depth, not the primary mechanism.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                tryStart()
                                try {
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        up.consume()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.stopRecordingAndTranscribe()
                                    } else {
                                        viewModel.cancelRecording()
                                    }
                                } catch (e: CancellationException) {
                                    viewModel.cancelRecording()
                                    throw e
                                }
                            }
                        },
                    color = if (recording) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
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
    onCancel: () -> Unit,
    onSend: (text: String, appendNewline: Boolean) -> Unit,
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
    WaveformBars(amplitudes = amplitudes)
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Editable transcript preview. The TextField holds a local [draft]
 * keyed off the incoming [text] so a fresh transcription resets it
 * but the user's edits survive recomposition while they're still
 * working on the same one. Three buttons:
 *
 *  - **Cancel**: drop the transcript, back to Idle.
 *  - **Insert**: write [draft] to the PTY without a trailing newline;
 *    the user keeps editing in the shell.
 *  - **Send**: write [draft] followed by `\n`; the shell executes it
 *    immediately. This is the primary action.
 *
 * Both Insert and Send disable on a blank draft so we don't waste a
 * round-trip writing nothing.
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

    Text(
        text = "Transcript",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = false,
        maxLines = 5,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onSend(draft, false) },
                enabled = canSend,
            ) {
                Text("Insert")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(draft, true) },
                enabled = canSend,
                colors = ButtonDefaults.buttonColors(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Send")
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
