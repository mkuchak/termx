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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import dev.kuch.termx.core.domain.theme.TerminalTheme

/**
 * App Settings — font size slider, Gemini API key entry, and theme picker.
 *
 * Task #17 owns the font size section; Task #18 owns the theme section;
 * Task #41 owns the Gemini API key section. All settings persist via
 * [SettingsViewModel]. The Gemini API key goes through the Keystore-backed
 * vault instead of DataStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
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
            item { SectionHeader("Theme") }
            items(state.themes, key = { it.id }) { theme ->
                ThemeCard(
                    theme = theme,
                    selected = theme.id == state.activeThemeId,
                    onSelect = { viewModel.setTheme(theme.id) },
                )
            }
        }
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
                    "Required for push-to-talk transcription. The key is stored in the device Keystore-backed vault."
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

@Composable
private fun ThemeCard(
    theme: TerminalTheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = theme.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                )
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ThemeSwatch(theme)
            Spacer(Modifier.height(8.dp))
            ThemeSamplePreview(theme)
        }
    }
}

@Composable
private fun ThemeSwatch(theme: TerminalTheme) {
    // 16-cell palette strip: two rows of 8.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            theme.ansi.subList(0, 8).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(color.toInt())),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            theme.ansi.subList(8, 16).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color(color.toInt())),
                )
            }
        }
    }
}

@Composable
private fun ThemeSamplePreview(theme: TerminalTheme) {
    Surface(
        color = Color(theme.background.toInt()),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "$ echo \"hello, termx\"",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = Color(theme.foreground.toInt()),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private const val MIN_FONT_SIZE_SP = 8
private const val MAX_FONT_SIZE_SP = 32
