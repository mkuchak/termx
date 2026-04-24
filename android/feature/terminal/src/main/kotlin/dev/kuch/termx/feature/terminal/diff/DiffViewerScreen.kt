package dev.kuch.termx.feature.terminal.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Full-screen diff viewer.
 *
 * Renders a unified-diff-style view for one Claude file edit. Not a full
 * syntax highlighter — just +/- line backgrounds per the Phase 5 MVP
 * scope. A real tokenizer (Kotlin / TS / Python) is deferred; this gives
 * 90% of the perceptual value for 5% of the work.
 *
 * Navigation:
 *  - Back arrow pops the back stack.
 *  - Copy icon on the header copies the file path to clipboard.
 *  - "Open in terminal" footer button [onOpenTerminal] is delegated to
 *    the caller so the NavHost owns the routing decision.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffViewerScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    viewModel: DiffViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val s = state) {
                            is DiffViewerState.Loaded -> s.payload.filePath
                            else -> "Diff"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val loaded = state as? DiffViewerState.Loaded
                    if (loaded != null) {
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(loaded.payload.filePath))
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy file path")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val s = state) {
                is DiffViewerState.Loading -> LoadingPane()
                is DiffViewerState.Error -> ErrorPane(message = s.message, onBack = onBack)
                is DiffViewerState.Loaded -> LoadedPane(
                    payload = s.payload,
                    onOpenTerminal = onOpenTerminal,
                )
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun LoadedPane(
    payload: DiffPayload,
    onOpenTerminal: () -> Unit,
) {
    val lines = remember(payload.unifiedDiff) { parseDiffLines(payload.unifiedDiff) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${payload.tool} · ${payload.session.ifBlank { "unknown session" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenTerminal) { Text("Open in terminal") }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            items(lines) { line ->
                DiffLineRow(line)
            }
        }
    }
}

/**
 * One row of the unified diff. Line numbers are synthetic counters we
 * assign during parsing so the gutter is always populated even when the
 * diff headers are malformed. Colour palette picks don't use the theme's
 * error/primary — darker hues keep the content legible against the
 * terminal background colour.
 */
@Composable
private fun DiffLineRow(line: DiffLine) {
    val bg: Color = when (line.kind) {
        DiffLineKind.ADDED -> ADDED_BG
        DiffLineKind.REMOVED -> REMOVED_BG
        DiffLineKind.CONTEXT -> Color.Transparent
        DiffLineKind.HUNK -> HUNK_BG
    }
    val fg: Color = when (line.kind) {
        DiffLineKind.ADDED -> ADDED_FG
        DiffLineKind.REMOVED -> REMOVED_FG
        DiffLineKind.CONTEXT -> MaterialTheme.colorScheme.onSurface
        DiffLineKind.HUNK -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = line.gutter.padStart(4, ' '),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = line.text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
        )
    }
}

private enum class DiffLineKind { ADDED, REMOVED, CONTEXT, HUNK }

private data class DiffLine(
    val gutter: String,
    val text: String,
    val kind: DiffLineKind,
)

/**
 * Tiny parser for the unified-diff text termxd produces. We only care
 * about the per-line prefix; `---`/`+++` header lines are treated as
 * context so the user still sees them but without +/- colouring.
 */
private fun parseDiffLines(unified: String): List<DiffLine> {
    if (unified.isEmpty()) return emptyList()
    var left = 0
    var right = 0
    val out = mutableListOf<DiffLine>()
    unified.split('\n').forEach { raw ->
        if (raw.isEmpty()) return@forEach
        when {
            raw.startsWith("@@") -> {
                out += DiffLine(gutter = "", text = raw, kind = DiffLineKind.HUNK)
                // @@ -a,b +c,d @@ — try to reset the counters from the header.
                val nums = HUNK_REGEX.find(raw)
                if (nums != null) {
                    left = nums.groupValues[1].toIntOrNull() ?: left
                    right = nums.groupValues[2].toIntOrNull() ?: right
                }
            }
            raw.startsWith("---") || raw.startsWith("+++") -> {
                out += DiffLine(gutter = "", text = raw, kind = DiffLineKind.CONTEXT)
            }
            raw.startsWith("+") -> {
                out += DiffLine(
                    gutter = right.toString(),
                    text = raw.substring(1),
                    kind = DiffLineKind.ADDED,
                )
                right++
            }
            raw.startsWith("-") -> {
                out += DiffLine(
                    gutter = left.toString(),
                    text = raw.substring(1),
                    kind = DiffLineKind.REMOVED,
                )
                left++
            }
            else -> {
                val trimmed = if (raw.startsWith(" ")) raw.substring(1) else raw
                out += DiffLine(
                    gutter = "$left",
                    text = trimmed,
                    kind = DiffLineKind.CONTEXT,
                )
                left++
                right++
            }
        }
    }
    return out
}

private val HUNK_REGEX = Regex("""@@ -(\d+),?\d* \+(\d+),?\d* @@""")

// Muted red/green that still read on a dark background. Picked to clear
// WCAG AA against black; kept low-saturation so a long diff isn't visually
// overwhelming.
private val ADDED_BG = Color(0x3300FF66)
private val ADDED_FG = Color(0xFFB5F7C4)
private val REMOVED_BG = Color(0x33FF5252)
private val REMOVED_FG = Color(0xFFFFB5B5)
private val HUNK_BG = Color(0x22808080)

