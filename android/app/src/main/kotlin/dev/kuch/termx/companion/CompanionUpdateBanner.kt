package dev.kuch.termx.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.data.remote.CompanionUpdateState

/**
 * Compact on-connect companion (termxd) update banner (OPT-2, Task #32),
 * mounted above the server list via the `companionUpdateBanner` slot in
 * `ServerListScreen` — the same hoist the APK `UpdateBanner` uses, decided
 * by the v1.1.17 grilling ("don't eat terminal real estate").
 *
 * Banner over snackbar deliberately: the offer is persistent and actionable
 * (Install / Later), so a transient auto-dismissing snackbar would drop the
 * Install affordance before a pocketed phone is even looked at. The banner
 * self-hides for Idle / Checking / UpToDate / Skipped (checks are invisible);
 * only an actual offer / in-progress install / outcome renders.
 *
 * Consent-first: the on-connect check only ever reaches UpdateAvailable /
 * Missing; tapping Install is what runs the over-SSH install.
 */
@Composable
fun CompanionUpdateBanner(
    modifier: Modifier = Modifier,
    viewModel: CompanionUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is CompanionUpdateState.UpdateAvailable -> OfferBanner(
            modifier = modifier,
            title = "Companion update available",
            detail = "${current.installed.companionDisplay()} → ${current.latestTag.tagDisplay()}",
            onInstall = { viewModel.install(current.serverId, current.downloadUrl) },
            onLater = { viewModel.skip(current.serverId, current.latestTag) },
        )

        is CompanionUpdateState.Missing -> OfferBanner(
            modifier = modifier,
            title = "Companion not installed",
            detail = "Install termx ${current.latestTag.tagDisplay()} (${current.arch}) on this server?",
            onInstall = { viewModel.install(current.serverId, current.downloadUrl) },
            onLater = { viewModel.skip(current.serverId, current.latestTag) },
        )

        is CompanionUpdateState.Installing -> InstallingBanner(
            modifier = modifier,
            log = current.log,
        )

        CompanionUpdateState.Installed -> InstalledBanner(
            modifier = modifier,
            onDismiss = { viewModel.dismiss() },
        )

        is CompanionUpdateState.Error -> ErrorBanner(
            modifier = modifier,
            message = current.message,
            onDismiss = { viewModel.dismiss() },
        )

        CompanionUpdateState.Idle,
        CompanionUpdateState.Checking,
        CompanionUpdateState.UpToDate,
        CompanionUpdateState.Skipped,
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
private fun OfferBanner(
    modifier: Modifier,
    title: String,
    detail: String,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    BannerSurface(modifier) {
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onLater) { Text("Later") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onInstall) { Text("Install") }
        }
    }
}

@Composable
private fun InstallingBanner(
    modifier: Modifier,
    log: List<String>,
) {
    BannerSurface(modifier) {
        Text(
            text = "Installing companion…",
            style = MaterialTheme.typography.bodyMedium,
        )
        val tail = log.lastOrNull { it.isNotBlank() }
        if (tail != null) {
            Text(
                text = tail.take(120),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun InstalledBanner(
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    BannerSurface(modifier) {
        Text(
            text = "Companion installed",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(top = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ErrorBanner(
    modifier: Modifier,
    message: String,
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
                text = "Companion install failed: $message",
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

/**
 * Trim the raw `termx --version` capture (`"termx version 0.1.4"`) down to a
 * short label for the banner, falling back to "unknown" for the
 * version-unknown sentinel.
 */
private fun String.companionDisplay(): String {
    val token = trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotBlank() }
    return when {
        token == null -> "unknown"
        token.equals("unknown)", ignoreCase = true) -> "unknown"
        else -> token
    }
}

/** Drop the `termxd-` prefix from a release tag for display. */
private fun String.tagDisplay(): String = trim().removePrefix("termxd-")
