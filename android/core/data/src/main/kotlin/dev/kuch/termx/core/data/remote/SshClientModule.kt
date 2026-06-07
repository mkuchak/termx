package dev.kuch.termx.core.data.remote

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.libs.sshnative.SshClient
import javax.inject.Singleton

/**
 * Process-wide [SshClient] provider.
 *
 * Registering BouncyCastle on the JCA is idempotent across [SshClient]
 * construction, but using a single instance keeps provider-ordering
 * deterministic and saves the (small) init cost for callers like
 * [InstallCompanionUseCaseImpl] that only need the connect entry point.
 */
@Module
@InstallIn(SingletonComponent::class)
object SshClientModule {

    @Provides
    @Singleton
    fun provideSshClient(): SshClient = SshClient()
}
