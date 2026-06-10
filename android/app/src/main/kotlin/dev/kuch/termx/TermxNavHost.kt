package dev.kuch.termx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.vault.VaultLockState
import dev.kuch.termx.feature.keys.KeyDetailScreen
import dev.kuch.termx.feature.keys.KeyGenerateScreen
import dev.kuch.termx.feature.keys.KeyImportScreen
import dev.kuch.termx.feature.keys.KeyListScreen
import dev.kuch.termx.feature.keys.unlock.BiometricUnlockScreen
import dev.kuch.termx.feature.onboarding.OnboardingScreen
import dev.kuch.termx.companion.CompanionUpdateBanner
import dev.kuch.termx.feature.servers.ServerListScreen
import dev.kuch.termx.feature.updater.SettingsUpdateCard
import dev.kuch.termx.feature.updater.UpdateBanner
import dev.kuch.termx.feature.servers.setup.SetupWizardScreen
import dev.kuch.termx.BuildConfig
import dev.kuch.termx.feature.settings.SettingsScreen
import dev.kuch.termx.feature.terminal.TerminalSheetHost
import dev.kuch.termx.feature.terminal.TerminalSheetViewModel
import dev.kuch.termx.feature.terminal.connection.ActiveSessionsRail
import dev.kuch.termx.feature.terminal.diff.DiffViewerScreen
import dev.kuch.termx.feature.terminal.permission.PermissionDialogHost
import dev.kuch.termx.notification.AgentAlertPoster
import dev.kuch.termx.notification.NotificationChannels
import dev.kuch.termx.push.UnifiedPushManager
import dev.kuch.termx.service.NotificationPermissionRequester
import android.content.Intent
import android.provider.Settings
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-wide navigation graph.
 *
 * Start destination is `servers` — the server list is the app's home —
 * unless [AppPreferences.onboardingComplete] is still false, in which
 * case we land on `onboarding` instead (Task #46). Onboarding completion
 * flips the preference and navigates to `servers`.
 *
 * THE TERMINAL IS NOT A ROUTE (Task #47). The old `terminal/{serverId}`
 * destination is gone; [TerminalSheetHost] is mounted as a root sibling
 * of the NavHost (inside the shared Box below, the same placement
 * technique as [PermissionDialogHost]) and slides over whatever route is
 * showing. Every "open a terminal" affordance — saved-row tap, active
 * card tap, the diff viewer's terminal action, notification deep links
 * (MainActivity) — goes through [TerminalSheetViewModel.open]
 * (connect-then-maximize).
 *
 * SHEET ↔ NAV INTERPLAY:
 *  - Any navigation to a non-home destination auto-MINIMIZES the sheet
 *    first (the [NavController.OnDestinationChangedListener] below +
 *    [shouldAutoMinimizeSheet]): settings/keys/diff must never sit
 *    hidden under a maximized terminal. Arriving at `servers` keeps the
 *    sheet — home is exactly what the sheet lives over, and a cold-start
 *    notification maximize lands before the start destination is set.
 *  - VAULT LOCK MINIMIZES (decision): the Locked branch clears the
 *    maximized id and the sheet is additionally not composed at all
 *    while Locked. The session itself stays alive in ConnectionManager;
 *    after unlock the user re-maximizes from the live home card. We
 *    deliberately do NOT preserve the maximized id across unlock — a
 *    lock event should leave zero terminal UI mounted behind the
 *    unlock screen.
 *  - [PermissionDialogHost] stays the LAST root sibling so the approval
 *    dialog renders above the sheet.
 *
 * The `unlock` gate installed by Task #20 is preserved: any lock-state
 * transition to [VaultLockState.State.Locked] jumps to `unlock` and
 * pops the prior back stack. Post-unlock we land back on the server
 * list, which is the user's expected "home" in the locked-flow recovery.
 */
@Composable
fun TermxNavHost() {
    NotificationPermissionRequester()

    val gateViewModel: NavGateViewModel = hiltViewModel()
    val lockState by gateViewModel.vaultLockState.state.collectAsStateWithLifecycle()
    val onboardingComplete by gateViewModel.onboardingComplete
        .collectAsStateWithLifecycle()

    // Resolved at the root (outside any NavBackStackEntry) so this is the
    // Activity-scoped instance — the same one TerminalSheetHost and
    // MainActivity's notification entry observe through TerminalSheetState.
    val sheetViewModel: TerminalSheetViewModel = hiltViewModel()

    val navController = rememberNavController()

    LaunchedEffect(lockState) {
        when (lockState) {
            VaultLockState.State.Locked -> {
                // Lock = minimize (see the class KDoc decision). The
                // destination listener below would also catch the
                // `unlock` navigation, but minimizing here is
                // deterministic even when we're already sitting on the
                // unlock route.
                sheetViewModel.minimize()
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

    // AUTO-MINIMIZE ON NAVIGATION: simplest-correct hook — one listener
    // on the controller instead of minimize calls sprinkled through
    // every click path. Fires for every destination change (including
    // the initial set, where the servers-route exemption keeps a
    // cold-start notification maximize alive). Minimize on an already
    // minimized sheet is a no-op.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            if (shouldAutoMinimizeSheet(destination.route)) {
                sheetViewModel.minimize()
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    val startDestination = if (onboardingComplete) Routes.Servers else Routes.Onboarding

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
        ) {
            composable(Routes.Onboarding) {
                OnboardingScreen(
                    onFinish = {
                        navController.navigate(Routes.Servers) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onLaunchSetupWizard = {
                        navController.navigate(Routes.SetupWizard) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onSetupBiometric = {
                        navController.navigate(Routes.Unlock) {
                            popUpTo(Routes.Onboarding) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.Servers) {
                ServerListScreen(
                    // Task #47: server taps no longer navigate — they
                    // connect (bind-if-alive) and maximize the terminal
                    // sheet overlay mounted below as a NavHost sibling.
                    onServerTap = { id ->
                        sheetViewModel.open(id)
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
                    updateBanner = { UpdateBanner() },
                    companionUpdateBanner = { CompanionUpdateBanner() },
                    // Task #46: the "ACTIVE SESSIONS" rail lives in
                    // :feature:terminal (it needs ConnectionManager + the
                    // thumbnail renderer) and is slot-injected here so
                    // :feature:servers stays free of that module. Tapping a
                    // card maximizes the sheet — the manager's bind-if-alive
                    // connect() makes that an instant rebind to the live
                    // emulator.
                    activeSessions = {
                        ActiveSessionsRail(
                            onSessionTap = { id ->
                                sheetViewModel.open(id)
                            },
                        )
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
            composable(Routes.Settings) {
                val settingsGate: NavGateViewModel = hiltViewModel()
                val pushDistributors by settingsGate.pushDistributors.collectAsStateWithLifecycle()
                val pushAck by settingsGate.pushAckDistributor.collectAsStateWithLifecycle()
                val context = androidx.compose.ui.platform.LocalContext.current
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    updaterCard = { SettingsUpdateCard(installedVersion = BuildConfig.VERSION_NAME) },
                    onTestAlert = settingsGate::testAgentAlert,
                    onAgentBypassDndChange = { enabled ->
                        settingsGate.setAgentBypassDnd(enabled) {
                            val intent = Intent(
                                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    pushDistributors = pushDistributors,
                    pushAckDistributor = pushAck,
                    onPushEnabledChange = settingsGate::setUnifiedPushEnabled,
                    onChoosePushDistributor = settingsGate::choosePushDistributor,
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
            composable(
                route = Routes.DiffViewerPattern,
                arguments = listOf(
                    navArgument(Routes.ArgDiffId) { type = NavType.StringType },
                    navArgument(Routes.ArgServerId) { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val serverIdArg = backStackEntry.arguments
                    ?.getString(Routes.ArgServerId)
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                DiffViewerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenTerminal = {
                        if (serverIdArg != null) {
                            // Pop home FIRST (the listener's minimize on
                            // route=servers is a no-op), then maximize — the
                            // sheet ends up over the home screen it
                            // canonically lives above, and minimizing it
                            // later reveals home, not a stale diff.
                            navController.popBackStack(Routes.Servers, inclusive = false)
                            sheetViewModel.open(serverIdArg)
                        } else {
                            navController.popBackStack()
                        }
                    },
                )
            }
        }

        // Terminal sheet overlay — composed ABOVE every route (later
        // sibling in this Box) and gated out entirely while the vault is
        // locked: no terminal pixel may render behind/around the unlock
        // screen. The Locked branch above already cleared the maximized
        // id; this gate is belt-and-braces for the same frame the lock
        // lands.
        if (lockState != VaultLockState.State.Locked) {
            TerminalSheetHost()
        }
    }

    // Permission dialog is mounted as the LAST root sibling so it renders
    // above the terminal sheet too (it's a Dialog window anyway, but keep
    // the order honest) and follows the user across any screen.
    PermissionDialogHost()
}

/**
 * Auto-minimize rule for the terminal sheet (Task #47): any destination
 * other than the home server list pulls the sheet down. Home is exempt
 * because the sheet canonically lives OVER home — and the start
 * destination firing this listener on cold start must not cancel a
 * notification-driven maximize that happened in `MainActivity.onCreate`
 * before composition.
 */
internal fun shouldAutoMinimizeSheet(route: String?): Boolean = route != Routes.Servers

/**
 * Thin ViewModel wrapper exposing the process-wide [VaultLockState]
 * singleton and the first-run onboarding gate to Compose. Direct
 * `@Inject` onto composables is not a thing — we route through a Hilt
 * ViewModel.
 */
@HiltViewModel
class NavGateViewModel @Inject constructor(
    val vaultLockState: VaultLockState,
    appPreferences: AppPreferences,
    private val agentAlertPoster: AgentAlertPoster,
    private val notificationChannels: NotificationChannels,
    private val unifiedPushManager: UnifiedPushManager,
) : ViewModel() {
    /**
     * True once the user has completed or skipped first-run onboarding.
     * Defaults to `true` for the initial emission so existing installs
     * (which never had this flag) don't get bounced through the
     * onboarding flow after an upgrade — the DataStore read will override
     * to `false` on genuinely fresh installs before the composition
     * settles.
     */
    val onboardingComplete: StateFlow<Boolean> = appPreferences.onboardingComplete
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = true,
        )

    // --- herdr agent alerts (Task #25) ------------------------------------
    //
    // `:feature:settings` cannot depend on `:app`, so the SettingsScreen
    // accepts these `:app`-only actions as callbacks (same seam as the
    // updater card). This host VM owns the singletons and exposes them.
    //
    // UnifiedPush distributor info is a *synchronous* query against the
    // platform (it changes when the user installs/picks a push app), with
    // no Flow — so cache it in StateFlows refreshed on enter / after a pick.

    private val _pushDistributors = MutableStateFlow<List<String>>(emptyList())
    val pushDistributors: StateFlow<List<String>> = _pushDistributors.asStateFlow()

    private val _pushAckDistributor = MutableStateFlow<String?>(null)
    val pushAckDistributor: StateFlow<String?> = _pushAckDistributor.asStateFlow()

    init {
        refreshPushDistributors()
    }

    fun refreshPushDistributors() {
        _pushDistributors.value = unifiedPushManager.distributors()
        _pushAckDistributor.value = unifiedPushManager.ackDistributor()
    }

    /** Post a sample agent alert so the user can preview sound + vibration. */
    fun testAgentAlert() {
        viewModelScope.launch {
            agentAlertPoster.postRaw("herdr", "Test: an agent finished")
        }
    }

    /**
     * Rebuild the agent channel with the new bypass-DND flag. The matching
     * preference write happens in [SettingsViewModel.setAgentBypassDnd];
     * this only owns the `:app` channel rebuild. When turning the flag ON
     * we ALSO need to launch the policy-access screen — that requires an
     * Activity context, so the screen passes [launchPolicyAccess].
     */
    fun setAgentBypassDnd(enabled: Boolean, launchPolicyAccess: () -> Unit) {
        notificationChannels.setAgentBypassDnd(enabled)
        if (enabled) launchPolicyAccess()
    }

    fun setUnifiedPushEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) unifiedPushManager.enable() else unifiedPushManager.disable()
            refreshPushDistributors()
        }
    }

    fun choosePushDistributor(pkg: String) {
        unifiedPushManager.choose(pkg)
        refreshPushDistributors()
    }
}

private object Routes {
    const val Onboarding = "onboarding"
    const val Servers = "servers"
    const val Keys = "keys"
    const val KeyGenerate = "keys/generate"
    const val KeyImport = "keys/import"
    const val Settings = "settings"
    const val SetupWizard = "setup-wizard"
    const val Unlock = "unlock"
    const val ArgServerId = "serverId"
    const val ArgKeyId = "id"
    const val ArgDiffId = "diffId"

    // Task #47: the terminal route (`terminal/{serverId}`) is GONE — the
    // terminal is the sheet overlay driven by TerminalSheetState, not a
    // destination. Don't reintroduce a route for it.
    const val KeyDetailPattern = "keys/{$ArgKeyId}"
    const val DiffViewerPattern = "diff/{$ArgDiffId}/{$ArgServerId}"

    fun keyDetailRoute(id: UUID): String = "keys/$id"
    fun diffViewerRoute(diffId: String, serverId: UUID): String = "diff/$diffId/$serverId"
}
