package dev.kuch.termx.feature.keys

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Top-level "Keys" screen: every persisted [KeyPair] plus entry points to
 * generate a new one or import from file.
 *
 * Deletion flow:
 *  - Long-press opens the action sheet.
 *  - "Delete" when the row has zero referencing servers → straight delete.
 *  - "Delete" when at least one server uses the key → reassignment dialog
 *    picks a replacement key before deleting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyListScreen(
    onKeyTap: (UUID) -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
    viewModel: KeyListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    var showFabSheet by rememberSaveable { mutableStateOf(false) }
    var actionSheetForId by rememberSaveable { mutableStateOf<String?>(null) }
    var reassignFromId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keys", fontWeight = FontWeight.SemiBold) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showFabSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add key")
            }
        },
    ) { padding ->
        when {
            uiState.isLoading -> LoadingPane(padding)
            uiState.rows.isEmpty() -> EmptyPane(
                padding = padding,
                onGenerate = onGenerate,
                onImport = onImport,
            )
            else -> KeyListBody(
                padding = padding,
                rows = uiState.rows,
                onKeyTap = onKeyTap,
                onLongPress = { id -> actionSheetForId = id.toString() },
            )
        }
    }

    if (showFabSheet) {
        AddKeyBottomSheet(
            onDismiss = { showFabSheet = false },
            onGenerate = {
                showFabSheet = false
                onGenerate()
            },
            onImport = {
                showFabSheet = false
                onImport()
            },
        )
    }

    actionSheetForId?.let { idStr ->
        val id = runCatching { UUID.fromString(idStr) }.getOrNull()
        val row = uiState.rows.firstOrNull { it.keyPair.id == id }
        if (id != null && row != null) {
            KeyActionSheet(
                row = row,
                onDismiss = { actionSheetForId = null },
                onDelete = {
                    actionSheetForId = null
                    if (row.usedByServerCount > 0) {
                        reassignFromId = id.toString()
                    } else {
                        viewModel.delete(id)
                    }
                },
            )
        }
    }

    reassignFromId?.let { idStr ->
        val fromId = runCatching { UUID.fromString(idStr) }.getOrNull()
        if (fromId != null) {
            val row = uiState.rows.firstOrNull { it.keyPair.id == fromId }
            val candidates = uiState.rows
                .filter { it.keyPair.id != fromId }
                .map { it.keyPair }
            ReassignDeleteDialog(
                usedByCount = row?.usedByServerCount ?: 0,
                candidates = candidates,
                onDismiss = { reassignFromId = null },
                onPick = { toId ->
                    viewModel.reassignAndDelete(fromId, toId)
                    reassignFromId = null
                },
            )
        }
    }
}

@Composable
private fun LoadingPane(padding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyPane(
    padding: PaddingValues,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(72.dp),
            )
            Text(
                text = "No keys yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Generate an Ed25519 pair or import an existing OpenSSH key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onImport) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
                androidx.compose.material3.Button(onClick = onGenerate) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Generate")
                }
            }
        }
    }
}

@Composable
private fun KeyListBody(
    padding: PaddingValues,
    rows: List<KeyListViewModel.KeyRow>,
    onKeyTap: (UUID) -> Unit,
    onLongPress: (UUID) -> Unit,
) {
    val now = remember { Instant.now() }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
    ) {
        items(items = rows, key = { it.keyPair.id }) { row ->
            KeyCard(
                row = row,
                now = now,
                onTap = { onKeyTap(row.keyPair.id) },
                onLongPress = { onLongPress(row.keyPair.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyCard(
    row: KeyListViewModel.KeyRow,
    now: Instant,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.keyPair.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    AlgorithmChip(row.keyPair.algorithm)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = truncateFingerprint(row.fingerprint),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Created ${relativeAgo(row.keyPair.createdAt, now)}" +
                        if (row.usedByServerCount > 0) {
                            " · used by ${row.usedByServerCount} server" +
                                if (row.usedByServerCount == 1) "" else "s"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlgorithmChip(algorithm: KeyAlgorithm) {
    val label = when (algorithm) {
        KeyAlgorithm.ED25519 -> "Ed25519"
        KeyAlgorithm.RSA_4096 -> "RSA-4096"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddKeyBottomSheet(
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
    onImport: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Add a key",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Generate a new pair or import an existing OpenSSH key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Generate")
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import from file")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyActionSheet(
    row: KeyListViewModel.KeyRow,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = row.keyPair.label,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Delete key",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReassignDeleteDialog(
    usedByCount: Int,
    candidates: List<KeyPair>,
    onDismiss: () -> Unit,
    onPick: (UUID) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Key is in use") },
        text = {
            Column {
                val pluralS = if (usedByCount == 1) "" else "s"
                Text(
                    "This key is used by $usedByCount server$pluralS. " +
                        "Pick a replacement key below to reassign them, then delete.",
                )
                Spacer(Modifier.height(12.dp))
                if (candidates.isEmpty()) {
                    Text(
                        "No other keys to reassign to. Generate a replacement first.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    candidates.forEach { k ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clickable { onPick(k.id) },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = k.label,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = when (k.algorithm) {
                                        KeyAlgorithm.ED25519 -> "Ed25519"
                                        KeyAlgorithm.RSA_4096 -> "RSA-4096"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun truncateFingerprint(fp: String): String {
    if (fp.length <= 28) return fp
    return fp.substring(0, 20) + "…" + fp.substring(fp.length - 6)
}

private fun relativeAgo(createdAt: Instant, now: Instant): String {
    val secs = ChronoUnit.SECONDS.between(createdAt, now).coerceAtLeast(0)
    return when {
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86_400 -> "${secs / 3600}h ago"
        secs < 31_536_000 -> "${secs / 86_400}d ago"
        else -> "${secs / 31_536_000}y ago"
    }
}

