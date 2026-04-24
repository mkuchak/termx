package dev.kuch.termx.feature.keys.unlock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.VaultLockState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Orchestrates the biometric prompt and surfaces its result as a
 * [UiState] that the composable can observe.
 *
 * The ViewModel does NOT own the prompt instance — that is constructed
 * on-demand with the current [FragmentActivity] (required by
 * androidx.biometric) at the point of the user tapping "Unlock". We
 * only flip [VaultLockState] on success so the rest of the app routes
 * off the unlock screen.
 *
 * The vault is no longer backed by an Android Keystore key (see
 * [FileSystemSecretVault] for why), so this prompt is a pure app-level
 * gate — no [BiometricPrompt.CryptoObject] is needed and no Keystore
 * operation is bound to the authentication result.
 */
@HiltViewModel
class BiometricUnlockViewModel @Inject constructor(
    private val vaultLockState: VaultLockState,
) : ViewModel() {

    data class UiState(
        val status: Status = Status.Idle,
        val errorMessage: String? = null,
    )

    enum class Status { Idle, Prompting, Failed }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * Show the system biometric / device-credential sheet. Must be called
     * on the main thread with the enclosing [FragmentActivity].
     */
    fun promptUnlock(activity: FragmentActivity) {
        // Re-entrancy guard: the system prompt is already visible.
        if (_uiState.value.status == Status.Prompting) return

        val canAuth = BiometricManager.from(activity)
            .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            _uiState.value = UiState(
                status = Status.Failed,
                errorMessage = biometricAvailabilityMessage(canAuth),
            )
            return
        }

        _uiState.value = UiState(status = Status.Prompting)
        vaultLockState.markUnlocking()

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    viewModelScope.launch {
                        vaultLockState.markUnlocked()
                        _uiState.value = UiState(status = Status.Idle)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancellations are expected; don't surface as "failed".
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    vaultLockState.lock()
                    _uiState.value = UiState(
                        status = if (cancelled) Status.Idle else Status.Failed,
                        errorMessage = if (cancelled) null else errString.toString(),
                    )
                }

                override fun onAuthenticationFailed() {
                    // Single rejection: let the system retry, don't tear down state.
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock termx")
            .setSubtitle("Biometric or device credential required")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(info)
    }

    private fun biometricAvailabilityMessage(code: Int): String = when (code) {
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            "This device has no biometric or credential hardware."
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "Biometric hardware is currently unavailable."
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "No biometrics or device credential enrolled. Set one up in system settings."
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            "A security update is required before termx can use biometrics."
        else -> "Biometric authentication is unavailable (code $code)."
    }
}
