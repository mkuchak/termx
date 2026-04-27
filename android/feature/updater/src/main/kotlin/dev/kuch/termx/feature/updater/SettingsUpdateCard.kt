package dev.kuch.termx.feature.updater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings card for the in-app updater. Shows the running version and
 * a "Check for updates" button that bypasses the 24h cache. The
 * actual update offer / progress / install UI is the [UpdateBanner]
 * on the server-list screen — this card is purely for initiating a
 * check and seeing what version you're on.
 */
@Composable
fun SettingsUpdateCard(
    installedVersion: String,
    modifier: Modifier = Modifier,
    viewModel: UpdaterViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isChecking = state is UpdaterState.Checking

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Updates",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Current version: v$installedVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = inlineStatus(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.refreshNow(installedVersion) },
                    enabled = !isChecking,
                ) {
                    Text(if (isChecking) "Checking…" else "Check for updates")
                }
            }
        }
    }
}

private fun inlineStatus(state: UpdaterState): String = when (state) {
    UpdaterState.Idle -> "Tap to check the GitHub Release page."
    UpdaterState.Checking -> "Checking GitHub for the latest release…"
    is UpdaterState.UpToDate -> "You're on the latest release."
    is UpdaterState.Available -> "${state.version} available — see the banner on the server list."
    is UpdaterState.Downloading -> "Downloading ${state.version}…"
    is UpdaterState.ReadyToInstall -> "${state.version} downloaded — tap Install on the banner."
    UpdaterState.Skipped -> "This version was skipped. Newer versions will surface again."
    is UpdaterState.Error -> "Last check failed: ${state.message}"
}
