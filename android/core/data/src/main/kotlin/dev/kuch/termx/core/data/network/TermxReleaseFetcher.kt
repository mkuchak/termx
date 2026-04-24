package dev.kuch.termx.core.data.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the latest termxd release from the mkuchak/termx GitHub repo.
 *
 * Why a custom filter? The repo ships two independent release tracks:
 *
 *  - `v*.*.*` — Android APK releases (ignored here).
 *  - `termxd-v*.*.*` — Go companion releases (the only thing we want).
 *
 *  The `/releases/latest` endpoint returns whichever track cut the newest tag,
 *  which is unreliable. Instead we pull the first 50 entries (more than enough
 *  given either track ships a handful of versions per month) and pick the
 *  first one whose `tag_name` starts with `termxd-v`.
 *
 *  The `assetForArch` lambda maps an architecture token (`amd64`, `arm64`) to
 *  the matching release asset's `browser_download_url`. GoReleaser (Task #29)
 *  names assets like `termxd_Linux_x86_64.tar.gz` / `termxd_Linux_arm64.tar.gz`
 *  — we match by case-insensitive substring, so any naming wobble on the
 *  goreleaser config doesn't break downstream UX overnight.
 */
@Singleton
class TermxReleaseFetcher @Inject constructor(private val http: OkHttpClient) {

    /**
     * Pulls the latest termxd-v\* release. [fallbackUrl] (if provided) is
     * returned as the single asset when the GitHub API call is unreachable —
     * useful for air-gapped lab setups that mirror the binary locally.
     */
    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/mkuchak/termx/releases?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "termx-android")
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("GitHub API returned ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            parseReleases(body)
                ?: throw IOException("No termxd-v* release found in GitHub API response")
        }
    }

    /**
     * Parses the `/releases` JSON array, returns the first entry whose
     * `tag_name` begins with `termxd-v`.
     *
     * `internal` visibility so unit tests (outside of this task) can exercise
     * the mapping without constructing an OkHttp client.
     */
    internal fun parseReleases(json: String): ReleaseInfo? {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        val arr = (root as? JsonArray) ?: return null

        for (element in arr) {
            val obj = element.jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!tag.startsWith("termxd-v")) continue
            val assets = (obj["assets"] as? JsonArray) ?: JsonArray(emptyList())
            return ReleaseInfo(
                tag = tag,
                assets = assets.map { it.jsonObject.toAsset() },
            )
        }
        return null
    }

    private fun JsonObject.toAsset(): Asset = Asset(
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        downloadUrl = this["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    data class Asset(val name: String, val downloadUrl: String)

    data class ReleaseInfo(val tag: String, val assets: List<Asset>) {
        /**
         * Resolve the download URL for [arch] (`amd64` / `arm64`).
         *
         *  - `amd64` also matches `x86_64` (goreleaser's default token).
         *  - `arm64` also matches `aarch64`.
         */
        fun assetForArch(arch: String): String? {
            val tokens = when (arch) {
                "amd64" -> listOf("amd64", "x86_64")
                "arm64" -> listOf("arm64", "aarch64")
                else -> listOf(arch)
            }
            val linuxAsset = assets.firstOrNull { a ->
                val lower = a.name.lowercase()
                lower.contains("linux") && tokens.any { lower.contains(it.lowercase()) }
            } ?: assets.firstOrNull { a ->
                // Last-ditch: no `linux` token but the arch matches.
                tokens.any { a.name.lowercase().contains(it.lowercase()) }
            }
            return linuxAsset?.downloadUrl
        }
    }
}
