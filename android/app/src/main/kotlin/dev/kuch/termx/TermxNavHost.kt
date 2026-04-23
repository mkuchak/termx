package dev.kuch.termx

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.kuch.termx.feature.terminal.TerminalScreen

/**
 * App-wide navigation graph. Phase 1 has one route — `terminal` — hitting
 * the env-var-configured test server. Phase 2 (Task #21) will add the
 * server list as start destination and pass a real UUID to this route.
 */
@Composable
fun TermxNavHost() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.Terminal,
    ) {
        composable(Routes.Terminal) {
            TerminalScreen(serverId = null)
        }
    }
}

private object Routes {
    const val Terminal = "terminal"
}
