package dev.kuch.termx.feature.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.AuthType
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Modal bottom sheet that adds a new [dev.kuch.termx.core.domain.model.Server]
 * or edits an existing one.
 *
 * - [serverId] == null → "Add server", fresh form.
 * - [serverId] != null → "Edit server", form pre-filled.
 *
 * Caller wiring (Task #21 hooks this up): show the sheet in response to the
 * server list's FAB tap, call [onDismiss] when the user swipes down or hits
 * Cancel, and navigate after [onSaved] with the persisted id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditServerSheet(
    serverId: UUID? = null,
    onDismiss: () -> Unit,
    onSaved: (UUID) -> Unit,
    onManageKeys: (() -> Unit)? = null,
    viewModel: AddEditServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showUntestedWarning by remember { mutableStateOf(false) }

    LaunchedEffect(serverId) { viewModel.initialize(serverId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        AddEditServerSheetContent(
            state = state,
            onLabelChange = viewModel::onLabelChange,
            onHostChange = viewModel::onHostChange,
            onPortChange = viewModel::onPortChange,
            onUsernameChange = viewModel::onUsernameChange,
            onAuthTypeChange = viewModel::onAuthTypeChange,
            onKeyPairSelected = viewModel::onKeyPairSelected,
            onPasswordChange = viewModel::onPasswordChange,
            onPasswordVisibilityToggle = viewModel::onPasswordVisibilityToggle,
            onUseMoshChange = viewModel::onUseMoshChange,
            onAutoAttachTmuxChange = viewModel::onAutoAttachTmuxChange,
            onTmuxSessionNameChange = viewModel::onTmuxSessionNameChange,
            onGroupSelected = viewModel::onGroupSelected,
            onManageKeys = {
                // Task #23: route to the keys screen. Callers that don't
                // provide a navigation lambda (previews, tests) fall back
                // to the dismiss behaviour from pre-#23.
                val nav = onManageKeys
                if (nav != null) {
                    onDismiss()
                    nav()
                } else {
                    onDismiss()
                }
            },
            onTestConnection = { viewModel.testConnection() },
            onCancel = onDismiss,
            onDelete = { showDeleteConfirm = true },
            onSave = {
                val untested = state.testResult !is TestResult.Success &&
                    state.testResult !is TestResult.Running
                if (untested) {
                    showUntestedWarning = true
                } else {
                    scope.launch {
                        val id = viewModel.save()
                        onSaved(id)
                    }
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete server?") },
            text = {
                Text(
                    "This removes the server from your list. " +
                        "Any stored private key is kept in the vault.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        viewModel.delete()
                        onDismiss()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showUntestedWarning) {
        AlertDialog(
            onDismissRequest = { showUntestedWarning = false },
            title = { Text("Save without testing?") },
            text = {
                Text(
                    "You haven't confirmed the connection. Save anyway? " +
                        "You can always test and edit later.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUntestedWarning = false
                    scope.launch {
                        val id = viewModel.save()
                        onSaved(id)
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showUntestedWarning = false }) { Text("Go back") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditServerSheetContent(
    state: AddEditServerUiState,
    onLabelChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAuthTypeChange: (AuthType) -> Unit,
    onKeyPairSelected: (UUID?) -> Unit,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
    onUseMoshChange: (Boolean) -> Unit,
    onAutoAttachTmuxChange: (Boolean) -> Unit,
    onTmuxSessionNameChange: (String) -> Unit,
    onGroupSelected: (UUID?) -> Unit,
    onManageKeys: () -> Unit,
    onTestConnection: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    val isEdit = state.id != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
    ) {
        Text(
            text = if (isEdit) "Edit server" else "Add server",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Label, host, credentials. Test before saving.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.label,
            onValueChange = onLabelChange,
            label = { Text("Label") },
            placeholder = { Text("Defaults to username@host") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.host,
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
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = onPortChange,
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Authentication",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(6.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = state.authType == AuthType.KEY,
                onClick = { onAuthTypeChange(AuthType.KEY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Key") }
            SegmentedButton(
                selected = state.authType == AuthType.PASSWORD,
                onClick = { onAuthTypeChange(AuthType.PASSWORD) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Password") }
        }

        Spacer(Modifier.height(12.dp))

        when (state.authType) {
            AuthType.KEY -> KeyPicker(
                state = state,
                onKeyPairSelected = onKeyPairSelected,
                onManageKeys = onManageKeys,
            )
            AuthType.PASSWORD -> PasswordField(
                state = state,
                onPasswordChange = onPasswordChange,
                onPasswordVisibilityToggle = onPasswordVisibilityToggle,
            )
        }

        if (state.authType == AuthType.PASSWORD) {
            Spacer(Modifier.height(6.dp))
            NoticeRow(
                "Password storage requires vault unlock (coming in Phase 2.5). " +
                    "You can still test now; on save the password isn't persisted yet.",
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Session",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(6.dp))

        SwitchRow(
            label = "Use mosh (survives network hiccups)",
            checked = state.useMosh,
            onCheckedChange = onUseMoshChange,
        )
        SwitchRow(
            label = "Auto-attach tmux on connect",
            checked = state.autoAttachTmux,
            onCheckedChange = onAutoAttachTmuxChange,
        )

        if (state.autoAttachTmux) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tmuxSessionName,
                onValueChange = onTmuxSessionNameChange,
                label = { Text("tmux session name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        GroupPicker(
            state = state,
            onGroupSelected = onGroupSelected,
        )

        Spacer(Modifier.height(20.dp))

        OutlinedButton(
            onClick = onTestConnection,
            enabled = state.canTestConnection && state.testResult !is TestResult.Running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test connection") }

        Spacer(Modifier.height(8.dp))

        TestResultRow(state.testResult)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            if (isEdit) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onSave,
                enabled = state.canSave,
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPicker(
    state: AddEditServerUiState,
    onKeyPairSelected: (UUID?) -> Unit,
    onManageKeys: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.availableKeys.firstOrNull { it.id == state.selectedKeyPairId }
    val display = selected?.label
        ?: if (state.availableKeys.isEmpty()) "No keys yet" else "Pick a key"

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
                    text = { Text("No keys yet — add one first") },
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage keys…") },
                onClick = {
                    expanded = false
                    onManageKeys()
                },
            )
        }
    }
}

@Composable
private fun PasswordField(
    state: AddEditServerUiState,
    onPasswordChange: (String) -> Unit,
    onPasswordVisibilityToggle: () -> Unit,
) {
    val transformation: VisualTransformation =
        if (state.passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            TextButton(onClick = onPasswordVisibilityToggle) {
                Text(if (state.passwordVisible) "Hide" else "Show")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupPicker(
    state: AddEditServerUiState,
    onGroupSelected: (UUID?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.availableGroups.firstOrNull { it.id == state.selectedGroupId }
    val display = selected?.name ?: "Ungrouped"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (state.availableGroups.isNotEmpty()) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = display,
            onValueChange = { },
            readOnly = true,
            enabled = state.availableGroups.isNotEmpty(),
            label = { Text("Group") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Ungrouped") },
                onClick = {
                    onGroupSelected(null)
                    expanded = false
                },
            )
            state.availableGroups.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name) },
                    onClick = {
                        onGroupSelected(g.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TestResultRow(result: TestResult) {
    when (result) {
        TestResult.Idle -> Box(Modifier.height(24.dp))
        TestResult.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Text("Testing…", modifier = Modifier.padding(start = 8.dp))
        }
        TestResult.Success -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Connected successfully",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        is TestResult.Error -> Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                result.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun NoticeRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Preview for Android Studio. Renders [AddEditServerSheetContent] directly
 * (not the `ModalBottomSheet` wrapper) so the layout panel can show the body
 * without needing a sheet host.
 */
@Preview(name = "Add server", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun AddEditServerSheetContentPreview() {
    AddEditServerSheetContent(
        state = AddEditServerUiState(
            label = "prod-vps",
            host = "vps.example.com",
            port = "22",
            username = "ubuntu",
            authType = AuthType.KEY,
            useMosh = true,
            autoAttachTmux = true,
            tmuxSessionName = "main",
            testResult = TestResult.Idle,
        ),
        onLabelChange = {},
        onHostChange = {},
        onPortChange = {},
        onUsernameChange = {},
        onAuthTypeChange = {},
        onKeyPairSelected = {},
        onPasswordChange = {},
        onPasswordVisibilityToggle = {},
        onUseMoshChange = {},
        onAutoAttachTmuxChange = {},
        onTmuxSessionNameChange = {},
        onGroupSelected = {},
        onManageKeys = {},
        onTestConnection = {},
        onCancel = {},
        onDelete = {},
        onSave = {},
    )
}
