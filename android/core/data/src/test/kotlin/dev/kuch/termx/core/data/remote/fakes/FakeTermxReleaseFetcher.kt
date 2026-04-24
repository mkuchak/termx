package dev.kuch.termx.core.data.remote.fakes

import dev.kuch.termx.core.data.network.TermxReleaseFetcher
import okhttp3.OkHttpClient

/**
 * Drop-in replacement for [TermxReleaseFetcher] that never touches the
 * network. Extends the real class so the use-case (which holds a
 * concrete `TermxReleaseFetcher` field, not an interface) accepts it.
 *
 * Pass [canned] to return a pre-built release; set [failure] to a
 * non-null Throwable to have `fetchLatest` throw.
 */
class FakeTermxReleaseFetcher(
    private val canned: TermxReleaseFetcher.ReleaseInfo = DEFAULT_RELEASE,
    private val failure: Throwable? = null,
) : TermxReleaseFetcher(OkHttpClient()) {

    override suspend fun fetchLatest(): ReleaseInfo {
        failure?.let { throw it }
        return canned
    }

    companion object {
        val DEFAULT_RELEASE = TermxReleaseFetcher.ReleaseInfo(
            tag = "termxd-v0.1.0",
            assets = listOf(
                TermxReleaseFetcher.Asset(
                    name = "termxd_Linux_arm64.tar.gz",
                    downloadUrl = "https://example.test/termxd_Linux_arm64.tar.gz",
                ),
                TermxReleaseFetcher.Asset(
                    name = "termxd_Linux_x86_64.tar.gz",
                    downloadUrl = "https://example.test/termxd_Linux_x86_64.tar.gz",
                ),
            ),
        )
    }
}
