package dev.kuch.termx.feature.updater

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Streams the signed-release APK into `cacheDir/updates/` so the
 * system installer can read it via FileProvider.
 *
 * Cache lives in `cacheDir` (not `filesDir`) so Android can evict it
 * under low-storage pressure — a downloaded-but-not-installed APK
 * isn't worth keeping forever. The temp `.part` file shields against
 * a half-downloaded APK being handed to the installer if the network
 * drops mid-stream.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    baseClient: OkHttpClient,
) {

    /**
     * The shared `NetworkModule` client has a 10s call timeout suited
     * to small JSON pings; an APK on a slow cellular connection can
     * easily exceed that. We borrow the connection pool but extend
     * the per-call wall clock to 5 minutes (enough for ~12 MB even
     * on dialup-tier links).
     */
    private val client: OkHttpClient = baseClient.newBuilder()
        .callTimeout(5, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Snapshot emitted while downloading. `bytesTotal == -1L` when the
     * server didn't send Content-Length (treat as indeterminate).
     */
    data class Progress(val bytesRead: Long, val bytesTotal: Long)

    /**
     * Stream the asset at [url] into a deterministic file inside
     * `cacheDir/updates/`. Emits intermediate progress; the final
     * emission carries `bytesRead == bytesTotal` (or final byte count
     * when total was unknown). Throws on HTTP / IO failure — the
     * caller turns it into an error state.
     */
    fun download(url: String, version: String): Flow<Progress> = flow {
        val targetDir = File(context.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }
        val finalFile = File(targetDir, "termx-${version}.apk")
        val partFile = File(targetDir, "termx-${version}.apk.part")
        // Drop any leftover from a previous attempt — the user's
        // download intent supersedes whatever's there.
        runCatching { partFile.delete() }
        runCatching { finalFile.delete() }

        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        // `total` survives past the response.close() so the trailing
        // emit can still report Content-Length; declared outside the
        // try / finally that owns the response handle.
        var total = -1L
        try {
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} downloading ${response.request.url}")
            }
            total = response.body?.contentLength() ?: -1L
            val source = response.body?.source() ?: throw IOException("Empty response body")
            partFile.outputStream().use { out ->
                val buf = ByteArray(8 * 1024)
                var read = 0L
                var lastEmittedAt = 0L
                val sourceStream = source.inputStream()
                while (true) {
                    val n = sourceStream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    read += n
                    val now = System.currentTimeMillis()
                    // Throttle progress emissions so we don't drown
                    // Compose recompositions during a 12 MB download.
                    if (now - lastEmittedAt > PROGRESS_EMIT_INTERVAL_MS || (total > 0 && read == total)) {
                        emit(Progress(bytesRead = read, bytesTotal = total))
                        lastEmittedAt = now
                    }
                }
                out.flush()
            }
        } finally {
            response.close()
        }

        if (!partFile.renameTo(finalFile)) {
            throw IOException("Failed to rename ${partFile.absolutePath} -> ${finalFile.absolutePath}")
        }
        Log.i(LOG_TAG, "downloaded $url to ${finalFile.absolutePath}")
        emit(
            Progress(
                bytesRead = finalFile.length(),
                bytesTotal = if (total > 0) total else finalFile.length(),
            ),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Resolve where a previously-downloaded APK for [version] would
     * live. Returns null if it isn't actually present (cache eviction,
     * fresh install, …).
     */
    fun cachedFile(version: String): File? {
        val target = File(File(context.cacheDir, UPDATES_SUBDIR), "termx-${version}.apk")
        return if (target.exists() && target.length() > 0) target else null
    }

    companion object {
        /**
         * Subdirectory under `cacheDir/`. Must match the `path=`
         * attribute in `res/xml/file_paths.xml` — FileProvider only
         * exposes URIs for files under that exact directory.
         */
        const val UPDATES_SUBDIR = "updates"

        private const val LOG_TAG = "ApkDownloader"
        private const val PROGRESS_EMIT_INTERVAL_MS = 250L
    }
}
