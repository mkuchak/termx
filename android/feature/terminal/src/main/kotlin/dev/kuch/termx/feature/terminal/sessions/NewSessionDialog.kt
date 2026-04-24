/*
 * Copyright (C) termx contributors.
 * Licensed under the MIT license (see LICENSE at the repository root).
 */
package dev.kuch.termx.feature.terminal.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kuch.termx.libs.sshnative.validateTmuxSessionName

/**
 * "New session" AlertDialog. Single text field for the session name,
 * live-validated against [validateTmuxSessionName] so the user gets
 * an inline error before hitting Confirm.
 *
 * [onConfirm] is invoked with a name the validator has already
 * accepted — callers still want to handle their own failure path
 * (e.g. tmux couldn't create the session), but basic shape is
 * already filtered.
 */
@Composable
fun NewSessionDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initialName) }
    val validation = validateTmuxSessionName(name)
    val isValid = name.isNotBlank() && validation.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New tmux session") },
        text = {
            Column {
                Text(
                    text = "Pick a name. Letters, digits, hyphens and underscores work.",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isNotBlank() && validation.isFailure,
                    supportingText = {
                        val err = validation.exceptionOrNull()?.message
                        if (name.isNotBlank() && err != null) {
                            Text(err)
                        }
                    },
                    label = { Text("Name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(name.trim()) },
                enabled = isValid,
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Rename AlertDialog. Mirrors [NewSessionDialog] but seeded with the
 * current name and with a slightly different title / confirm label.
 */
@Composable
fun RenameSessionDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val validation = validateTmuxSessionName(name)
    val isValid = name.isNotBlank() && validation.isSuccess && name != currentName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = name.isNotBlank() && validation.isFailure,
                    supportingText = {
                        val err = validation.exceptionOrNull()?.message
                        if (name.isNotBlank() && err != null) {
                            Text(err)
                        }
                    },
                    label = { Text("New name") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (isValid) onConfirm(name.trim()) },
                enabled = isValid,
            ) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Confirmation dialog for `tmux kill-session -t <name>`. Separate from
 * rename/new because it's destructive and server-side — not undoable
 * from this surface — so we want a clear danger framing.
 */
@Composable
fun KillSessionDialog(
    sessionName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kill session?") },
        text = {
            Text(
                "This will terminate the tmux session \"$sessionName\" on the server. " +
                    "Any running programs inside it will be killed.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Kill") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
