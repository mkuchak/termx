package dev.kuch.termx.feature.ptt

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.prefs.GeminiApiKeyStore
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the Push-to-talk lifecycle:
 *
 *  1. User presses and holds FAB   → [startRecording].
 *  2. User releases                 → [stopRecordingAndTranscribe] fires
 *     the Gemini call. On success we land in [PttState.Ready], showing
 *     a preview card with the editable transcript.
 *  3. User taps "Send" or "Insert" → the composable writes the
 *     (possibly edited) transcript to the PTY, with or without a
 *     trailing newline, and calls [consumeSend] to drop back to Idle.
 *
 * The "Command vs Text" persisted mode was removed in v1.1.11: the
 * choice is now made per-utterance via the two buttons on the Ready
 * card, and the transcript itself is editable inside the card so the
 * user can fix Gemini mistranscriptions without retyping in the shell.
 *
 * The send path here does not itself touch the PTY — the PTT module
 * has no dependency on `:feature:terminal` to avoid a cycle; the
 * caller composable resolves the writer and calls it.
 */
@HiltViewModel
class PttViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val apiKeyStore: GeminiApiKeyStore,
    private val audioRecorder: AudioRecorder,
    private val geminiClient: GeminiClient,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<PttState>(PttState.Idle)
    val state: StateFlow<PttState> = _state.asStateFlow()

    /** BCP-47 source locale ("en-US"); driven by Settings. */
    val sourceLanguage: StateFlow<String> = appPreferences.pttSourceLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            "en-US",
        )

    /** BCP-47 target locale; equal to [sourceLanguage] = transcribe-only. */
    val targetLanguage: StateFlow<String> = appPreferences.pttTargetLanguage
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            "en-US",
        )

    /** Optional domain-context hints appended to every prompt. */
    val context: StateFlow<String> = appPreferences.pttContext
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            "",
        )

    private var activeRecordingFile: File? = null
    private var transcribeJob: Job? = null

    /**
     * Begin a new recording. No-op if we are already recording.
     */
    fun startRecording() {
        if (_state.value is PttState.Recording) return

        val file = File(appContext.cacheDir, "ptt-${UUID.randomUUID()}.m4a")
        activeRecordingFile = file
        try {
            audioRecorder.start(file)
            _state.value = PttState.Recording(
                amplitudes = audioRecorder.amplitudes.value,
            )
            viewModelScope.launch {
                audioRecorder.amplitudes.collect { samples ->
                    val current = _state.value
                    if (current is PttState.Recording) {
                        _state.value = current.copy(amplitudes = samples)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "start recording failed", t)
            activeRecordingFile = null
            _state.value = PttState.Error(t.message ?: "Could not start recorder")
        }
    }

    /**
     * Stop the current recording and dispatch a transcription call to
     * Gemini. Transitions state through `Transcribing` → `Ready` or
     * `Error`.
     */
    fun stopRecordingAndTranscribe() {
        if (_state.value !is PttState.Recording) return
        val captured = audioRecorder.stop()
        if (captured == null || captured.durationMs < MIN_USEFUL_DURATION_MS) {
            activeRecordingFile?.let { runCatching { it.delete() } }
            activeRecordingFile = null
            _state.value = PttState.Error("Recording too short — hold longer to speak.")
            return
        }

        _state.value = PttState.Transcribing(attempt = 1, maxAttempts = GeminiClient.MAX_ATTEMPTS)
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            runCatching {
                val key = apiKeyStore.get().orEmpty()
                if (key.isBlank()) {
                    throw GeminiException("Add your Gemini API key in Settings to use push-to-talk.")
                }
                withContext(Dispatchers.IO) {
                    geminiClient.transcribe(
                        apiKey = key,
                        audioFile = captured.file,
                        sourceLanguage = sourceLanguage.value,
                        targetLanguage = targetLanguage.value,
                        context = context.value,
                        onAttempt = { attempt ->
                            _state.value = PttState.Transcribing(
                                attempt = attempt,
                                maxAttempts = GeminiClient.MAX_ATTEMPTS,
                            )
                        },
                    )
                }
            }.onSuccess { text ->
                // Gemini, when handed silent / room-tone audio, will
                // happily fabricate a plausible-sounding transcript
                // (the prompt asks for one, so it produces one). We
                // instruct it to emit the literal NO_SPEECH sentinel
                // when there's nothing intelligible — bail with a
                // friendly error instead of injecting hallucinated
                // text into the user's PTY.
                if (text.trim().equals(NO_SPEECH_SENTINEL, ignoreCase = true)) {
                    _state.value = PttState.Error("No speech detected — try again.")
                } else {
                    _state.value = PttState.Ready(text = text)
                }
            }.onFailure { t ->
                Log.w(LOG_TAG, "transcription failed", t)
                _state.value = PttState.Error(t.message ?: "Transcription failed")
            }
            runCatching { captured.file.delete() }
            activeRecordingFile = null
        }
    }

    /**
     * Abort the current recording without transcribing. Safe from any
     * state; a no-op outside Recording.
     */
    fun cancelRecording() {
        if (_state.value is PttState.Recording) {
            audioRecorder.cancel()
        }
        activeRecordingFile?.let { runCatching { it.delete() } }
        activeRecordingFile = null
        _state.value = PttState.Idle
    }

    /** Clear the current Ready/Error/Transcribing card back to Idle. */
    fun dismiss() {
        transcribeJob?.cancel()
        transcribeJob = null
        _state.value = PttState.Idle
    }

    /**
     * Mark the current Ready transcript as sent — consumers should call
     * this after they have injected the text into the PTY so we drop
     * back to Idle.
     */
    fun consumeSend() {
        _state.value = PttState.Idle
    }

    /**
     * Open the editable transcript card with an empty draft so the user
     * can type a command instead of speaking it. The compose card and
     * the PTT card are the same composable — `requestFocus = true`
     * tells [ReadyBody] to auto-focus the field (and pop the IME) the
     * moment it appears, distinguishing this entry from the
     * post-Gemini one which deliberately doesn't auto-focus.
     *
     * No-op when PTT is busy with anything else: never clobber an
     * active recording / in-flight Gemini call / existing transcript
     * the user might still want.
     */
    fun composeText() {
        if (_state.value != PttState.Idle) return
        _state.value = PttState.Ready(text = "", requestFocus = true)
    }

    override fun onCleared() {
        transcribeJob?.cancel()
        runCatching { audioRecorder.cancel() }
        activeRecordingFile?.let { runCatching { it.delete() } }
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "PttViewModel"

        /**
         * Floor on a recording's duration before we'll spend a Gemini
         * API call on it. Set to 1.5 s because:
         *  - Genuine speech recordings are typically multi-second.
         *  - Anything sub-second is almost always either an accidental
         *    tap or a gesture-cancel race (see PttFab.awaitEachGesture).
         *  - Below this floor, MediaRecorder produces buffered
         *    room-tone with no actual speech, and Gemini cheerfully
         *    fabricates plausible-sounding transcripts when handed
         *    silence — wasting an API call AND polluting the user's
         *    PTY with hallucinated text.
         *
         * Was 250 ms, which only caught the "MediaRecorder returned
         * null / 0-byte" case and missed sub-second-but-non-empty
         * recordings entirely.
         */
        private const val MIN_USEFUL_DURATION_MS = 1500L

        /**
         * Sentinel Gemini is instructed to return when the audio
         * contains no intelligible speech — see GeminiClient.PROMPT.
         */
        internal const val NO_SPEECH_SENTINEL = "NO_SPEECH"
    }
}

