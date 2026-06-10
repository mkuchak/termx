package dev.kuch.termx

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.kuch.termx.feature.terminal.connection.ConnectionManager
import dev.kuch.termx.feature.terminal.connection.TerminalSheetState
import dev.kuch.termx.notification.EventNotificationRouter
import java.util.UUID
import javax.inject.Inject

/**
 * Host Activity for the whole app.
 *
 * Uses [FragmentActivity] (not plain ComponentActivity) so Task #20's
 * BiometricPrompt can attach on Android 9+ without repainting the surface.
 * Everything user-facing is Compose — the Fragment ancestry is purely a
 * BiometricPrompt compatibility requirement.
 *
 * NOTIFICATION ENTRY (Task #47): notification open-app PendingIntents
 * carry [EventNotificationRouter.EXTRA_SERVER_ID]; this Activity reads
 * it in [onCreate] (cold start / relaunch) and [onNewIntent] (warm
 * SINGLE_TOP delivery) and connect-then-maximizes that session's
 * terminal sheet. `connect()` is bind-if-alive, so a live session is an
 * instant rebind and a dropped one redials.
 * [EventNotificationRouter.EXTRA_APPROVAL_ID] remains deliberately
 * unrouted — the diff-viewer deep-link half of PROJECT_KNOWLEDGE §14.4
 * stays out of scope.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var connectionManager: ConnectionManager

    @Inject lateinit var terminalSheetState: TerminalSheetState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let Compose see the IME insets. Without this call, `imePadding()`
        // and `safeDrawingPadding()` resolve to 0 dp because the framework
        // still fits the decor view inside the system windows.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            TermxTheme {
                TermxNavHost()
            }
        }
        // Only a GENUINE launch handles the deep link: on a recreation
        // (rotation, restore from recents) the saved state is non-null
        // and the original intent must not re-maximize a sheet the user
        // has since minimized.
        if (savedInstanceState == null) {
            maximizeSessionFromIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maximizeSessionFromIntent(intent)
    }

    /**
     * Connect-then-maximize for a notification tap carrying a server id.
     * The extra holds the REGISTRY id; the all-zeros fallback sentinel
     * maps to the manager's null-id test-server path (same translation
     * as the ReconnectBroker collector). A malformed/absent extra is a
     * no-op — the app just opens wherever it was.
     */
    private fun maximizeSessionFromIntent(intent: Intent?) {
        val serverId = intent
            ?.getStringExtra(EventNotificationRouter.EXTRA_SERVER_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return
        connectionManager.connect(
            serverId.takeUnless { it == ConnectionManager.FALLBACK_SERVER_ID },
        )
        terminalSheetState.maximize(serverId)
    }
}

@Composable
private fun TermxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = sorcererColorScheme(), content = content)
}
