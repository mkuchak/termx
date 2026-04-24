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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the Push-to-talk lifecycle:
 *
 *  1. User presses and holds FAB   → [startRecording].
 *  2. User releases                 → [stopRecordingAndTranscribe] fires
 *     the Gemini call. On success we land in [PttState.Ready], showing a
 *     preview card with Send / Cancel.
 *  3. User taps "Send"              → [sendReady] returns the text to
 *     the caller via [consumeSend]; the composable injects it into the
 *     PTY via [dev.kuch.termx.feature.terminal.TerminalViewModel.writeToPty].
 *
 * Mode (Command vs Text) is persisted to DataStore via [AppPreferences].
 * The send path here does not itself touch the PTY — the PTT module has
 * no dependency on `:feature:terminal` to avoid a cycle; the caller
 * composable resolves the writer and calls it.
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

    /**
     * Live push-to-talk mode preference. The FAB UI binds to this
     * directly so switching persists instantly via [setMode].
     */
    val mode: StateFlow<PttMode> = appPreferences.pttMode
        .map { PttMode.parse(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PttMode.Command,
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

        _state.value = PttState.Transcribing
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            runCatching {
                val key = apiKeyStore.get().orEmpty()
                if (key.isBlank()) {
                    throw GeminiException("Add your Gemini API key in Settings to use push-to-talk.")
                }
                withContext(Dispatchers.IO) {
                    geminiClient.transcribe(key, captured.file)
                }
            }.onSuccess { text ->
                _state.value = PttState.Ready(text = text, mode = mode.value)
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

    /** Persist the new PTT mode. */
    fun setMode(new: PttMode) {
        viewModelScope.launch {
            appPreferences.setPttMode(new.persistValue)
        }
    }

    override fun onCleared() {
        transcribeJob?.cancel()
        runCatching { audioRecorder.cancel() }
        activeRecordingFile?.let { runCatching { it.delete() } }
        super.onCleared()
    }

    companion object {
        private const val LOG_TAG = "PttViewModel"
        private const val MIN_USEFUL_DURATION_MS = 250L
    }
}

/**
 * Observable PTT state. The composable binds one branch at a time:
 *  - [Idle]: FAB only.
 *  - [Recording]: FAB in "recording" visuals + waveform card.
 *  - [Transcribing]: spinner card.
 *  - [Ready]: transcript preview with Send / Cancel.
 *  - [Error]: error banner, dismissible.
 */
sealed interface PttState {
    data object Idle : PttState
    data class Recording(val amplitudes: List<Int>) : PttState
    data object Transcribing : PttState
    data class Ready(val text: String, val mode: PttMode) : PttState
    data class Error(val message: String) : PttState
}

/**
 * Push-to-talk output mode. Drives whether the injected transcript
 * is terminated with a newline (shell executes immediately) or not
 * (user adds details and presses Enter themselves).
 */
enum class PttMode(val persistValue: String, val appendNewline: Boolean, val label: String) {
    Command(persistValue = "command", appendNewline = true, label = "Command"),
    Text(persistValue = "text", appendNewline = false, label = "Text"),
    ;

    companion object {
        fun parse(raw: String): PttMode = when (raw) {
            Text.persistValue -> Text
            else -> Command
        }
    }
}
