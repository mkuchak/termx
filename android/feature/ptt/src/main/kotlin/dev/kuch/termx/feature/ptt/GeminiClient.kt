package dev.kuch.termx.feature.ptt

import android.util.Base64
import dev.kuch.termx.core.domain.ptt.PttLanguage
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Thin client around Gemini's `generateContent` endpoint for single-shot
 * audio transcription and translation.
 *
 * The single old "transcribe English with filler-word cleanup" prompt was
 * replaced by two builders that mirror github.com/mkuchak/push-to-talk:
 *
 *  - [buildTranscribePrompt] when source == target — plain transcription,
 *    spelling/grammar cleanup, no rephrasing.
 *  - [buildTranslatePrompt] when source != target — translates the audio
 *    into the target language with an explicit "treat the entire input as
 *    [source]" guard so Gemini doesn't bail on bilingual passages.
 *
 * Both prompts append the termx-specific NO_SPEECH sentinel instruction:
 * silent audio MUST collapse to the literal string `NO_SPEECH` so
 * [PttViewModel] can surface a friendly error instead of injecting one of
 * Gemini's plausible-sounding hallucinations into the user's PTY (see
 * v1.1.9 fix).
 *
 * Each call is wrapped in a 3-attempt retry loop with 500ms / 1200ms
 * backoff (±20% jitter) on transient failures: HTTP 429/5xx, IOException,
 * and bodies matching `overloaded|unavailable|try again|fetch failed`.
 * Hard failures (auth, malformed request, empty transcript) bail
 * immediately. The optional [onAttempt] callback lets the caller update
 * UI between attempts.
 *
 * We do not share the process-wide [OkHttpClient] from
 * [dev.kuch.termx.core.data.network.NetworkModule] because that client has
 * a 10-second call timeout suited to small JSON pings — audio upload
 * needs a wider budget.
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
     * and return the cleaned transcript / translation. Blocks for up to
     * [TIMEOUT_SECONDS] seconds per attempt × [MAX_ATTEMPTS] attempts.
     *
     * @param sourceLanguage BCP-47 locale the audio is spoken in
     *   (e.g. `"en-US"`).
     * @param targetLanguage BCP-47 locale to output in. Equal to
     *   [sourceLanguage] selects the transcribe prompt; different
     *   selects the translate prompt.
     * @param context Optional free-text "domain hints" appended to the
     *   prompt to help Gemini get jargon right. Blanks are dropped.
     * @param onAttempt Fires before each network attempt with the
     *   attempt number (1-based). UI uses this to show
     *   "Retrying… (2/3)".
     *
     * @throws GeminiException for empty API key, persistent HTTP
     *   failures, missing transcript in the response, or network errors
     *   that exhaust the retry budget.
     */
    suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        sourceLanguage: String,
        targetLanguage: String,
        context: String,
        onAttempt: suspend (Int) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            throw GeminiException("Gemini API key is not configured")
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            throw GeminiException("Recorded audio file is empty")
        }

        val source = PttLanguage.normalise(sourceLanguage)
        val target = PttLanguage.normalise(targetLanguage)
        val prompt = buildPrompt(source, target, context)

        val audioBase64 = Base64.encodeToString(
            audioFile.readBytes(),
            Base64.NO_WRAP,
        )
        val payload = buildRequestPayload(prompt, audioBase64)
        val url = "$ENDPOINT_BASE$MODEL:generateContent?key=$apiKey"
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        executeWithRetry(request, onAttempt).let(::extractTranscript)
    }

    /**
     * Pick the correct prompt template and append the optional context
     * appendix. Pure function — no Gemini I/O. `internal` so the
     * `GeminiClientTest` suite can verify language interpolation +
     * NO_SPEECH guard + context appendix without spinning up OkHttp.
     */
    internal fun buildPrompt(source: String, target: String, context: String): String {
        val base = if (source == target) {
            buildTranscribePrompt(source)
        } else {
            buildTranslatePrompt(source, target)
        }
        val ctx = context.trim()
        return if (ctx.isEmpty()) base else base + contextAppendix(ctx)
    }

    internal fun buildTranscribePrompt(code: String): String {
        val name = PttLanguage.fullName[code] ?: code
        return "You are a precise transcription assistant. Transcribe the " +
            "following audio faithfully in $name ($code). Fix any obvious " +
            "spelling or grammatical errors while preserving the speaker's " +
            "original meaning, tone, and style. Pay close attention to the " +
            "speaker's intonation to distinguish questions from statements, " +
            "and punctuate accordingly. Do not add, remove, or rephrase " +
            "content beyond error corrections. Output only the corrected " +
            "transcription, nothing else — no quotes, no labels, no " +
            "explanation. " + NO_SPEECH_GUARD
    }

    internal fun buildTranslatePrompt(from: String, to: String): String {
        val fromName = PttLanguage.fullName[from] ?: from
        val toName = PttLanguage.fullName[to] ?: to
        val fromShort = PttLanguage.shortName(from)
        val toShort = PttLanguage.shortName(to)
        return "You are a precise translation assistant. The following audio " +
            "is spoken in $fromName ($from). You MUST translate it into " +
            "$toName ($to). Even if parts of the audio sound like $toShort, " +
            "treat the entire input as $fromShort and translate everything " +
            "to $toShort. Produce a faithful, natural-sounding translation " +
            "that preserves the speaker's meaning, tone, and intent. Pay " +
            "close attention to the speaker's intonation to distinguish " +
            "questions from statements, and punctuate accordingly. Fix any " +
            "obvious errors. Output ONLY the translated text in $toShort " +
            "— no quotes, no labels, no explanation, no original " +
            "$fromShort text. " + NO_SPEECH_GUARD
    }

    internal fun contextAppendix(ctx: String): String =
        "\n\nThe speaker may reference the following domain-specific terms, " +
            "names, or context. Use this to improve transcription accuracy " +
            "and ensure these terms are spelled correctly:\n\"\"\"\n$ctx\n\"\"\""

    private suspend fun executeWithRetry(
        request: Request,
        onAttempt: suspend (Int) -> Unit,
    ): String {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            onAttempt(attempt)
            try {
                return executeOnce(request)
            } catch (t: Throwable) {
                lastError = t
                val retryable = t is RetryableGeminiException && attempt < MAX_ATTEMPTS
                if (!retryable) {
                    if (t is RetryableGeminiException) {
                        // Final attempt — surface the inner cause with attempt count.
                        throw GeminiException(
                            "Failed after $MAX_ATTEMPTS attempts: ${t.message}",
                            t.cause,
                        )
                    }
                    throw t
                }
                delay(jitteredBackoff(attempt))
            }
        }
        // Unreachable — the loop above either returns or throws.
        throw GeminiException(
            "Gemini call exhausted retry budget",
            lastError,
        )
    }

    private fun executeOnce(request: Request): String {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (ioe: IOException) {
            throw RetryableGeminiException("Network error talking to Gemini: ${ioe.message}", ioe)
        }
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return body
            }
            val snippet = body.take(GeminiException.SNIPPET_MAX)
            val message = "Gemini HTTP ${resp.code}: $snippet"
            if (isRetryableHttp(resp.code) || isRetryableBody(body)) {
                throw RetryableGeminiException(message)
            }
            throw GeminiException(message)
        }
    }

    /** 500ms on attempt 2, 1200ms on attempt 3, ±20% jitter. */
    internal fun jitteredBackoff(attempt: Int): Long {
        val base = when (attempt) {
            1 -> 500L
            else -> 1200L
        }
        val jitter = base * 0.2
        val offset = Random.nextDouble(-jitter, jitter)
        return (base + offset).toLong().coerceAtLeast(0L)
    }

    internal fun isRetryableHttp(code: Int): Boolean =
        code == 429 || code in 500..504

    internal fun isRetryableBody(body: String): Boolean =
        RETRYABLE_BODY_REGEX.containsMatchIn(body)

    private fun buildRequestPayload(prompt: String, audioBase64: String): JsonObject =
        buildJsonObject {
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
                                            put("text", JsonPrimitive(prompt))
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

    internal fun extractTranscript(body: String): String {
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
        const val MODEL = "gemini-3.1-flash-lite-preview"
        const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val AUDIO_MIME_TYPE = "audio/mp4"
        const val TIMEOUT_SECONDS = 30L
        const val MAX_ATTEMPTS = 3

        /**
         * Sentinel guard appended to BOTH transcribe and translate
         * prompts. Gemini, when handed silent / room-tone audio, will
         * happily fabricate a plausible-sounding transcript ("The first
         * thing that comes to mind…") because the prompt asks for one —
         * surfacing this literal lets [PttViewModel] bail with a
         * friendly error instead of injecting hallucinated text into
         * the user's PTY.
         */
        const val NO_SPEECH_GUARD =
            "If the audio contains no intelligible speech (silence, " +
                "room tone, or background noise only), return the literal " +
                "text NO_SPEECH and nothing else."

        /**
         * Bodies whose error text matches this regex are retried even
         * if the HTTP code wouldn't normally trigger a retry — covers
         * Gemini's habit of returning 200 with a JSON error envelope on
         * transient backend issues.
         */
        private val RETRYABLE_BODY_REGEX =
            Regex("overloaded|unavailable|try again|fetch failed", RegexOption.IGNORE_CASE)

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Thrown by [GeminiClient.transcribe] on any non-recoverable failure.
 * Message is safe to surface to the user; the snippet of the response
 * body is capped at [SNIPPET_MAX] characters to avoid painting a whole
 * error page into a snackbar.
 */
class GeminiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val SNIPPET_MAX = 240
    }
}

/**
 * Internal marker for failures that should trigger another attempt.
 * Never escapes [GeminiClient] — the retry loop either succeeds, or
 * collapses this into a user-facing [GeminiException] on the final
 * attempt.
 */
private class RetryableGeminiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
