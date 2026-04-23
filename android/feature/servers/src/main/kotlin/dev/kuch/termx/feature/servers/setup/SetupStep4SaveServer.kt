package dev.kuch.termx.feature.servers.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kuch.termx.core.domain.model.AuthType

/**
 * Step 4: review and save.
 *
 * The Edit icon on each row teleports the user back to step 1 — cheap in
 * Compose navigation terms and matches the review-before-save mental model.
 * On Save the viewmodel either transitions to step 5 (key auth) or fires
 * `onDone` directly (password auth).
 */
@Composable
fun SetupStep4SaveServer(
    state: SetupWizardUiState,
    onEditStep1: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Review and save", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Last look before we persist the row. Tap the pencil to edit.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        val effectiveLabel = state.draft.label.ifBlank {
            "${state.draft.username}@${state.draft.host}"
        }

        ReviewCard(
            title = "Server",
            rows = listOf(
                "Label" to effectiveLabel,
                "Host" to "${state.draft.host}:${state.draft.port}",
                "Username" to state.draft.username,
            ),
            onEdit = onEditStep1,
        )

        Spacer(Modifier.height(12.dp))

        val authRows = when (state.draft.authType) {
            AuthType.KEY -> {
                val key = state.availableKeys.firstOrNull { it.id == state.draft.keyPairId }
                listOf(
                    "Method" to "Key",
                    "Key" to (key?.label ?: "(unset)"),
                )
            }
            AuthType.PASSWORD -> listOf(
                "Method" to "Password",
                "Password" to "•".repeat(state.draft.password.length.coerceAtMost(12)),
            )
        }
        ReviewCard(
            title = "Authentication",
            rows = authRows,
            onEdit = onEditStep1,
        )

        Spacer(Modifier.height(12.dp))

        ReviewCard(
            title = "Session",
            rows = listOf(
                "Use mosh" to if (state.draft.useMosh) "Yes" else "No",
                "Auto-attach tmux" to if (state.draft.autoAttachTmux) "Yes" else "No",
                "tmux session" to state.draft.tmuxSessionName.ifBlank { "main" },
                "Group" to (
                    state.availableGroups.firstOrNull { it.id == state.draft.groupId }?.name
                        ?: "Ungrouped"
                    ),
            ),
            onEdit = onEditStep1,
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}

@Composable
private fun ReviewCard(
    title: String,
    rows: List<Pair<String, String>>,
    onEdit: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit $title",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            rows.forEach { (k, v) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
