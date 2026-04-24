package dev.kuch.termx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.feature.keys.KeyDetailScreen
import dev.kuch.termx.feature.keys.KeyGenerateScreen
import dev.kuch.termx.feature.keys.KeyImportScreen
import dev.kuch.termx.feature.keys.KeyListScreen
import dev.kuch.termx.feature.keys.unlock.BiometricUnlockScreen
import dev.kuch.termx.feature.servers.ServerListScreen
import dev.kuch.termx.feature.servers.setup.SetupWizardScreen
import dev.kuch.termx.feature.terminal.TerminalScreen
import java.util.UUID
import javax.inject.Inject

/**
 * App-wide navigation graph.
 *
 * Start destination is `servers` — the server list is the app's home.
 * Tapping a row navigates to `terminal/{serverId}`; Task #15's
 * [TerminalScreen] now accepts a non-null UUID and connects via Room +
 * the vault rather than the BuildConfig fallback.
 *
 * The `unlock` gate installed by Task #20 is preserved: any lock-state
 * transition to [VaultLockState.State.Locked] jumps to `unlock` and
 * pops the prior back stack. Post-unlock we land back on the server
 * list, which is the user's expected "home" in the locked-flow recovery.
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
                    navController.navigate(Routes.Servers) {
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
        startDestination = Routes.Servers,
    ) {
        composable(Routes.Servers) {
            ServerListScreen(
                onServerTap = { id ->
                    navController.navigate(Routes.terminalRoute(id))
                },
                onManageKeys = {
                    navController.navigate(Routes.Keys)
                },
                onLaunchSetupWizard = {
                    navController.navigate(Routes.SetupWizard)
                },
                onOpenSettings = {
                    navController.navigate(Routes.Settings)
                },
            )
        }
        composable(Routes.SetupWizard) {
            SetupWizardScreen(
                onDone = { _ ->
                    // Row is already persisted — just pop back to the
                    // server list, which will auto-reflect the new entry
                    // via the Room flow.
                    navController.popBackStack(Routes.Servers, inclusive = false)
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.TerminalPattern,
            arguments = listOf(
                navArgument(Routes.ArgServerId) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments
                ?.getString(Routes.ArgServerId)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            TerminalScreen(serverId = id)
        }
        composable(Routes.Keys) {
            KeyListScreen(
                onKeyTap = { id -> navController.navigate(Routes.keyDetailRoute(id)) },
                onGenerate = { navController.navigate(Routes.KeyGenerate) },
                onImport = { navController.navigate(Routes.KeyImport) },
            )
        }
        composable(Routes.KeyGenerate) {
            KeyGenerateScreen(
                onDone = { id ->
                    navController.popBackStack(Routes.Keys, inclusive = false)
                    navController.navigate(Routes.keyDetailRoute(id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.KeyImport) {
            KeyImportScreen(
                onDone = { id ->
                    navController.popBackStack(Routes.Keys, inclusive = false)
                    navController.navigate(Routes.keyDetailRoute(id))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.KeyDetailPattern,
            arguments = listOf(
                navArgument(Routes.ArgKeyId) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val id = UUID.fromString(
                backStackEntry.arguments!!.getString(Routes.ArgKeyId),
            )
            KeyDetailScreen(
                keyId = id,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.Unlock) {
            BiometricUnlockScreen(
                onUnlocked = {
                    // Fallback path — the LaunchedEffect above normally
                    // drives this transition off the VaultLockState flow.
                    navController.navigate(Routes.Servers) {
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
    const val Servers = "servers"
    const val Keys = "keys"
    const val KeyGenerate = "keys/generate"
    const val KeyImport = "keys/import"
    const val Settings = "settings"
    const val SetupWizard = "setup-wizard"
    const val Unlock = "unlock"
    const val ArgServerId = "serverId"
    const val ArgKeyId = "id"
    const val TerminalPattern = "terminal/{$ArgServerId}"
    const val KeyDetailPattern = "keys/{$ArgKeyId}"

    fun terminalRoute(id: UUID): String = "terminal/$id"
    fun keyDetailRoute(id: UUID): String = "keys/$id"
}
