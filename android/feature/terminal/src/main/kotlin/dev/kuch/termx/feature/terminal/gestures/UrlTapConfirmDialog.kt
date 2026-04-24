package dev.kuch.termx.feature.terminal.gestures

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Confirm dialog fired by a double-tap on a URL in the terminal view.
 *
 * Double-tap semantics are deliberate: single-tap inside TerminalView
 * already focuses / dismisses IME / sends a mouse event in some modes,
 * so hijacking it would surprise power users. Double-tap means "I meant
 * it" without rebinding long-press (which belongs to text selection).
 *
 * The confirmation step also guards against the "I copy-pasted a
 * phishing URL into my logs" footgun — the user sees the exact URL
 * before a browser opens.
 */
@Composable
fun UrlTapConfirmDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open URL?") },
        text = { Text(url) },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Log.w("UrlTap", "No browser available", e)
                    Toast.makeText(context, "No app available to open URLs", Toast.LENGTH_SHORT).show()
                }
                onDismiss()
            }) { Text("Open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
