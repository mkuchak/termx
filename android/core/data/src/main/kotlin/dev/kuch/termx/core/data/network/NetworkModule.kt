package dev.kuch.termx.core.data.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * Process-wide [OkHttpClient] used by [TermxReleaseFetcher] and any future
 * "small JSON call" surface (Gemini PTT uses its own SDK and will not share
 * this client).
 *
 * Aggressively short timeouts: a 10 s wall-clock budget matches the wizard's
 * "did the internet die?" UX, and GitHub's releases endpoint is comfortably
 * sub-second under normal conditions.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
}
