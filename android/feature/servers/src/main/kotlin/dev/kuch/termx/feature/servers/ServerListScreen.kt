package dev.kuch.termx.feature.servers

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's home screen: every persisted server in one place.
 *
 * - Sticky group headers with expand/collapse.
 * - Swipe-left on any row to delete with a 5 s Undo snackbar.
 * - Overflow menu per row: Edit · Duplicate · Move to group · Delete.
 * - "Reorder" top-bar toggle that swaps the overflow icon for up/down
 *   arrows. Real drag-to-reorder is deferred to a follow-up task —
 *   shipping arrow buttons avoids the extra library dependency and
 *   gets a functioning reorder UX in today's build.
 * - FAB opens [AddEditServerSheet] in add mode. Tapping a card navigates
 *   to the terminal for that server via [onServerTap]. The top bar's
 *   key-icon routes to [onManageKeys] (Task #23's screen).
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
    viewModel: ServerListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val pendingUndo by viewModel.pendingUndo.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showAddPicker by rememberSaveable { mutableStateOf(false) }
    var editingServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var reorderMode by rememberSaveable { mutableStateOf(false) }
    var moveToGroupForServerId by rememberSaveable { mutableStateOf<String?>(null) }
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
                    if (uiState is ServerListUiState.Loaded) {
                        IconButton(
                            onClick = { reorderMode = !reorderMode },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = if (reorderMode) "Exit reorder" else "Reorder",
                                tint = if (reorderMode) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
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
                // pass here via the tmux/ssh refresh machinery.
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
                // Task #45: nudge the user to exclude termx from Doze so
                // background tails keep running with the screen off.
                // Hoisted above the list so it's the first thing they see
                // on a fresh install and so it never fights the list for
                // focus.
                BatteryOptimizationPrompt()
                when (val s = uiState) {
                    ServerListUiState.Loading -> LoadingPane()
                    ServerListUiState.Empty -> EmptyPane(onAdd = { showAddPicker = true })
                    is ServerListUiState.Error -> ErrorPane(message = s.message)
                    is ServerListUiState.Loaded -> ServerListBody(
                        buckets = s.groupsWithServers,
                        reorderMode = reorderMode,
                        onServerTap = onServerTap,
                        onEdit = { id -> editingServerId = id.toString() },
                        onDuplicate = viewModel::onDuplicate,
                        onDelete = viewModel::onDelete,
                        onMoveToGroupRequest = { id ->
                            moveToGroupForServerId = id.toString()
                        },
                        onToggleGroup = viewModel::onToggleGroupCollapse,
                        onMoveUp = viewModel::onMoveUp,
                        onMoveDown = viewModel::onMoveDown,
                        onReorder = viewModel::onReorder,
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

    moveToGroupForServerId?.let { idStr ->
        val id = runCatching { UUID.fromString(idStr) }.getOrNull()
        val loaded = uiState as? ServerListUiState.Loaded
        if (id != null && loaded != null) {
            MoveToGroupDialog(
                groups = loaded.groupsWithServers.mapNotNull { it.group },
                onDismiss = { moveToGroupForServerId = null },
                onPick = { groupId ->
                    viewModel.onMoveToGroup(id, groupId)
                    moveToGroupForServerId = null
                },
            )
        }
    }
}

/**
 * Task #53 — real drag-to-reorder.
 *
 * We render the full (grouped) list into a single [LazyColumn] and
 * power the drag interactions with `sh.calvin.reorderable`. A local
 * [displayBuckets] copy of [buckets] tracks the in-flight order during
 * a drag: [rememberReorderableLazyListState]'s onMove callback swaps
 * two servers in place (within the same group only — cross-group drag
 * is a follow-up), and at drag-stop the final ordering is published
 * to the ViewModel via [onReorder].
 *
 * Dragging is long-press activated (via `longPressDraggableHandle`) so
 * the row's tap / swipe / overflow-button affordances all keep working.
 * Haptic feedback fires on drag-start and drag-stop; item translation +
 * a subtle shadow is handled by [ReorderableItem]'s default animation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerListBody(
    buckets: List<GroupedServers>,
    reorderMode: Boolean,
    onServerTap: (UUID) -> Unit,
    onEdit: (UUID) -> Unit,
    onDuplicate: (UUID) -> Unit,
    onDelete: (UUID) -> Unit,
    onMoveToGroupRequest: (UUID) -> Unit,
    onToggleGroup: (UUID) -> Unit,
    onMoveUp: (UUID) -> Unit,
    onMoveDown: (UUID) -> Unit,
    onReorder: (UUID?, List<UUID>) -> Unit,
) {
    val listState = rememberLazyListState()
    val now = remember { Instant.now() }

    // Local copy of the bucket list that the drag reorder mutates in
    // place while a drag is in progress. The `remember(buckets)` key
    // resets the local state whenever the upstream flow re-emits so
    // edits / deletes / group changes still flow through.
    var displayBuckets by remember(buckets) { mutableStateOf(buckets) }
    // Track which bucket id the active drag belongs to, so we push
    // the final order for that bucket (and only that one) to Room on
    // drop. Null = no drag in progress.
    var pendingReorderGroupId: UUID? by remember(buckets) { mutableStateOf(null) }

    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        // Find the bucket whose server list contains the dragged key.
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromId = runCatching { UUID.fromString(fromKey) }.getOrNull()
            ?: return@rememberReorderableLazyListState
        val toId = runCatching { UUID.fromString(toKey) }.getOrNull()
            ?: return@rememberReorderableLazyListState
        val sourceBucketIndex = displayBuckets.indexOfFirst { b ->
            b.servers.any { it.id == fromId }
        }
        if (sourceBucketIndex < 0) return@rememberReorderableLazyListState
        val sourceBucket = displayBuckets[sourceBucketIndex]
        // Scope within-group only; reject the move if the target key
        // lives in a different bucket.
        val targetInSameBucket = sourceBucket.servers.any { it.id == toId }
        if (!targetInSameBucket) return@rememberReorderableLazyListState
        val newServers = sourceBucket.servers.toMutableList()
        val fromIdx = newServers.indexOfFirst { it.id == fromId }
        val toIdx = newServers.indexOfFirst { it.id == toId }
        if (fromIdx < 0 || toIdx < 0 || fromIdx == toIdx) return@rememberReorderableLazyListState
        newServers.add(toIdx, newServers.removeAt(fromIdx))
        displayBuckets = displayBuckets.toMutableList().also {
            it[sourceBucketIndex] = sourceBucket.copy(servers = newServers)
        }
        pendingReorderGroupId = sourceBucket.group?.id
    }
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        displayBuckets.forEach { bucket ->
            val headerName = bucket.group?.name ?: "Ungrouped"
            val count = bucket.servers.size
            stickyHeader(key = bucket.group?.id?.toString() ?: "__ungrouped__") {
                ServerGroupHeader(
                    name = headerName,
                    count = count,
                    isCollapsed = bucket.isCollapsed,
                    onToggle = bucket.group?.let { g -> { onToggleGroup(g.id) } },
                )
            }
            if (!bucket.isCollapsed) {
                items(
                    items = bucket.servers,
                    key = { it.id.toString() },
                ) { server ->
                    val index = bucket.servers.indexOf(server)
                    ReorderableItem(reorderableState, key = server.id.toString()) {
                        val dragHandle = Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val gid = pendingReorderGroupId
                                pendingReorderGroupId = null
                                // Compute the final ordered id list for
                                // the bucket that was dragged — VM
                                // persists `sortOrder = N` for position
                                // N in a single Room transaction.
                                val final = displayBuckets
                                    .firstOrNull { b -> b.group?.id == gid }
                                    ?.servers
                                    ?.map { it.id }
                                    .orEmpty()
                                if (final.isNotEmpty()) onReorder(gid, final)
                            },
                        )
                        SwipeableServerCard(
                            server = server,
                            reorderMode = reorderMode,
                            canMoveUp = index > 0,
                            canMoveDown = index < bucket.servers.lastIndex,
                            now = now,
                            onTap = { onServerTap(server.id) },
                            onEdit = { onEdit(server.id) },
                            onDuplicate = { onDuplicate(server.id) },
                            onDelete = { onDelete(server.id) },
                            onMoveToGroup = { onMoveToGroupRequest(server.id) },
                            onMoveUp = { onMoveUp(server.id) },
                            onMoveDown = { onMoveDown(server.id) },
                            dragHandleModifier = dragHandle,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableServerCard(
    server: Server,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    now: Instant,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    // In reorder mode the swipe would conflict with the up/down arrows, so
    // we skip the dismiss wrapper entirely — the user opted into arrow
    // controls, destructive swipes stay out of the way.
    if (reorderMode) {
        ServerCard(
            server = server,
            reorderMode = true,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onTap = onTap,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onMoveToGroup = onMoveToGroup,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            now = now,
        )
        return
    }

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
            reorderMode = false,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onTap = onTap,
            onEdit = onEdit,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onMoveToGroup = onMoveToGroup,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            now = now,
            modifier = dragHandleModifier,
        )
    }
}

@Composable
private fun DismissBackground() {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        color = Color(0xFFE5484D),
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
                tint = Color.White,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Delete",
                color = Color.White,
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
 * Lightweight picker dialog for the "Move to group" overflow action.
 * Re-uses the list of groups the user has already created; no inline
 * "Create group" affordance here — keep that flow in the edit sheet.
 */
@Composable
private fun MoveToGroupDialog(
    groups: List<ServerGroup>,
    onDismiss: () -> Unit,
    onPick: (UUID?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to group") },
        text = {
            Column {
                GroupPickRow(label = "Ungrouped", onClick = { onPick(null) })
                groups.forEach { g ->
                    GroupPickRow(label = g.name, onClick = { onPick(g.id) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun GroupPickRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
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
