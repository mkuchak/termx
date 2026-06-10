package dev.kuch.termx.feature.servers

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.Server
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's home screen, Moshi-style (Task #46): live "ACTIVE SESSIONS"
 * cards on top, a flat "SAVED CONNECTIONS" list below.
 *
 * - [activeSessions] slot renders the live-session rail. It is composed
 *   by the `:app` NavHost with `:feature:terminal`'s `ActiveSessionsRail`
 *   (this module must not depend on `:feature:terminal` — same
 *   slot-injection seam as [updateBanner]). The rail self-hides when no
 *   session is live.
 * - Swipe-left on any row deletes with a 5 s Undo snackbar (delete
 *   commits immediately; undo re-inserts).
 * - Overflow menu per row: Edit · Duplicate · Delete.
 * - Groups render as plain, NON-collapsible uppercase section headers
 *   when they exist (ungrouped last); no sticky behavior, no reorder UI
 *   — the group/reorder-heavy chrome was dropped in the Task #46 pivot
 *   (the Room group model and the repository reorder API stay).
 * - FAB (bottom-right) opens the add flow; the top bar keeps the
 *   key-vault shortcut and the Settings gear.
 * - Empty state shows a big `Icons.Default.Dns` glyph, a friendly note,
 *   and a primary "Add server" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onServerTap: (UUID) -> Unit,
    onManageKeys: () -> Unit,
    onLaunchSetupWizard: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /**
     * Slot for the in-app updater banner (v1.1.17). The :app NavHost
     * composes this with [dev.kuch.termx.feature.updater.UpdateBanner]
     * so :feature:servers stays free of the updater module dep. The
     * banner self-hides when there's no update to surface.
     */
    updateBanner: @Composable () -> Unit = {},
    /**
     * Slot for the on-connect companion (termxd) update banner (OPT-2,
     * Task #32). The :app NavHost composes this with
     * `dev.kuch.termx.companion.CompanionUpdateBanner` (backed by
     * CompanionUpdateRepository's StateFlow) so :feature:servers stays free
     * of the banner. Self-hides unless a connect surfaced an offer.
     */
    companionUpdateBanner: @Composable () -> Unit = {},
    /**
     * Slot for the "ACTIVE SESSIONS" rail (Task #46). The :app NavHost
     * composes this with `dev.kuch.termx.feature.terminal.connection.
     * ActiveSessionsRail`, passing an open-the-terminal-sheet lambda
     * (Task #47: connect-then-maximize, no nav route) —
     * :feature:servers must not depend on :feature:terminal (where
     * ConnectionManager + the thumbnail renderer live). The rail renders
     * nothing when no session is live, so it is composed unconditionally
     * here, above the saved list in every UI state (a live test-server
     * session can exist even with zero saved rows).
     */
    activeSessions: @Composable () -> Unit = {},
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showAddPicker by rememberSaveable { mutableStateOf(false) }
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Surface the undo snackbar whenever the ViewModel stashes a deleted row.
    LaunchedEffect(pendingUndo?.id) {
        val server = pendingUndo ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Deleted ${server.label}",
            actionLabel = "Undo",
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.onUndoDelete(server)
            SnackbarResult.Dismissed -> viewModel.onUndoDismissed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("termx", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onManageKeys) {
                        Icon(Icons.Filled.VpnKey, contentDescription = "Manage keys")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddPicker = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add server")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            state = pullState,
            isRefreshing = isRefreshing,
            onRefresh = {
                // Phase 2 stub: flip a flag so the spinner renders for a
                // frame, then settle. Phase 3 will trigger a real ping
                // pass here via the ssh refresh machinery.
                scope.launch {
                    isRefreshing = true
                    viewModel.onRefresh()
                    delay(400)
                    isRefreshing = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // In-app updater banner (v1.1.17). Self-hides when
                // there's nothing to surface; otherwise renders Available /
                // Downloading / ReadyToInstall / Error states above the
                // server list so the user sees update offers without
                // visiting Settings.
                updateBanner()
                // On-connect companion (termxd) update offer (Task #32).
                // Same hoist as the APK banner; self-hides unless a recent
                // connect found the VPS companion missing or out of date.
                companionUpdateBanner()
                // Task #45: nudge the user to exclude termx from Doze so
                // background tails keep running with the screen off.
                // Hoisted above the list so it's the first thing they see
                // on a fresh install and so it never fights the list for
                // focus.
                BatteryOptimizationPrompt()
                // Task #46: live-session cards. Self-hides when empty.
                activeSessions()
                when (val s = uiState) {
                    ServerListUiState.Loading -> LoadingPane()
                    ServerListUiState.Empty -> EmptyPane(onAdd = { showAddPicker = true })
                    is ServerListUiState.Error -> ErrorPane(message = s.message)
                    is ServerListUiState.Loaded -> ServerListBody(
                        buckets = s.groupsWithServers,
                        onServerTap = onServerTap,
                        onEdit = { id -> editingServerId = id.toString() },
                        onDuplicate = viewModel::onDuplicate,
                        onDelete = viewModel::onDelete,
                    )
                }
            }
        }
    }

    if (showAddPicker) {
        AddServerPickerSheet(
            onDismiss = { showAddPicker = false },
            onQuickAdd = {
                showAddPicker = false
                showAddSheet = true
            },
            onGuidedSetup = {
                showAddPicker = false
                onLaunchSetupWizard()
            },
        )
    }

    if (showAddSheet) {
        AddEditServerSheet(
            serverId = null,
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false },
            onManageKeys = onManageKeys,
        )
    }

    editingServerId?.let { idStr ->
        val id = runCatching { UUID.fromString(idStr) }.getOrNull()
        if (id != null) {
            AddEditServerSheet(
                serverId = id,
                onDismiss = { editingServerId = null },
                onSaved = { editingServerId = null },
                onManageKeys = onManageKeys,
            )
        }
    }
}

