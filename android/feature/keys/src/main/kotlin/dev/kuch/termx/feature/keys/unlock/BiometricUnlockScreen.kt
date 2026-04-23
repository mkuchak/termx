package dev.kuch.termx.feature.keys.unlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.kuch.termx.core.data.vault.VaultLockState

/**
 * Minimal unlock screen: small mark, title, explainer, a single "Unlock"
 * button. Auto-prompts on first composition so the natural app-launch
 * flow is "biometric sheet appears immediately"; the button exists for
 * retry after cancel / error.
 *
 * @param onUnlocked Called when [VaultLockState] flips to
 *   [VaultLockState.State.Unlocked]; the host NavHost uses this to pop
 *   back to the original destination.
 */
@Composable
fun BiometricUnlockScreen(
    onUnlocked: () -> Unit,
    viewModel: BiometricUnlockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Auto-prompt once on entry. Re-launches when retrying after an error.
    LaunchedEffect(Unit) {
        context.findFragmentActivity()?.let(viewModel::promptUnlock)
    }

    // The VaultLockState singleton drives navigation-off-this-screen.
    // We observe it via a second lightweight ViewModel entry — but since
    // the prompt callback flips VaultLockState on its own, the parent
    // NavHost will notice and call onUnlocked via route change.
    // We still expose onUnlocked here so the unlock flow is self-contained
    // for testing.
    LaunchedEffect(uiState.status) {
        // No-op — navigation is driven by VaultLockState observer in NavHost.
    }

    Scaffold { padding ->
        UnlockScreenContent(
            padding = padding,
            status = uiState.status,
            errorMessage = uiState.errorMessage,
            onUnlockClick = {
                context.findFragmentActivity()?.let(viewModel::promptUnlock)
            },
            onUnlockedForPreview = onUnlocked,
        )
    }
}

@Composable
private fun UnlockScreenContent(
    padding: PaddingValues,
    status: BiometricUnlockViewModel.Status,
    errorMessage: String?,
    onUnlockClick: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onUnlockedForPreview: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Simple round "logo" mark — swap for a vector asset later.
            Surface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "tx",
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Unlock termx",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Your SSH keys and stored passwords are protected by your device biometric or credential.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onUnlockClick,
                enabled = status != BiometricUnlockViewModel.Status.Prompting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when (status) {
                        BiometricUnlockViewModel.Status.Prompting -> "Waiting for biometric..."
                        BiometricUnlockViewModel.Status.Failed -> "Try again"
                        BiometricUnlockViewModel.Status.Idle -> "Unlock"
                    },
                )
            }
        }
    }
}
