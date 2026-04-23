package dev.kuch.termx.feature.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Sticky header for a [ServerGroup] section in the server list.
 *
 * The header is clickable when [onToggle] is non-null: tapping flips the
 * collapsed state. The "Ungrouped" header passes `null` and stays static.
 *
 * Renders as a full-width surface so `stickyHeader { ... }` looks correct
 * against the scrolling rows underneath it.
 */
@Composable
fun ServerGroupHeader(
    name: String,
    count: Int,
    isCollapsed: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val clickable = onToggle != null
    val row = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 6.dp)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .let { if (clickable) it.clickable { onToggle() } else it },
    ) {
        Row(
            modifier = row,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (clickable) {
                Icon(
                    imageVector = if (isCollapsed) {
                        Icons.Filled.KeyboardArrowRight
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (isCollapsed) "Expand" else "Collapse",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
            } else {
                // Keep the same content inset so ungrouped and grouped rows
                // line up vertically in the list.
                Spacer(Modifier.width(28.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
