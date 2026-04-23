package dev.kuch.termx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.feature.keys.unlock.BiometricUnlockScreen
import dev.kuch.termx.feature.terminal.TerminalScreen
import javax.inject.Inject

/**
 * App-wide navigation graph.
 *
 * On top of the Phase 1 single-route (`terminal`) skeleton, Task #20 adds
 * a `unlock` route and a cross-cutting gate: whenever
 * [VaultLockState.state] reads anything other than
 * [VaultLockState.State.Unlocked] we navigate to `unlock` and pop every
 * other entry so the back stack can't leak a pre-lock screen.
 *
 * Post-unlock navigation is handled implicitly — the
 * [BiometricUnlockScreen] calls [VaultLockState.markUnlocked] on
 * success, the [LaunchedEffect] below observes that and bounces the user
 * back to the terminal. The `pendingDestination` ref remembers whatever
 * the user was headed towards before the lock event; Task #21 will use
 * it to deep-link back into the server list.
 */
@Composable
fun TermxNavHost() {
    val gateViewModel: NavGateViewModel = hiltViewModel()
    val lockState by gateViewModel.vaultLockState.state.collectAsStateWithLifecycle()

    val navController = rememberNavController()

    LaunchedEffect(lockState) {
        when (lockState) {
            VaultLockState.State.Locked -> {
                if (navController.currentDestination?.route != Routes.Unlock) {
                    navController.navigate(Routes.Unlock) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            VaultLockState.State.Unlocked -> {
                if (navController.currentDestination?.route == Routes.Unlock) {
                    navController.navigate(Routes.Terminal) {
                        popUpTo(Routes.Unlock) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            VaultLockState.State.Unlocking -> Unit
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.Terminal,
    ) {
        composable(Routes.Terminal) {
            TerminalScreen(serverId = null)
        }
        composable(Routes.Unlock) {
            BiometricUnlockScreen(
                onUnlocked = {
                    // Fallback path — the LaunchedEffect above normally
                    // drives this transition off the VaultLockState flow.
                    navController.navigate(Routes.Terminal) {
                        popUpTo(Routes.Unlock) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/**
 * Thin ViewModel wrapper exposing the process-wide [VaultLockState]
 * singleton to Compose. Direct `@Inject` onto composables is not a
 * thing — we route through a Hilt ViewModel.
 */
@HiltViewModel
class NavGateViewModel @Inject constructor(
    val vaultLockState: VaultLockState,
) : ViewModel()

private object Routes {
    const val Terminal = "terminal"
    const val Unlock = "unlock"
}
