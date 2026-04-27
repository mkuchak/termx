package dev.kuch.termx.feature.updater

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Latest-release lookup against GitHub's public Releases API.
 *
 * `releases/latest` excludes drafts and pre-releases by spec, so we
 * never accidentally surface a non-stable tag. The endpoint returns a
 * single JSON object with `tag_name`, `assets[]`, etc. — we pick the
 * single asset whose name ends in `.apk` (matches our release-it
 * output `termx-v<version>-release.apk`).
 *
 * Rate limit: 60 req/hour for unauthenticated callers. With one
 * cold-start check per device per 24h plus optional manual "check
 * now" taps, a single user is nowhere near it.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Compare `releases/latest` to [installedVersion]. Returns
     * [Result.Available] only when the upstream tag is strictly
     * newer; equal or older returns [Result.UpToDate]. Network /
     * parse failures collapse to [Result.Error] — we never throw
     * up the call stack since this runs at cold start and shouldn't
     * blow up the app.
     */
    suspend fun check(installedVersion: String): Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val raw = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Error("GitHub HTTP ${resp.code}")
                }
                resp.body?.string().orEmpty()
            }
        } catch (ioe: IOException) {
            Log.w(LOG_TAG, "update check network error", ioe)
            return@withContext Result.Error(ioe.message ?: "Network error")
        }
        parse(raw, installedVersion)
    }

    /**
     * `internal` so [UpdateCheckerTest] can hammer the JSON shape
     * paths without spinning up a mock HTTP server.
     */
    internal fun parse(body: String, installedVersion: String): Result {
        val tag: String
        val asset: AssetMeta?
        try {
            val root = json.parseToJsonElement(body).jsonObject
            tag = root["tag_name"]?.jsonPrimitive?.content
                ?: return Result.Error("Response missing tag_name")
            val assets = root["assets"]?.jsonArray
                ?: return Result.Error("Response missing assets")
            asset = assets
                .map { it.jsonObject }
                .firstOrNull { obj ->
                    obj["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
                }
                ?.let { obj ->
                    AssetMeta(
                        url = obj["browser_download_url"]?.jsonPrimitive?.content
                            ?: return Result.Error("Asset missing browser_download_url"),
                        sizeBytes = obj["size"]?.jsonPrimitive?.content?.toLongOrNull()
                            ?: return Result.Error("Asset missing size"),
                    )
                }
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "update check parse error", t)
            return Result.Error("Malformed response: ${t.message}")
        }

        if (asset == null) return Result.Error("Latest release has no .apk asset")

        return if (VersionTag.isNewer(candidate = tag, installed = installedVersion)) {
            Result.Available(version = tag, downloadUrl = asset.url, sizeBytes = asset.sizeBytes)
        } else {
            Result.UpToDate
        }
    }

    sealed interface Result {
        data class Available(
            val version: String,
            val downloadUrl: String,
            val sizeBytes: Long,
        ) : Result

        data object UpToDate : Result

        data class Error(val reason: String) : Result
    }

    private data class AssetMeta(val url: String, val sizeBytes: Long)

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/mkuchak/termx/releases/latest"
        private const val LOG_TAG = "UpdateChecker"
    }
}
