package dev.kuch.termx.core.data.remote

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.core.domain.repository.TmuxSessionRepository
import dev.kuch.termx.libs.sshnative.SshClient
import javax.inject.Singleton

/**
 * Binds the domain-level [TmuxSessionRepository] interface to the
 * sshj-backed implementation (Task #25).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TmuxRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTmuxSessionRepository(
        impl: TmuxSessionRepositoryImpl,
    ): TmuxSessionRepository
}

/**
 * Process-wide [SshClient] provider.
 *
 * Registering BouncyCastle on the JCA is idempotent across [SshClient]
 * construction, but using a single instance keeps provider-ordering
 * deterministic and saves the (small) init cost for callers like
 * [TmuxSessionRepositoryImpl] that only need the connect entry point.
 */
@Module
@InstallIn(SingletonComponent::class)
object SshClientModule {

    @Provides
    @Singleton
    fun provideSshClient(): SshClient = SshClient()
}
