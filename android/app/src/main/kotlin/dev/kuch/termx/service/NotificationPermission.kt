package dev.kuch.termx.service

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Fires the Android 13+ `POST_NOTIFICATIONS` runtime permission prompt
 * once per launch. The system only actually shows the dialog on the
 * first call after install; subsequent launches (already granted or
 * permanently denied) are cheap no-ops.
 *
 * Hooked into [dev.kuch.termx.TermxNavHost] at the root so it fires on
 * first composition rather than gating any specific screen. We don't
 * need to branch on the result — if the user denies, the foreground
 * service still runs; they just won't see the persistent chrome.
 */
@Composable
fun NotificationPermissionRequester() {
    val launcher = rememberLauncherForActivityResult(RequestPermission()) { /* no-op */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
