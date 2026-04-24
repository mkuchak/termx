package dev.kuch.termx.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.core.data.remote.InstallCompanionUseCaseImpl
import dev.kuch.termx.core.data.repository.KeyPairRepositoryImpl
import dev.kuch.termx.core.data.repository.ServerGroupRepositoryImpl
import dev.kuch.termx.core.data.repository.ServerRepositoryImpl
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import javax.inject.Singleton

/**
 * Binds the `:core:domain` repository contracts to their `:core:data`
 * implementations so feature modules only see the domain interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindServerRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    @Singleton
    abstract fun bindKeyPairRepository(impl: KeyPairRepositoryImpl): KeyPairRepository

    @Binds
    @Singleton
    abstract fun bindServerGroupRepository(impl: ServerGroupRepositoryImpl): ServerGroupRepository

    @Binds
    @Singleton
    abstract fun bindInstallCompanionUseCase(
        impl: InstallCompanionUseCaseImpl,
    ): InstallCompanionUseCase
}
