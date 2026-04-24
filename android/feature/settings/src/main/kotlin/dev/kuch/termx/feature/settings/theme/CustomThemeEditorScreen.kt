package dev.kuch.termx.feature.settings.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kuch.termx.core.domain.theme.BuiltInThemes
import dev.kuch.termx.core.domain.theme.TerminalTheme

/**
 * Task #48 — hand-rolled ANSI palette editor.
 *
 * Material3 ships no ColorPicker, so the per-swatch dialog uses three
 * HSV sliders (hue 0..360, saturation 0..1, value 0..1) with a live
 * preview tile. That keeps the dependency graph flat and sidesteps the
 * various third-party color-picker libs with ambiguous maintenance.
 *
 * Saved palettes flow through [onSave] as a [TerminalTheme] whose id
 * is prefixed with `custom:` by the caller (see
 * [dev.kuch.termx.feature.settings.SettingsViewModel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomThemeEditorScreen(
    onBack: () -> Unit,
    onSave: (TerminalTheme) -> Unit,
    seed: TerminalTheme = BuiltInThemes.dracula,
) {
    var displayName by remember { mutableStateOf("Custom theme") }
    var background by remember { mutableStateOf(seed.background) }
    var foreground by remember { mutableStateOf(seed.foreground) }
    var cursor by remember { mutableStateOf(seed.cursor) }
    val ansi = remember { seed.ansi.toMutableStateList() }

    var editing by remember { mutableStateOf<Slot?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                SwatchRow("Background", background) { editing = Slot.Background }
                SwatchRow("Foreground", foreground) { editing = Slot.Foreground }
                SwatchRow("Cursor", cursor) { editing = Slot.Cursor }
            }
            item { SectionHeader("ANSI palette (0-15)") }
            items(16) { index ->
                SwatchRow("ansi[$index]", ansi[index]) { editing = Slot.Ansi(index) }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        onSave(
                            TerminalTheme(
                                id = "custom:${System.currentTimeMillis()}",
                                displayName = displayName.ifBlank { "Custom theme" },
                                background = background,
                                foreground = foreground,
                                cursor = cursor,
                                ansi = ansi.toList(),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save theme")
                }
            }
        }

        editing?.let { slot ->
            val current = when (slot) {
                Slot.Background -> background
                Slot.Foreground -> foreground
                Slot.Cursor -> cursor
                is Slot.Ansi -> ansi[slot.index]
            }
            ColorPickerDialog(
                title = when (slot) {
                    Slot.Background -> "Background"
                    Slot.Foreground -> "Foreground"
                    Slot.Cursor -> "Cursor"
                    is Slot.Ansi -> "ansi[${slot.index}]"
                },
                initial = current,
                onDismiss = { editing = null },
                onConfirm = { picked ->
                    when (slot) {
                        Slot.Background -> background = picked
                        Slot.Foreground -> foreground = picked
                        Slot.Cursor -> cursor = picked
                        is Slot.Ansi -> ansi[slot.index] = picked
                    }
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun SwatchRow(label: String, argb: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(argb.toInt()))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format("#%08X", argb.toInt()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private fun ColorPickerDialog(
    title: String,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initHsv = argbToHsv(initial)
    var hue by remember { mutableStateOf(initHsv[0]) }
    var sat by remember { mutableStateOf(initHsv[1]) }
    var value by remember { mutableStateOf(initHsv[2]) }
    val color = remember(hue, sat, value) { hsvToArgb(hue, sat, value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(color.toInt())),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = String.format("#%08X", color.toInt()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                SliderRow("Hue", hue, 0f, 360f) { hue = it }
                SliderRow("Saturation", sat, 0f, 1f) { sat = it }
                SliderRow("Value", value, 0f, 1f) { value = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = String.format("%.2f", value),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

private sealed interface Slot {
    data object Background : Slot
    data object Foreground : Slot
    data object Cursor : Slot
    data class Ansi(val index: Int) : Slot
}

/**
 * Minimal ARGB -> HSV. Alpha is dropped (and reinstated as 0xFF on the
 * way back) because this editor only surfaces opaque terminal colors.
 */
private fun argbToHsv(argb: Long): FloatArray {
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = (max - min).toFloat()
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b).toFloat() / delta) % 6f)
        max == g -> 60f * ((b - r).toFloat() / delta + 2f)
        else -> 60f * ((r - g).toFloat() / delta + 4f)
    }
    val hue = if (h < 0f) h + 360f else h
    val sat = if (max == 0) 0f else delta / max
    val value = max / 255f
    return floatArrayOf(hue, sat, value)
}

private fun hsvToArgb(hue: Float, sat: Float, value: Float): Long {
    val color = Color.hsv(hue, sat, value).toArgb().toLong() and 0xFFFFFFFFL
    // Compose's Color.hsv returns FF alpha; keep that explicit so we
    // always produce a 0xFFrrggbb value the terminal expects.
    return (0xFF000000L or (color and 0x00FFFFFFL))
}

/**
 * Helper — the Compose `mutableStateListOf` requires vararg which a
 * runtime list can't supply directly; wrap via a tiny extension.
 */
private fun <T> List<T>.toMutableStateList() =
    androidx.compose.runtime.mutableStateListOf<T>().also { it.addAll(this) }
