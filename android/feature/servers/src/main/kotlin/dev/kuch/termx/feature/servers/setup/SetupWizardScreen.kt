package dev.kuch.termx.feature.servers.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import java.util.UUID

/**
 * 5-step guided onboarding for a new VPS.
 *
 * Entry: the server-list FAB offers "Guided setup" as a second option next to
 * "Quick add" (the bare [dev.kuch.termx.feature.servers.AddEditServerSheet]).
 *
 * Flow:
 * 1. Connection details (host / port / username / auth)
 * 2. Test connection — cannot advance without a green Success
 * 3. Install termxd companion (placeholder — real install is Task #33)
 * 4. Review + Save — writes the row to Room
 * 5. Share the public key (key-auth only; password auth skips to `onDone`)
 *
 * The exit X on the top bar pops a confirmation; Back either steps the wizard
 * backward or (on step 1) exits via [onCancel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    onDone: (UUID) -> Unit,
    onCancel: () -> Unit,
    viewModel: SetupWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installState: InstallStep3State by viewModel.installStep3State.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Step ${state.currentStep} of 5") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.currentStep == 1) {
                                onCancel()
                            } else {
                                viewModel.back()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.requestExit() }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Exit wizard",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinearProgressIndicator(
                progress = { state.currentStep / 5f },
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { it / 3 } + fadeOut())
                    }
                },
                label = "wizard-step",
            ) { step ->
                when (step) {
                    1 -> SetupStep1Connection(
                        state = state,
                        onLabelChange = viewModel::onLabelChange,
                        onHostChange = viewModel::onHostChange,
                        onPortChange = viewModel::onPortChange,
                        onUsernameChange = viewModel::onUsernameChange,
                        onAuthTypeChange = viewModel::onAuthTypeChange,
                        onKeyPairSelected = viewModel::onKeyPairSelected,
                        onPasswordChange = viewModel::onPasswordChange,
                        onGenerateKey = { label ->
                            viewModel.generateAndSelectKey(label)
                        },
                        onDismissKeyGenError = viewModel::dismissKeyGenError,
                        onNext = viewModel::next,
                    )
                    2 -> SetupStep2TestConnection(
                        state = state,
                        onRunTest = viewModel::runTest,
                        onNext = viewModel::next,
                    )
                    3 -> SetupStep3InstallTermxd(
                        state = installState,
                        onPreview = viewModel::runCompanionPreview,
                        onInstall = viewModel::runCompanionInstall,
                        onRetry = viewModel::runCompanionDetect,
                        onNext = viewModel::advanceFromCompanion,
                        onSkip = viewModel::advanceFromCompanion,
                    )
                    4 -> SetupStep4SaveServer(
                        state = state,
                        onEditStep1 = viewModel::jumpToStep1,
                        onSave = {
                            // Save may transition to step 5 internally (key
                            // auth) or call onDone directly (password auth).
                            viewModel.save(onDoneIfPasswordAuth = { id -> onDone(id) })
                        },
                    )
                    5 -> SetupStep5PublicKey(
                        state = state,
                        onDone = {
                            val id = state.savedServerId
                            if (id != null) onDone(id) else onCancel()
                        },
                    )
                    else -> Unit
                }
            }
        }
    }

    if (state.showExitConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissExit,
            title = { Text("Discard setup?") },
            text = {
                Text(
                    if (state.savedServerId != null && state.draft.authType == AuthType.KEY) {
                        "The server row is already saved. Exiting skips the " +
                            "public-key share step — you can still copy the " +
                            "key later from the Keys screen."
                    } else {
                        "Anything you entered in the wizard will be lost."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissExit()
                    val id = state.savedServerId
                    if (id != null) onDone(id) else onCancel()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissExit) { Text("Keep going") }
            },
        )
    }
}
