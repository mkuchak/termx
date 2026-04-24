package dev.kuch.termx.feature.servers.setup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kuch.termx.core.domain.model.PlannedAction
import dev.kuch.termx.core.domain.usecase.InstallStep3State

/**
 * Step 3: termxd companion install.
 *
 * The body is a state machine reader — every UI branch is a function of the
 * current [InstallStep3State]. The viewmodel owns both the state feed and the
 * action callbacks; this composable is otherwise stateless.
 *
 *  - [onPreview] / [onInstall] / [onRetry] re-run the stage via the use-case.
 *  - [onNext] is called only from the Success / AlreadyInstalled paths — the
 *    wizard-level next()/back() arrows still work, but the primary action
 *    button here is what advances the user past the companion step.
 *  - [onSkip] is the persistent secondary action, always reachable.
 */
@Composable
fun SetupStep3InstallTermxd(
    state: InstallStep3State,
    onPreview: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Install termx companion", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Unlocks the event stream, permission broker, and diff viewer. Optional.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        when (state) {
            InstallStep3State.Detecting -> StatusCard(
                title = "Checking VPS...",
                body = "Looking for an existing termx binary.",
                showSpinner = true,
            )

            is InstallStep3State.AlreadyInstalled -> Column {
                StatusCard(
                    title = "Already installed",
                    body = state.version,
                    icon = Icons.Filled.CheckCircle,
                    iconTint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))
                PrimarySkipRow(
                    primaryLabel = "Next",
                    onPrimary = onNext,
                    onSkip = onSkip,
                )
            }

            InstallStep3State.FetchingRelease -> StatusCard(
                title = "Fetching release...",
                body = "Picking the right termxd-v* asset from GitHub.",
                showSpinner = true,
            )

            is InstallStep3State.ReadyToDownload -> Column {
                ReadyToDownloadCard(state)
                Spacer(Modifier.height(16.dp))
                PrimarySkipRow(
                    primaryLabel = "Download + Preview",
                    onPrimary = onPreview,
                    onSkip = onSkip,
                )
            }

            is InstallStep3State.Downloading -> StatusCard(
                title = "Preparing...",
                body = state.status,
                showSpinner = true,
            )

            is InstallStep3State.PreviewingDiff -> Column(modifier = Modifier.fillMaxWidth()) {
                DiffPreview(state.actions)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text("Skip")
                    }
                    Button(onClick = onInstall, modifier = Modifier.weight(1f)) {
                        Text("Install")
                    }
                }
            }

            is InstallStep3State.Installing -> InstallingView(state.log)

            InstallStep3State.Success -> Column {
                SuccessCard()
                Spacer(Modifier.height(16.dp))
                PrimarySkipRow(
                    primaryLabel = "Next",
                    onPrimary = onNext,
                    onSkip = onSkip,
                )
            }

            is InstallStep3State.Error -> Column {
                ErrorCard(state.message, state.log)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
                        Text("Skip")
                    }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Pieces
// -----------------------------------------------------------------------------

@Composable
private fun StatusCard(
    title: String,
    body: String,
    showSpinner: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Terminal,
    iconTint: Color = MaterialTheme.colorScheme.primary,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showSpinner) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp))
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReadyToDownloadCard(state: InstallStep3State.ReadyToDownload) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Release ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tag: ${state.releaseTag}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "Arch: ${state.arch}",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Nothing writes to the VPS until you approve the diff on the next screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Dry-run preview, grouped by action type.
 *
 * Three sections:
 *  - "File modifications" — inject_block, update_json (the marked-block edits)
 *  - "File creations"     — write_file, mkdir (fresh state under ~/.termx)
 *  - "Binary install"     — install_binary (the termx binary itself)
 *
 *  Unknown types fall through into a fourth "Other" bucket so the user sees
 *  every change rather than silently dropping rows.
 */
@Composable
private fun DiffPreview(actions: List<PlannedAction>) {
    val modifications = actions.filter { it.type in MODIFY_TYPES }
    val creations = actions.filter { it.type in CREATE_TYPES }
    val binaries = actions.filter { it.type in BINARY_TYPES }
    val other = actions.filter {
        it.type !in MODIFY_TYPES && it.type !in CREATE_TYPES && it.type !in BINARY_TYPES
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Planned changes (${actions.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Review before applying. Everything is idempotent and reversible via termx uninstall.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ExpandableCard("File modifications", modifications, initiallyExpanded = true)
        Spacer(Modifier.height(8.dp))
        ExpandableCard("File creations", creations, initiallyExpanded = true)
        Spacer(Modifier.height(8.dp))
        ExpandableCard("Binary install", binaries, initiallyExpanded = true)
        if (other.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ExpandableCard("Other", other, initiallyExpanded = false)
        }
    }
}

@Composable
private fun ExpandableCard(
    title: String,
    rows: List<PlannedAction>,
    initiallyExpanded: Boolean,
) {
    // remember, not rememberSaveable — a re-composition from a new state emit
    // shouldn't collapse expanded sections mid-review.
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${rows.size} action${if (rows.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }
            if (expanded) {
                if (rows.isEmpty()) {
                    Text(
                        "Nothing in this bucket.",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEach { action -> PlannedActionRow(action) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun PlannedActionRow(action: PlannedAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.type,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                action.path ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        val description = action.description
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val mode = action.extras["mode"]
        if (!mode.isNullOrBlank()) {
            Text(
                "mode $mode",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallingView(log: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) {
            listState.animateScrollToItem(log.size - 1)
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(
                "Installing termx...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(10.dp),
            ) {
                items(log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (log.isEmpty()) {
                    items(listOf("waiting for first output...")) { placeholder ->
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuccessCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Companion installed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "~/.termx/events.ndjson is live. Phase 4+ features are unlocked for this VPS.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, log: List<String>) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Install failed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp),
                ) {
                    Column {
                        log.takeLast(MAX_INLINE_LOG_LINES).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimarySkipRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) {
            Text("Skip")
        }
        Button(onClick = onPrimary, modifier = Modifier.weight(1f)) {
            Text(primaryLabel)
        }
    }
}

private val MODIFY_TYPES = setOf("inject_block", "update_json")
private val CREATE_TYPES = setOf("write_file", "mkdir")
private val BINARY_TYPES = setOf("install_binary")
private const val MAX_INLINE_LOG_LINES = 20
