package dev.kuch.termx.core.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the absolute path to the known_hosts file used by
 * every sshj connect in the app.
 *
 * Extracted from the Android [Context] so JVM-only unit tests can pass
 * a plain temp-dir path without spinning up Robolectric — see
 * `InstallCompanionUseCaseImpl` tests in `:core:data`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KnownHostsPath

@Module
@InstallIn(SingletonComponent::class)
object KnownHostsModule {

    @Provides
    @Singleton
    @KnownHostsPath
    fun provideKnownHostsPath(@ApplicationContext context: Context): String =
        context.filesDir.absolutePath + "/known_hosts"
}