/**
 * The flat "SAVED CONNECTIONS" list. When the user has created groups
 * they render as plain uppercase section labels (Moshi-style, in
 * onSurfaceVariant) with the ungrouped bucket last — no expand/collapse,
 * no sticky headers, no drag (all dropped in the Task #46 pivot).
 */
@Composable
private fun ServerListBody(
    buckets: List<GroupedServers>,
    onServerTap: (UUID) -> Unit,
    onEdit: (UUID) -> Unit,
    onDuplicate: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
) {
    val now = remember { Instant.now() }
    // Per-group labels only earn their vertical space when at least one
    // explicit group exists; a fully ungrouped install gets just the
    // single section label.
    val showGroupLabels = buckets.any { it.group != null }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "__saved_connections__") {
            SectionLabel("SAVED CONNECTIONS")
        }
        buckets.forEach { bucket ->
            if (showGroupLabels) {
                item(key = bucket.group?.id?.toString() ?: "__ungrouped__") {
                    SectionLabel(
                        label = (bucket.group?.name ?: "Ungrouped").uppercase(),
                        secondary = true,
                    )
                }
            }
            items(
                items = bucket.servers,
                key = { it.id.toString() },
            ) { server ->
                SwipeableServerCard(
                    server = server,
                    now = now,
                    onTap = { onServerTap(server.id) },
                    onEdit = { onEdit(server.id) },
                    onDuplicate = { onDuplicate(server.id) },
                    onDelete = { onDelete(server.id) },
                )
            }
        }
    }
}

/**
 * Moshi-style uppercase section label in onSurfaceVariant. [secondary]
 * labels (group names under "SAVED CONNECTIONS") indent the same but
 * drop to labelSmall so the hierarchy reads at a glance.
 */
@Composable
private fun SectionLabel(label: String, secondary: Boolean = false) {
    Text(
        text = label,
        style = if (secondary) {
            MaterialTheme.typography.labelSmall
        } else {
            MaterialTheme.typography.labelMedium
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableServerCard(
    server: Server,
    now: Instant,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                // Returning `true` lets SwipeToDismissBox settle in the
                // dismissed state; the undo snackbar will either restore
                // the row or accept the deletion once the timer fires.
                // Room's flow re-emits without the deleted row, so the
                // LazyColumn item key driven by `Server.id` handles the
                // disappearance animation for us.
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.66f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            DismissBackground()
        },
    ) {
        ServerCard(
            server = server,
            onTap = onTap,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            now = now,
        )
    }
}

@Composable
private fun DismissBackground() {
    // Destructive action surface — uses Material 3's `error` token so
    // it inherits Sorcerer's accent (red==accent in this palette by
    // faithful design). Icon + label are `onError` so contrast stays
    // tied to the same semantic pair.
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.error,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Delete",
                color = MaterialTheme.colorScheme.onError,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyPane(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "No servers yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Add your first VPS to start driving Claude from your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text("Add server")
            }
        }
    }
}

@Composable
private fun ErrorPane(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * FAB-triggered chooser: "Quick add" opens the classic bottom sheet,
 * "Guided setup" launches the 5-step wizard. Keeps the power-user path
 * one-tap (reopen the sheet) while putting the onboarding flow front and
 * centre for first-time users.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerPickerSheet(
    onDismiss: () -> Unit,
    onQuickAdd: () -> Unit,
    onGuidedSetup: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Add a server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "First VPS? Guided setup walks you through test + key share.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            AddOptionRow(
                icon = Icons.Filled.AutoAwesome,
                title = "Guided setup",
                subtitle = "5 steps: connect, test, install companion, save, share key.",
                onClick = onGuidedSetup,
            )
            Spacer(Modifier.height(8.dp))
            AddOptionRow(
                icon = Icons.Filled.FlashOn,
                title = "Quick add",
                subtitle = "Single bottom sheet — best when you already know the drill.",
                onClick = onQuickAdd,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AddOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
