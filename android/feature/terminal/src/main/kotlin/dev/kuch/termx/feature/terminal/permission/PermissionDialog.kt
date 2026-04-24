package dev.kuch.termx.feature.terminal.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Top-level permission dialog host.
 *
 * Shown over whatever screen is on top when the broker's
 * [PermissionBrokerViewModel.pendingRequests] is non-empty. Renders only
 * the head of the list; subsequent requests queue behind it. Returning
 * to the terminal screen dismisses nothing — the dialog is mounted as a
 * sibling under the app's root, so it follows the user.
 *
 * Shared single broker viewmodel is hoisted at the app host so every
 * screen collects the same state. Seeing an approval prompt from inside
 * the Settings screen is intentional: the user might be tweaking config
 * while Claude asks for permission on a different server.
 */
@Composable
fun PermissionDialogHost(
    viewModel: PermissionBrokerViewModel = hiltViewModel(),
) {
    val pending by viewModel.pendingRequests.collectAsStateWithLifecycle()
    val head = pending.firstOrNull() ?: return
    PermissionDialog(
        request = head,
        onApprove = { viewModel.approve(head.approvalId) },
        onDeny = { viewModel.deny(head.approvalId) },
        onAlwaysApprove = { viewModel.alwaysApprove(head.approvalId) },
    )
}

/**
 * Visual body of the dialog. Extracted so preview code can pass a
 * synthetic [PendingPermission] without wiring the whole broker.
 *
 * Layout:
 *  - Title: "Claude requests permission"
 *  - Tool name (big, primary color)
 *  - Server / session metadata (secondary text)
 *  - Scrollable pretty-printed JSON of the tool args (monospaced)
 *  - Approve / Deny / Always approve buttons
 */
@Composable
fun PermissionDialog(
    request: PendingPermission,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysApprove: () -> Unit,
) {
    val prettyArgs = remember(request.toolArgs) {
        runCatching { PRETTY_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), request.toolArgs) }
            .getOrElse { request.toolArgs.toString() }
    }
    AlertDialog(
        // User must choose a button; no scrim dismiss.
        onDismissRequest = { },
        shape = RoundedCornerShape(16.dp),
        title = { Text("Claude requests permission") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = request.toolName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${request.serverLabel} · ${request.sessionName.ifBlank { "unknown session" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = prettyArgs,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApprove) { Text("Approve") }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = onDeny) { Text("Deny") }
                TextButton(onClick = onAlwaysApprove) { Text("Always approve this tool") }
            }
        },
    )
}

@OptIn(ExperimentalSerializationApi::class)
private val PRETTY_JSON: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}
