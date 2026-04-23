package dev.kuch.termx

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Host Activity for the whole app.
 *
 * Uses [FragmentActivity] (not plain ComponentActivity) so Task #20's
 * BiometricPrompt can attach on Android 9+ without repainting the surface.
 * Everything user-facing is Compose — the Fragment ancestry is purely a
 * BiometricPrompt compatibility requirement.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TermxTheme {
                TermxNavHost()
            }
        }
    }
}

@Composable
private fun TermxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
