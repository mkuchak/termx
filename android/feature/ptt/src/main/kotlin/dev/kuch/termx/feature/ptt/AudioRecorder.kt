package dev.kuch.termx.feature.ptt

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Task #40 — MediaRecorder wrapper for the PTT FAB.
 *
 * Records MP4/AAC (the widest Gemini multimodal compat: it accepts
 * `audio/mp4` plus other codecs, but AAC-in-MP4 is the Android default
 * that works on every device from API 21+).
 *
 * Amplitude polling: we sample `getMaxAmplitude()` at [SAMPLE_PERIOD_MS]
 * cadence on an IO coroutine and publish it on [amplitudes] — the PTT
 * composable reads this flow to draw the live waveform. The ring buffer
 * keeps the latest [MAX_SAMPLES] values; older samples are dropped.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L
    private var pollingJob: Job? = null
    private var recordingScope: CoroutineScope? = null

    private val _amplitudes = MutableStateFlow<List<Int>>(emptyList())
    /**
     * Live amplitude samples (0..32767, from `MediaRecorder.getMaxAmplitude()`)
     * published every [SAMPLE_PERIOD_MS]. Clears to empty on [stop].
     */
    val amplitudes: StateFlow<List<Int>> = _amplitudes.asStateFlow()

    /**
     * Start recording into [outputPath]. The file is overwritten if it
     * exists. Called from the main thread by the PTT composable when the
     * user presses the FAB.
     *
     * @throws IllegalStateException when a recording is already in flight.
     */
    fun start(outputPath: File) {
        if (recorder != null) {
            throw IllegalStateException("AudioRecorder is already running")
        }
        outputPath.parentFile?.mkdirs()
        if (outputPath.exists()) outputPath.delete()

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ)
            recorder.setAudioEncodingBitRate(AAC_BITRATE_BPS)
            recorder.setAudioChannels(1)
            recorder.setOutputFile(outputPath.absolutePath)
            recorder.prepare()
            recorder.start()
        } catch (t: Throwable) {
            runCatching { recorder.release() }
            throw IllegalStateException("Failed to start MediaRecorder", t)
        }

        this.recorder = recorder
        this.outputFile = outputPath
        this.startedAtMs = System.currentTimeMillis()
        _amplitudes.value = emptyList()

        val scope = CoroutineScope(Dispatchers.IO)
        recordingScope = scope
        pollingJob = scope.launch {
            val buffer = ArrayDeque<Int>()
            while (true) {
                delay(SAMPLE_PERIOD_MS)
                val current = this@AudioRecorder.recorder ?: break
                val amp = runCatching { current.maxAmplitude }.getOrDefault(0)
                if (buffer.size >= MAX_SAMPLES) buffer.removeFirst()
                buffer.addLast(amp)
                _amplitudes.value = buffer.toList()
            }
        }
    }

    /**
     * Stop the recorder and return the captured audio. Safe to call
     * even when no recording is active — returns null in that case.
     */
    fun stop(): RecordedAudio? {
        val active = recorder ?: return null
        val file = outputFile
        val started = startedAtMs
        val snapshotSamples = _amplitudes.value

        pollingJob?.cancel()
        pollingJob = null
        recordingScope = null

        recorder = null
        outputFile = null
        startedAtMs = 0L
        _amplitudes.value = emptyList()

        val durationMs = (System.currentTimeMillis() - started).coerceAtLeast(0L)
        val stopSucceeded = runCatching {
            active.stop()
        }.onFailure { t ->
            Log.w(LOG_TAG, "MediaRecorder.stop() failed", t)
        }.isSuccess
        runCatching { active.reset() }
        runCatching { active.release() }

        if (!stopSucceeded || file == null || !file.exists() || file.length() == 0L) {
            return null
        }
        return RecordedAudio(file = file, durationMs = durationMs, amplitudeSamples = snapshotSamples)
    }

    /** Abort any in-flight recording and delete the partial file. Idempotent. */
    fun cancel() {
        val active = recorder ?: return
        val file = outputFile

        pollingJob?.cancel()
        pollingJob = null
        recordingScope = null

        recorder = null
        outputFile = null
        startedAtMs = 0L
        _amplitudes.value = emptyList()

        runCatching { active.stop() }
        runCatching { active.reset() }
        runCatching { active.release() }
        file?.let { runCatching { it.delete() } }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val AAC_BITRATE_BPS = 64_000
        const val SAMPLE_PERIOD_MS = 50L
        const val MAX_SAMPLES = 96
        private const val LOG_TAG = "AudioRecorder"
    }
}

/**
 * Result of a successful [AudioRecorder.stop] call. [amplitudeSamples]
 * is the snapshot captured during recording; the UI can keep showing it
 * in the "review transcript" state instead of going blank.
 */
data class RecordedAudio(
    val file: File,
    val durationMs: Long,
    val amplitudeSamples: List<Int>,
)
