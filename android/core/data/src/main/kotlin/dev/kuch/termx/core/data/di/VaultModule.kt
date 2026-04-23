package dev.kuch.termx.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kuch.termx.core.data.vault.KeystoreSecretVault
import dev.kuch.termx.core.data.vault.SecretVault
import javax.inject.Singleton

/**
 * Binds the Keystore-backed [KeystoreSecretVault] against the public
 * [SecretVault] abstraction so callers depend only on the interface.
 *
 * [VaultLockState][dev.kuch.termx.core.data.vault.VaultLockState],
 * [AppPreferences][dev.kuch.termx.core.data.prefs.AppPreferences], and
 * [VaultLifecycleObserver][dev.kuch.termx.core.data.vault.VaultLifecycleObserver]
 * already carry `@Singleton` + `@Inject` constructor annotations, so
 * Hilt resolves them without explicit entries here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VaultModule {

    @Binds
    @Singleton
    abstract fun bindSecretVault(impl: KeystoreSecretVault): SecretVault
}
