package dev.kuch.termx.libs.companion.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.libs.companion.EventStreamClientFactory
import javax.inject.Singleton

/**
 * Hilt bindings for `:libs:companion`.
 *
 * Today: only the [EventStreamClientFactory]. Kept as an `object` with
 * `@Provides` (rather than `@Binds`) because the factory has a no-arg
 * constructor and doesn't implement a separate interface — the binding
 * is trivial and this form avoids an abstract-class-plus-interface
 * dance for a single value.
 */
@Module
@InstallIn(SingletonComponent::class)
object CompanionModule {

    @Provides
    @Singleton
    fun provideEventStreamClientFactory(): EventStreamClientFactory = EventStreamClientFactory()
}
