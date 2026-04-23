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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.feature.servers.TestResult

/**
 * Step 2: show a read-only summary of step-1 fields, let the user fire a live
 * SSH handshake, and gate advance on a Success result. Retry is available on
 * error; the user can go back via the top-bar arrow.
 */
@Composable
fun SetupStep2TestConnection(
    state: SetupWizardUiState,
    onRunTest: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text("Test the connection", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "We'll open an SSH handshake with the fields below. You won't " +
                "advance until the connection succeeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        SummaryCard(state = state)

        Spacer(Modifier.height(20.dp))

        when (val r = state.testResult) {
            TestResult.Idle -> {
                Button(
                    onClick = onRunTest,
                    enabled = state.canTestConnection,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test connection") }
            }
            TestResult.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Testing…")
                }
            }
            TestResult.Success -> {
                SuccessCard()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }
            is TestResult.Error -> {
                ErrorCard(message = r.message)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onRunTest,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SetupWizardUiState) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Host", fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.draft.host}:${state.draft.port}",
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Username", fontWeight = FontWeight.SemiBold)
                Text(state.draft.username, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Auth", fontWeight = FontWeight.SemiBold)
                val label = when (state.draft.authType) {
                    AuthType.KEY -> {
                        val key = state.availableKeys.firstOrNull { it.id == state.draft.keyPairId }
                        "key · ${key?.label ?: "(unset)"}"
                    }
                    AuthType.PASSWORD -> "password"
                }
                Text(label, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun SuccessCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Connected successfully",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
