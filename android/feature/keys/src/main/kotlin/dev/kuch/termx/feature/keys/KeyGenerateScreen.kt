package dev.kuch.termx.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.KeyAlgorithm

/**
 * Form for generating a new Ed25519 / RSA-4096 key pair.
 *
 * The vault must be unlocked — if it isn't, a banner explains the block and
 * the Generate button is disabled. Unlocking is handled elsewhere (launching
 * the app already does it via the [dev.kuch.termx.feature.keys.unlock]
 * screen from Task #20); when a user deep-links here with a locked vault we
 * just ask them to head back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyGenerateScreen(
    onDone: (java.util.UUID) -> Unit,
    onBack: () -> Unit = {},
    viewModel: KeyGenerateViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generate key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(20.dp),
        ) {
            if (uiState.vaultLocked) {
                VaultLockedBanner()
                Spacer(Modifier.height(16.dp))
            }

            Text("Algorithm", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = uiState.algorithm == KeyAlgorithm.ED25519,
                    onClick = { viewModel.onAlgorithmChange(KeyAlgorithm.ED25519) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Ed25519") }
                SegmentedButton(
                    selected = uiState.algorithm == KeyAlgorithm.RSA_4096,
                    onClick = { viewModel.onAlgorithmChange(KeyAlgorithm.RSA_4096) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("RSA-4096") }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = when (uiState.algorithm) {
                    KeyAlgorithm.ED25519 ->
                        "Modern, fast, small. Recommended."
                    KeyAlgorithm.RSA_4096 ->
                        "Compatible with ancient hosts. ~10 s to generate."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = uiState.label,
                onValueChange = viewModel::onLabelChange,
                label = { Text("Label") },
                placeholder = { Text("e.g. prod-vps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.comment,
                onValueChange = viewModel::onCommentChange,
                label = { Text("Comment (optional)") },
                placeholder = { Text("termx-${uiState.label.ifBlank { "mykey" }}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { viewModel.generate(onDone) },
                enabled = uiState.canGenerate && !uiState.vaultLocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = when (uiState.algorithm) {
                            KeyAlgorithm.ED25519 -> "Generating Ed25519…"
                            KeyAlgorithm.RSA_4096 -> "Generating RSA-4096 (this may take a few seconds)…"
                        },
                    )
                } else {
                    Text("Generate")
                }
            }

            uiState.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun VaultLockedBanner() {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Vault is locked — unlock the app from the home screen before generating.",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
