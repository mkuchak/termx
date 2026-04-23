package dev.kuch.termx.feature.keys

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.KeyAlgorithm

/**
 * SAF-based import flow.
 *
 * 1. User taps "Choose file…" → system document picker opens with `*/*`.
 * 2. Bytes read, a no-passphrase parse is attempted automatically.
 * 3. If the parser throws about a passphrase, the passphrase field is
 *    surfaced and we re-parse on demand.
 * 4. On success: show a preview (algorithm, fingerprint, derived public
 *    line) and a Save button that writes to the vault + Room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyImportScreen(
    onDone: (java.util.UUID) -> Unit,
    onBack: () -> Unit = {},
    viewModel: KeyImportViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.onFilePicked(uri)
            }
        },
    )

    var showPassphrase by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import key") },
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
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Vault is locked — unlock the app before saving.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = uiState.sourceFileName?.let { "Re-pick file ($it)" }
                        ?: "Choose file…",
                )
            }

            Spacer(Modifier.height(12.dp))

            if (uiState.rawBytes != null || uiState.error != null) {
                OutlinedTextField(
                    value = uiState.passphrase,
                    onValueChange = viewModel::onPassphraseChange,
                    label = { Text("Passphrase (leave blank if unencrypted)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showPassphrase) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        androidx.compose.material3.TextButton(
                            onClick = { showPassphrase = !showPassphrase },
                        ) {
                            Text(if (showPassphrase) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.parse() },
                    enabled = !uiState.isBusy && uiState.rawBytes != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Parse")
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

            if (uiState.isBusy) {
                Spacer(Modifier.height(12.dp))
                Row {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Working…")
                }
            }

            uiState.preview?.let { preview ->
                Spacer(Modifier.height(20.dp))
                PreviewCard(
                    algorithm = preview.algorithm,
                    fingerprint = preview.fingerprint,
                    publicKey = preview.publicKey,
                )

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = uiState.label,
                    onValueChange = viewModel::onLabelChange,
                    label = { Text("Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = !uiState.isBusy && !uiState.vaultLocked && uiState.label.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save to vault")
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    algorithm: KeyAlgorithm,
    fingerprint: String,
    publicKey: String,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Parsed successfully",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Algorithm: " + when (algorithm) {
                    KeyAlgorithm.ED25519 -> "Ed25519"
                    KeyAlgorithm.RSA_4096 -> "RSA-4096"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = publicKey,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
