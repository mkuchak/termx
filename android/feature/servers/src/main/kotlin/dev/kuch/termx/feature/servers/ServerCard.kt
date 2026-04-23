package dev.kuch.termx.feature.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.Server
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One row in the server list.
 *
 * Visual order left → right:
 *  - Ping dot (if [Server.pingMs] is set). Green <50ms, yellow <200ms, red ≥200.
 *  - Label (big) + host:port (small) stacked vertically.
 *  - Transport badge ("ssh" / "mosh").
 *  - Last-connected relative time.
 *  - Either the reorder arrows (in reorder mode) OR the overflow dots.
 *
 * Tapping the card invokes [onTap]; the caller navigates to the terminal.
 * The overflow icon opens a context menu; reorder arrows replace the
 * overflow icon while reorder mode is active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerCard(
    server: Server,
    reorderMode: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    now: Instant = Instant.now(),
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onTap,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PingDot(pingMs = server.pingMs)
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${server.username}@${server.host}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TransportBadge(useMosh = server.useMosh)
                    Text(
                        text = formatRelative(server.lastConnected, now),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (reorderMode) {
                Column {
                    IconButton(
                        onClick = onMoveUp,
                        enabled = canMoveUp,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(
                        onClick = onMoveDown,
                        enabled = canMoveDown,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "More",
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to group…") },
                            onClick = {
                                menuOpen = false
                                onMoveToGroup()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Delete",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PingDot(pingMs: Int?) {
    val color = when {
        pingMs == null -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        pingMs < 50 -> Color(0xFF3DD68C)
        pingMs < 200 -> Color(0xFFE4B740)
        else -> Color(0xFFE5484D)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun TransportBadge(useMosh: Boolean) {
    val text = if (useMosh) "mosh" else "ssh"
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Render a past [Instant] as "3 min ago" / "never". [now] is injected so
 * previews and unit tests can lock the clock.
 */
internal fun formatRelative(at: Instant?, now: Instant = Instant.now()): String {
    if (at == null) return "never connected"
    val d = Duration.between(at, now)
    val secs = d.seconds
    return when {
        secs < 0 -> "in the future"
        secs < 60 -> "just now"
        secs < 3600 -> "${secs / 60} min ago"
        secs < 86_400 -> "${secs / 3600} h ago"
        secs < 30 * 86_400L -> "${secs / 86_400} d ago"
        else -> "long ago"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun ServerCardPreview() {
    ServerCard(
        server = Server(
            id = UUID.randomUUID(),
            label = "prod-vps",
            host = "vps.example.com",
            port = 22,
            username = "ubuntu",
            authType = AuthType.KEY,
            keyPairId = null,
            groupId = null,
            useMosh = true,
            autoAttachTmux = true,
            tmuxSessionName = "main",
            lastConnected = Instant.now().minusSeconds(180),
            pingMs = 42,
            sortOrder = 0,
        ),
        reorderMode = false,
        canMoveUp = true,
        canMoveDown = true,
        onTap = {},
        onEdit = {},
        onDuplicate = {},
        onDelete = {},
        onMoveToGroup = {},
        onMoveUp = {},
        onMoveDown = {},
    )
}
