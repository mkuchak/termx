package dev.kuch.termx.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kuch.termx.core.domain.ptt.PttLanguage

/**
 * App Settings — font size slider, Gemini API key entry, and theme picker.
 *
 * Task #17 owns the font size section; Task #18 owns the theme section;
 * Task #41 owns the Gemini API key section. All settings persist via
 * [SettingsViewModel]. The Gemini API key goes through the sandboxed
 * vault instead of DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /**
     * Slot for the updater card (v1.1.17). Filled by the :app
     * NavHost with [dev.kuch.termx.feature.updater.SettingsUpdateCard]
     * so :feature:settings stays free of a :feature:updater dep.
     */
    updaterCard: @Composable () -> Unit = {},
    /**
     * herdr agent-alert side-effects that need `:app`-only singletons
     * (`AgentAlertPoster`, `NotificationChannels`, `UnifiedPushManager`).
     * `:feature:settings` cannot depend on `:app` (apps depend on
     * features, not vice-versa), so — exactly like [updaterCard] — the
     * :app NavHost supplies these as callbacks/state. They default to
     * inert values so the screen still renders in isolation/previews.
     *
     *  - [onTestAlert] posts a sample alert on `termx.agent` + vibrates.
     *  - [onAgentBypassDndChange] persists the pref AND (in :app) rebuilds
     *    the channel with setBypassDnd + launches the policy-access screen.
     *  - [pushDistributors] / [pushAckDistributor] back the UnifiedPush
     *    picker + "no push app installed" CTA + chosen-distributor status.
     *  - [onPushEnabledChange] flips the master switch via
     *    `UnifiedPushManager.enable()/disable()`.
     *  - [onChoosePushDistributor] saves the user's distributor pick.
     */
    onTestAlert: () -> Unit = {},
    onAgentBypassDndChange: (Boolean) -> Unit = {},
    pushDistributors: List<String> = emptyList(),
    pushAckDistributor: String? = null,
    onPushEnabledChange: (Boolean) -> Unit = {},
    onChoosePushDistributor: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.geminiSaveStatus) {
        val msg = state.geminiSaveStatus ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.consumeGeminiStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { FontSizeSection(state = state, onChange = viewModel::setFontSize) }
            item {
                GeminiApiKeySection(
                    present = state.geminiKeyPresent,
                    onSave = viewModel::saveGeminiKey,
                    onClear = viewModel::clearGeminiKey,
                )
            }
            item {
                PttLanguageSection(
                    sourceLanguage = state.pttSourceLanguage,
                    targetLanguage = state.pttTargetLanguage,
                    context = state.pttContext,
                    onSourceChange = viewModel::setPttSourceLanguage,
                    onTargetChange = viewModel::setPttTargetLanguage,
                    onContextChange = viewModel::setPttContext,
                )
            }
            item { updaterCard() }
            item {
                NotificationsSection(
                    state = state,
                    onAgentFinishedChange = viewModel::setAgentFinishedEnabled,
                    onStrongVibrationChange = viewModel::setAgentStrongVibration,
                    // Bypass-DND has two halves: persist the pref (VM, sees
                    // :core:data) AND rebuild the channel + launch the
                    // policy-access screen (host, sees :app). Fan out to both.
                    onAgentBypassDndChange = { enabled ->
                        viewModel.setAgentBypassDnd(enabled)
                        onAgentBypassDndChange(enabled)
                    },
                    onTestAlert = onTestAlert,
                    pushDistributors = pushDistributors,
                    pushAckDistributor = pushAckDistributor,
                    onPushEnabledChange = onPushEnabledChange,
                    onChoosePushDistributor = onChoosePushDistributor,
                )
            }
        }
    }
}

/**
 * herdr "Notifications" section. The pref-backed switches drive the VM
 * (which writes [dev.kuch.termx.core.data.prefs.AlertPreferences]); the
 * actions that need `:app` singletons (test alert, bypass-DND channel
 * rebuild + policy launch, UnifiedPush picker) are routed through the
 * callbacks the :app NavHost passes into [SettingsScreen].
 */
