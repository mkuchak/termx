package dev.kuch.termx.feature.servers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AlertPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Task #45 home-screen card asking the user to exclude termx from
 * Doze / battery optimization.
 *
 * Visibility logic:
 *  - Detect state via `PowerManager.isIgnoringBatteryOptimizations`.
 *  - Hidden when the app is already whitelisted *or* when the user has
 *    hit "Don't ask again" ([AlertPreferences.batteryOptPromptDismissed]).
 *  - Re-checks on every composition so revoking the exclusion in
 *    Android Settings causes the card to reappear the next time the
 *    user lands on the server list.
 *
 * "Enable" flow fires an explicit `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
 * intent targeting our package; Android shows a native confirmation
 * dialog, and we don't need an [ActivityResultLauncher] because the
 * visibility check on the next resume settles the UI.
 */
@Composable
fun BatteryOptimizationPrompt(
    modifier: Modifier = Modifier,
    viewModel: BatteryOptimizationPromptViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()

    // Re-read the OS state on every composition. `mutableStateOf` with a
    // LaunchedEffect(dismissed) keyed trigger lets us re-poll after
    // return-from-Settings flips the exclusion.
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LaunchedEffect(dismissed) {
        ignoring = isIgnoringBatteryOptimizations(context)
    }

    if (ignoring || dismissed) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Keep sessions alive",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "To keep sessions alive when the screen is off, exclude termx " +
                    "from battery optimization.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = viewModel::onDismissForever) {
                    Text("Don't ask again")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    launchBatteryOptimizationSettings(context)
                }) {
                    Text("Enable")
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun launchBatteryOptimizationSettings(context: Context) {
    // The targeted-package variant of the intent deep-links straight to
    // the "allow for termx?" system dialog; without the `package:` data
    // URI Android would drop us into the generic battery-optimization
    // list and force the user to scroll for the row.
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            // Some OEMs strip the explicit-package variant; fall back to
            // the generic settings screen so the user can still allow us
            // manually.
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            runCatching { context.startActivity(fallback) }
        }
}

/**
 * Minimal VM whose only job is to read + flip the
 * [AlertPreferences.batteryOptPromptDismissed] flag.
 */
@HiltViewModel
class BatteryOptimizationPromptViewModel @Inject constructor(
    private val alertPreferences: AlertPreferences,
) : ViewModel() {
    val dismissed: StateFlow<Boolean> = alertPreferences.batteryOptPromptDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onDismissForever() {
        viewModelScope.launch {
            alertPreferences.setBatteryOptPromptDismissed(true)
        }
    }
}
