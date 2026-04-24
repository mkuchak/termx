package dev.kuch.termx.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.core.data.db.ALL_MIGRATIONS
import dev.kuch.termx.core.data.db.AppDatabase
import dev.kuch.termx.core.data.db.dao.CustomThemeDao
import dev.kuch.termx.core.data.db.dao.KeyPairDao
import dev.kuch.termx.core.data.db.dao.ServerDao
import dev.kuch.termx.core.data.db.dao.ServerGroupDao
import javax.inject.Singleton

/**
 * Wires the singleton [AppDatabase] and exposes each DAO as its own provider
 * so repository classes can depend on the DAO directly.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "termx.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideServerDao(db: AppDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideKeyPairDao(db: AppDatabase): KeyPairDao = db.keyPairDao()

    @Provides
    fun provideServerGroupDao(db: AppDatabase): ServerGroupDao = db.serverGroupDao()

    @Provides
    fun provideCustomThemeDao(db: AppDatabase): CustomThemeDao = db.customThemeDao()
}
