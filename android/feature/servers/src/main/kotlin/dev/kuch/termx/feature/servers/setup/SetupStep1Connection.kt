package dev.kuch.termx.feature.servers.setup

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.kuch.termx.core.domain.model.AuthType
import java.util.UUID

/**
 * Step 1 of the wizard: the raw connection fields.
 *
 * Layout mirrors [dev.kuch.termx.feature.servers.AddEditServerSheet]'s form
 * so users who've seen the quick-add sheet recognise the controls. The one
 * addition is the "Generate key" inline button — tapping it opens a small
 * dialog that hands a label to [SetupWizardViewModel.generateAndSelectKey].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupStep1Connection(
    state: SetupWizardUiState,
    onLabelChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAuthTypeChange: (AuthType) -> Unit,
    onKeyPairSelected: (UUID?) -> Unit,
    onPasswordChange: (String) -> Unit,
    onGenerateKey: (String) -> Unit,
    onDismissKeyGenError: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGenerateDialog by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = "Connection details",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Where does your VPS live, and how do we get in?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.draft.label,
            onValueChange = onLabelChange,
            label = { Text("Label") },
            placeholder = { Text("Defaults to username@host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.draft.host,
            onValueChange = onHostChange,
            label = { Text("Host") },
            placeholder = { Text("vps.example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.draft.username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.draft.port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        Text("Authentication", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.draft.authType == AuthType.KEY,
                onClick = { onAuthTypeChange(AuthType.KEY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Key") }
            SegmentedButton(
                selected = state.draft.authType == AuthType.PASSWORD,
                onClick = { onAuthTypeChange(AuthType.PASSWORD) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Password") }
        }

        Spacer(Modifier.height(12.dp))

        when (state.draft.authType) {
            AuthType.KEY -> KeyPickerWithGenerate(
                state = state,
                onKeyPairSelected = onKeyPairSelected,
                onRequestGenerate = { showGenerateDialog = true },
            )
            AuthType.PASSWORD -> OutlinedTextField(
                value = state.draft.password,
                onValueChange = onPasswordChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onNext,
            enabled = state.canAdvanceFromStep1,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Next") }
    }

    if (showGenerateDialog) {
        GenerateKeyDialog(
            isGenerating = state.isGeneratingKey,
            vaultLocked = state.vaultLocked,
            error = state.keyGenError,
            onDismiss = {
                if (!state.isGeneratingKey) {
                    showGenerateDialog = false
                    onDismissKeyGenError()
                }
            },
            onGenerate = { label ->
                onGenerateKey(label)
            },
            onGeneratedClose = {
                showGenerateDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPickerWithGenerate(
    state: SetupWizardUiState,
    onKeyPairSelected: (UUID?) -> Unit,
    onRequestGenerate: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.availableKeys.firstOrNull { it.id == state.draft.keyPairId }
    val display = selected?.label
        ?: if (state.availableKeys.isEmpty()) "No keys yet" else "Pick a key"

    Column {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = display,
                onValueChange = { },
                readOnly = true,
                label = { Text("SSH key") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (state.availableKeys.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No keys yet — generate one below") },
                        onClick = { expanded = false },
                        enabled = false,
                    )
                } else {
                    state.availableKeys.forEach { k ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(k.label)
                                    Text(
                                        k.algorithm.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                onKeyPairSelected(k.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onRequestGenerate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text("Generate Ed25519 key")
        }
    }
}

@Composable
private fun GenerateKeyDialog(
    isGenerating: Boolean,
    vaultLocked: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit,
    onGeneratedClose: () -> Unit,
) {
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate Ed25519 key") },
        text = {
            Column {
                if (vaultLocked) {
                    Text(
                        "Vault is locked — unlock the app from the home screen first.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("e.g. prod-vps") },
                    singleLine = true,
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Modern, fast, small. Paired public key is sharable in step 5.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onGenerate(label)
                    // The viewmodel transitions draft.keyPairId when generation
                    // succeeds and surfaces `keyGenError` on failure. We close
                    // eagerly — the picker re-binds via the streaming keys
                    // flow so the new key appears selected once Ed25519 work
                    // finishes (sub-second on modern hardware).
                    onGeneratedClose()
                },
                enabled = !isGenerating && !vaultLocked && label.isNotBlank(),
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Generating…")
                } else {
                    Text("Generate")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isGenerating) { Text("Cancel") }
        },
    )
}
