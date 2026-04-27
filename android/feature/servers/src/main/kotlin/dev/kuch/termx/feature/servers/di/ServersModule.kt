package dev.kuch.termx.feature.servers.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.feature.servers.MoshPreflight
import dev.kuch.termx.feature.servers.MoshPreflightImpl

/**
 * Hilt bindings for [:feature:servers].
 *
 * Currently only wires [MoshPreflight] so view-models can `@Inject`
 * the interface (and unit tests can pass a one-liner stub) while
 * production resolves to [MoshPreflightImpl].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServersModule {

    @Binds
    abstract fun bindMoshPreflight(impl: MoshPreflightImpl): MoshPreflight
}