/**
 * Observable PTT state. The composable binds one branch at a time:
 *  - [Idle]: FAB only.
 *  - [Recording]: FAB in "recording" visuals + waveform card.
 *  - [Transcribing]: spinner card with retry counter.
 *  - [Ready]: editable transcript preview with Cancel / Insert / Send.
 *  - [Error]: error banner, dismissible.
 */
sealed interface PttState {
    data object Idle : PttState
    data class Recording(val amplitudes: List<Int>) : PttState

    /**
     * Awaiting Gemini's response. [attempt] is 1-based and increments
     * when the retry loop in [GeminiClient.transcribe] reissues the
     * call after a transient failure; the FAB caption uses it to
     * render "Transcribing…" on attempt 1 and "Retrying… (N/M)" on
     * attempts ≥2.
     */
    data class Transcribing(val attempt: Int, val maxAttempts: Int) : PttState

    /**
     * Editable transcript waiting for Insert / Send / Cancel.
     *
     * @param requestFocus `true` when the field should auto-focus on
     *   appearance (compose-text long-press path); `false` for the
     *   post-Gemini path where auto-focus would be friction since the
     *   common case is "tap Send."
     */
    data class Ready(val text: String, val requestFocus: Boolean = false) : PttState
    data class Error(val message: String) : PttState
}
