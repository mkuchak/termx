package dev.kuch.termx.core.data.vault

/**
 * Thrown by [SecretVault] when callers attempt to read or write secrets
 * while [VaultLockState.state] is anything other than
 * [VaultLockState.State.Unlocked]. The UI layer is expected to navigate
 * to the biometric unlock screen and retry.
 */
class VaultLockedException(message: String = "Vault is locked") : IllegalStateException(message)