@Composable
private fun NotificationsSection(
    state: SettingsUiState,
    onAgentFinishedChange: (Boolean) -> Unit,
    onStrongVibrationChange: (Boolean) -> Unit,
    onAgentBypassDndChange: (Boolean) -> Unit,
    onTestAlert: () -> Unit,
    pushDistributors: List<String>,
    pushAckDistributor: String?,
    onPushEnabledChange: (Boolean) -> Unit,
    onChoosePushDistributor: (String) -> Unit,
) {
    val context = LocalContext.current
    var pickerExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Notifications")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Alert you when a remote AI agent finishes its work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SwitchRow(
                title = "Agent-finished alerts",
                subtitle = "Notify on the herdr channel when an agent wraps up.",
                checked = state.agentFinishedEnabled,
                onCheckedChange = onAgentFinishedChange,
            )
            SwitchRow(
                title = "Strong vibration (3s)",
                subtitle = "Three full-strength buzzes so a pocketed phone is felt.",
                checked = state.agentStrongVibration,
                onCheckedChange = onStrongVibrationChange,
            )
            SwitchRow(
                title = "Bypass Do Not Disturb",
                subtitle = "Let agent alerts through while DND is on (needs policy access).",
                checked = state.agentBypassDnd,
                onCheckedChange = onAgentBypassDndChange,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onTestAlert) { Text("Test alert") }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Push when app is closed",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                title = "UnifiedPush",
                subtitle = "Receive agent alerts via a push app even when termx is closed.",
                checked = state.unifiedPushEnabled,
                onCheckedChange = onPushEnabledChange,
            )

            Spacer(Modifier.height(8.dp))
            if (pushDistributors.isEmpty()) {
                Text(
                    text = "No push app installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NTFY_INSTALL_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }) {
                    Text("Install ntfy")
                }
            } else {
                Text(
                    text = "Push app: ${pushAckDistributor ?: "not selected"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedButton(onClick = { pickerExpanded = true }) {
                        Text("Choose push app")
                    }
                    DropdownMenu(
                        expanded = pickerExpanded,
                        onDismissRequest = { pickerExpanded = false },
                    ) {
                        pushDistributors.forEach { pkg ->
                            DropdownMenuItem(
                                text = { Text(pkg) },
                                onClick = {
                                    onChoosePushDistributor(pkg)
                                    pickerExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.unifiedPushEndpoint.isNotBlank()) {
                    "Endpoint synced"
                } else {
                    "Waiting for registration"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val NTFY_INSTALL_URL = "https://f-droid.org/packages/io.heckel.ntfy/"

/**
 * A title + optional subtitle on the left and a trailing [Switch],
 * matching the card body style used by the other settings sections.
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Task #41 — Gemini API key entry. The field is password-masked by
 * default; a trailing eye toggles visibility. "Get a key" launches the
 * AI Studio console in the user's browser.
 */
@Composable
private fun GeminiApiKeySection(
    present: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Gemini API key")
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (present) {
                    "A key is saved. Replace it by pasting a new value and tapping Save."
                } else {
                    "Required for push-to-talk transcription. The key is stored in the app's private, biometric-gated vault."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("AIza…") },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GEMINI_API_KEY_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                }) {
                    Text("Get a key")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (present) {
                        OutlinedButton(onClick = onClear) { Text("Clear") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            onSave(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) { Text("Save") }
                }
            }
        }
    }
}

private const val GEMINI_API_KEY_URL = "https://aistudio.google.com/apikey"

/**
 * Push-to-talk language card. Two stacked dropdowns drive the source
 * (`Speak in`) and target (`Transcribe to`) locales fed into Gemini's
 * prompt; equal codes select the transcribe-only template, different
 * codes select the translate template. The textarea below is an
 * optional free-text "domain hints" appendix appended to every
 * prompt — useful for priming the model with technical jargon
 * (`kubectl, systemctl, k9s`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PttLanguageSection(
    sourceLanguage: String,
    targetLanguage: String,
    context: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Push-to-talk language")
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pick the language you'll speak and the language Gemini should output.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LanguageDropdown(
                label = "Speak in",
                selected = sourceLanguage,
                onSelect = onSourceChange,
            )
            Spacer(Modifier.height(8.dp))
            LanguageDropdown(
                label = "Transcribe to",
                selected = targetLanguage,
                onSelect = onTargetChange,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = pttModeCaption(sourceLanguage, targetLanguage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Optional context",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = context,
                onValueChange = onContextChange,
                placeholder = { Text("e.g. kubectl, systemctl, k9s, my server names") },
                singleLine = false,
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Helps Gemini recognize jargon and proper nouns.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    label: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value = PttLanguage.displayLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            PttLanguage.codes.forEach { code ->
                DropdownMenuItem(
                    text = { Text(PttLanguage.displayLabel(code)) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun pttModeCaption(source: String, target: String): String {
    val sourceLabel = PttLanguage.displayLabel(source)
    val targetLabel = PttLanguage.displayLabel(target)
    val sourceFull = PttLanguage.fullName[source] ?: sourceLabel
    return if (source == target) {
        "Transcribe $sourceFull"
    } else {
        "Translate $sourceLabel → $targetLabel"
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FontSizeSection(
    state: SettingsUiState,
    onChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeader("Terminal font size")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Aa",
                    fontSize = state.fontSizeSp.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "${state.fontSizeSp} sp",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Slider(
                value = state.fontSizeSp.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = MIN_FONT_SIZE_SP.toFloat()..MAX_FONT_SIZE_SP.toFloat(),
                steps = (MAX_FONT_SIZE_SP - MIN_FONT_SIZE_SP) - 1,
            )
        }
    }
}

private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 32
