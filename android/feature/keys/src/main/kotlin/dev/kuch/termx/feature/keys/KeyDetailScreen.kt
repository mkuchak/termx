package dev.kuch.termx.feature.keys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import java.util.UUID

/**
 * Detail view for a single key. Shows label, algorithm, fingerprint, a
 * monospace dump of the public-key line, and three export affordances:
 *   - Copy: pushes the line to the system clipboard.
 *   - Share: fires an `Intent.ACTION_SEND` with the line as plain text.
 *   - QR: full-screen modal with the key rendered as a 512×512 QR code.
 *
 * Delete flows through a confirmation. When any server references the key
 * the dialog offers reassignment to another key first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyDetailScreen(
    keyId: UUID,
    onBack: () -> Unit,
    viewModel: KeyDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(keyId) { viewModel.load(keyId) }

    var showQr by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showDelete = true }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.keyPair == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Key not found.", color = MaterialTheme.colorScheme.error)
            }

            else -> DetailContent(
                padding = padding,
                keyPair = uiState.keyPair!!,
                fingerprint = uiState.fingerprint,
                referencingCount = uiState.referencingServers.size,
                onCopy = { copyToClipboard(context, uiState.keyPair!!.publicKey) },
                onShare = { shareText(context, uiState.keyPair!!.publicKey) },
                onShowQr = { showQr = true },
            )
        }
    }

    if (showQr && uiState.keyPair != null) {
        QrModal(
            content = uiState.keyPair!!.publicKey,
            onDismiss = { showQr = false },
        )
    }

    if (showDelete && uiState.keyPair != null) {
        DeleteDialog(
            referencingCount = uiState.referencingServers.size,
            candidates = uiState.otherKeys,
            onDismiss = { showDelete = false },
            onDelete = {
                showDelete = false
                viewModel.delete(onBack)
            },
            onReassign = { toId ->
                showDelete = false
                viewModel.reassignAndDelete(toId, onBack)
            },
        )
    }
}

@Composable
private fun DetailContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    keyPair: KeyPair,
    fingerprint: String,
    referencingCount: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = keyPair.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (keyPair.algorithm) {
                KeyAlgorithm.ED25519 -> "Ed25519"
                KeyAlgorithm.RSA_4096 -> "RSA-4096"
            } + " · " + (
                if (referencingCount == 0) "unused"
                else "used by $referencingCount server" + if (referencingCount == 1) "" else "s"
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Section(title = "Fingerprint") {
            Text(
                text = fingerprint,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(12.dp))
        Section(title = "Public key (OpenSSH)") {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = keyPair.publicKey,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy public key",
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Share")
            }
            OutlinedButton(onClick = onShowQr, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCode, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("QR")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun QrModal(content: String, onDismiss: () -> Unit) {
    val bitmap = remember(content) { renderQrBitmap(content, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Public key QR") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .size(300.dp)
                        .clickable(onClick = onDismiss),
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Public key QR",
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Scan to receive the public key",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    referencingCount: Int,
    candidates: List<KeyPair>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onReassign: (UUID) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (referencingCount == 0) "Delete key?" else "Key is in use",
            )
        },
        text = {
            if (referencingCount == 0) {
                Text("This removes the public-key row and its vault blob. This cannot be undone.")
            } else {
                val pluralS = if (referencingCount == 1) "" else "s"
                Column {
                    Text(
                        "This key is used by $referencingCount server$pluralS. " +
                            "Pick a replacement key below to reassign before deleting.",
                    )
                    Spacer(Modifier.height(12.dp))
                    if (candidates.isEmpty()) {
                        Text(
                            "No other keys to reassign to. Generate a replacement first.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        candidates.forEach { k ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { onReassign(k.id) },
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(k.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = when (k.algorithm) {
                                            KeyAlgorithm.ED25519 -> "Ed25519"
                                            KeyAlgorithm.RSA_4096 -> "RSA-4096"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (referencingCount == 0) {
                Button(
                    onClick = onDelete,
                ) { Text("Delete") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        dismissButton = if (referencingCount == 0) {
            {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        } else {
            null
        },
    )
}

// --- helpers ---------------------------------------------------------------

private fun renderQrBitmap(content: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(
                x,
                y,
                if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
            )
        }
    }
    bmp
} catch (_: Throwable) {
    null
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val clip = ClipData.newPlainText("termx public key", text)
    clipboard.setPrimaryClip(clip)
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, "Share public key").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
