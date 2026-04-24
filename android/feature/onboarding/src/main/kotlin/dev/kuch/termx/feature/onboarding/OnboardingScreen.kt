package dev.kuch.termx.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch

/**
 * First-run onboarding hosted on a 3-page [HorizontalPager] with a simple
 * dot indicator. See Task #46 in docs/ROADMAP.md.
 *
 * The three pages are:
 *
 * 1. Welcome — explains what termx is (mobile control surface for Claude
 *    Code on a VPS).
 * 2. Biometric vault — calls out that SSH private keys never leave the
 *    phone in plaintext. Optionally routes to the existing vault-unlock
 *    flow via [onSetupBiometric] when the user taps "Set up biometric".
 * 3. Add your first server — jumps into the Setup Wizard or skips to an
 *    empty server list.
 *
 * [onFinish] flips the `onboarding_complete` preference and lets the host
 * NavHost re-evaluate its start destination.
 */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onLaunchSetupWizard: () -> Unit,
    onSetupBiometric: () -> Unit = {},
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    val completeAnd = { block: () -> Unit ->
        viewModel.markComplete()
        block()
        onFinish()
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> BiometricPage(onSetupBiometric = {
                        // Mark complete so the user isn't bounced back
                        // here after the vault-unlock round trip.
                        viewModel.markComplete()
                        onSetupBiometric()
                        onFinish()
                    })
                    2 -> AddServerPage(
                        onLaunchSetupWizard = { completeAnd(onLaunchSetupWizard) },
                        onSkip = { completeAnd {} },
                    )
                }
            }

            DotIndicator(
                pageCount = 3,
                currentPage = pagerState.currentPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { completeAnd {} },
                    enabled = pagerState.currentPage != 2,
                ) {
                    Text("Skip")
                }

                if (pagerState.currentPage < 2) {
                    Button(onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }) {
                        Text("Next")
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
            }
        }
    }
}

@Composable
private fun WelcomePage(padding: PaddingValues = PaddingValues(32.dp)) {
    PageScaffold(padding = padding) {
        TermxLogo()
        Spacer(Modifier.height(24.dp))
        Text(
            text = "termx: your VPS in your pocket",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "termx turns your phone into a control surface for Claude Code running on any SSH-reachable server.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BiometricPage(
    onSetupBiometric: () -> Unit,
    padding: PaddingValues = PaddingValues(32.dp),
) {
    PageScaffold(padding = padding) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "🔒",
                    fontSize = 40.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Your keys never leave the phone in plaintext.",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "SSH private keys and saved passwords are encrypted under the Android Keystore and unlocked with your device biometric or credential. termx caches them in memory only while you're using the app.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onSetupBiometric) {
            Text("Set up biometric")
        }
    }
}

@Composable
private fun AddServerPage(
    onLaunchSetupWizard: () -> Unit,
    onSkip: () -> Unit,
    padding: PaddingValues = PaddingValues(32.dp),
) {
    PageScaffold(padding = padding) {
        Surface(
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "🖥️",
                    fontSize = 40.sp,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Let's connect.",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Add your first server to start streaming a tmux session from your VPS. The Setup Wizard walks you through host, port, credentials, and an optional termxd install.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onLaunchSetupWizard,
            colors = ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Launch Setup Wizard")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip for now")
        }
    }
}

@Composable
private fun PageScaffold(
    padding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun TermxLogo() {
    Surface(
        modifier = Modifier.size(112.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "tx",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DotIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            val color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
