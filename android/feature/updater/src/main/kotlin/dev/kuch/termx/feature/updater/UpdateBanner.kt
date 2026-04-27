package dev.kuch.termx.feature.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Compact in-app update banner. Mounted above the server-list
 * (per the v1.1.17 grilling decision) so it doesn't eat real
 * estate on the terminal screen. Self-hides for Idle / UpToDate /
 * Skipped / Checking states.
 */
@Composable
fun UpdateBanner(
    modifier: Modifier = Modifier,
    viewModel: UpdaterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    when (val current = state) {
        is UpdaterState.Available -> AvailableBanner(
            modifier = modifier,
            state = current,
            onDownload = { viewModel.startDownload(current) },
            onSkip = { viewModel.skip(current.version) },
        )

        is UpdaterState.Downloading -> DownloadingBanner(
            modifier = modifier,
            state = current,
        )

        is UpdaterState.ReadyToInstall -> ReadyBanner(
            modifier = modifier,
            state = current,
            onInstall = {
                val result = viewModel.install()
                if (result is ApkInstaller.Result.GrantNeeded) {
                    context.startActivity(viewModel.grantInstallPermissionIntent())
                }
            },
            onCancel = { viewModel.dismiss() },
        )

        is UpdaterState.Error -> ErrorBanner(
            modifier = modifier,
            state = current,
            onDismiss = { viewModel.dismiss() },
        )

        UpdaterState.Idle,
        UpdaterState.Checking,
        UpdaterState.Skipped,
        is UpdaterState.UpToDate,
        -> { /* hidden */ }
    }
}

@Composable
private fun BannerSurface(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) { content() }
    }
}

@Composable
private fun AvailableBanner(
    modifier: Modifier,
    state: UpdaterState.Available,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
) {
    BannerSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Update available — ${state.version} (${formatSize(state.sizeBytes)})",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onSkip) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDownload) { Text("Download") }
        }
    }
}

@Composable
private fun DownloadingBanner(
    modifier: Modifier,
    state: UpdaterState.Downloading,
) {
    BannerSurface(modifier) {
        val progress = remember(state.bytesRead, state.bytesTotal) {
            if (state.bytesTotal > 0) state.bytesRead.toFloat() / state.bytesTotal else null
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Downloading ${state.version}…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${formatSize(state.bytesRead)} / ${
                        if (state.bytesTotal > 0) formatSize(state.bytesTotal) else "?"
                    }",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ReadyBanner(
    modifier: Modifier,
    state: UpdaterState.ReadyToInstall,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    BannerSurface(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${state.version} ready to install",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onInstall) { Text("Install") }
        }
    }
}

@Composable
private fun ErrorBanner(
    modifier: Modifier,
    state: UpdaterState.Error,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Update check failed: ${state.message}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(top = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

/** Format a byte count as a short human-readable string ("12.3 MB"). */
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "?"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
