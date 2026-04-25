package dev.kuch.termx.feature.ptt

import android.util.Base64
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Task #40 — thin client around Gemini's `generateContent` endpoint for
 * single-shot audio transcription.
 *
 * Model: `gemini-2.5-flash-lite` per the Phase 6 grilled decision. The
 * `flash-lite` tier is the cheapest multimodal Gemini; single-call
 * transcribe-and-clean fits its token budget comfortably for the 30-second
 * PTT cap.
 *
 * We do not share the process-wide [OkHttpClient] from
 * [dev.kuch.termx.core.data.network.NetworkModule] because that client has
 * a 10-second call timeout suited to small JSON pings — audio upload needs
 * a wider budget. We take its connect pool as a dependency by cloning.
 */
@Singleton
class GeminiClient @Inject constructor(
    baseClient: OkHttpClient,
) {

    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Upload [audioFile] (MP4/AAC produced by [AudioRecorder]) to Gemini
     * and return the cleaned transcript. Blocks for up to
     * [TIMEOUT_SECONDS] seconds.
     *
     * @throws GeminiException for empty API key, HTTP failures, missing
     *   transcript in the response, or network errors.
     */
    suspend fun transcribe(apiKey: String, audioFile: File): String =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                throw GeminiException("Gemini API key is not configured")
            }
            if (!audioFile.exists() || audioFile.length() == 0L) {
                throw GeminiException("Recorded audio file is empty")
            }

            val audioBase64 = Base64.encodeToString(
                audioFile.readBytes(),
                Base64.NO_WRAP,
            )
            val payload = buildRequestPayload(audioBase64)
            val url = "$ENDPOINT_BASE$MODEL:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val responseBody = try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw GeminiException(
                            "Gemini HTTP ${response.code}: ${body.take(GeminiException.SNIPPET_MAX)}",
                        )
                    }
                    body
                }
            } catch (ioe: IOException) {
                throw GeminiException("Network error talking to Gemini: ${ioe.message}", ioe)
            }

            extractTranscript(responseBody)
        }

    private fun buildRequestPayload(audioBase64: String): JsonObject = buildJsonObject {
        put(
            "contents",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put(
                            "parts",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("text", JsonPrimitive(PROMPT))
                                    },
                                )
                                add(
                                    buildJsonObject {
                                        put(
                                            "inline_data",
                                            buildJsonObject {
                                                put("mime_type", JsonPrimitive(AUDIO_MIME_TYPE))
                                                put("data", JsonPrimitive(audioBase64))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
        put(
            "generationConfig",
            buildJsonObject {
                put("temperature", JsonPrimitive(0.0))
                put("topP", JsonPrimitive(1.0))
            },
        )
    }

    private fun extractTranscript(body: String): String {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse {
                throw GeminiException("Gemini response was not JSON: ${body.take(GeminiException.SNIPPET_MAX)}")
            }
        val candidates = parsed["candidates"] as? JsonArray
            ?: throw GeminiException("Gemini response missing 'candidates': ${body.take(GeminiException.SNIPPET_MAX)}")
        val firstCandidate = candidates.firstOrNull()?.jsonObject
            ?: throw GeminiException("Gemini returned no candidates")
        val parts = firstCandidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: throw GeminiException("Gemini candidate missing 'content.parts'")
        val text = parts
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString(separator = "")
            .trim()
        if (text.isEmpty()) {
            throw GeminiException("Gemini returned empty transcript")
        }
        return text
    }

    companion object {
        const val MODEL = "gemini-2.5-flash-lite"
        const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val AUDIO_MIME_TYPE = "audio/mp4"
        const val TIMEOUT_SECONDS = 30L

        /**
         * Instruction sent alongside the inline audio. We want filler
         * words removed because the transcript is shell-bound —
         * "um run tests" must become "run tests".
         *
         * Also: instruct the model to emit a literal NO_SPEECH sentinel
         * when handed silent / room-tone audio. Without this guard,
         * Gemini will fabricate a plausible-sounding transcript ("The
         * first thing that comes to mind…") because the prompt asks
         * for one — better to surface "no speech detected" than to
         * inject hallucinated text into the user's PTY.
         */
        const val PROMPT =
            "Transcribe the speech in this audio and clean up filler words, " +
                "stutters, and false starts. " +
                "If the audio contains no intelligible speech (silence, " +
                "room tone, or background noise only), return the literal " +
                "text NO_SPEECH and nothing else. " +
                "Otherwise return ONLY the cleaned transcript. " +
                "No preamble, no quotes, no commentary."

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Thrown by [GeminiClient.transcribe] on any failure. Message is safe
 * to surface to the user; the snippet of the response body is capped at
 * [SNIPPET_MAX] characters to avoid painting a whole error page into a
 * snackbar.
 */
class GeminiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val SNIPPET_MAX = 240
    }
}
