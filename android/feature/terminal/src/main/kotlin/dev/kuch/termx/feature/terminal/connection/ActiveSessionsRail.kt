package dev.kuch.termx.feature.terminal.connection

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.feature.terminal.thumbnail.TerminalThumbnailRenderer
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Bridges [ConnectionManager.connections] to the home screen's
 * active-session cards. Lives in `:feature:terminal` because the rail
 * needs [ConnectionManager] + [TerminalThumbnailRenderer], and
 * `:feature:servers` must not depend on this module — the screen takes
 * the rail as an injected composable slot instead (wired in
 * `:app`'s TermxNavHost, same pattern as `updateBanner`).
 *
 * [cards] recomputes whenever the slot MAP changes (new server
 * connected for the first time) or any slot's [TermxConnection.state]
 * transitions (connect / disconnect / error), because slots persist
 * after disconnect by design — membership alone says nothing about
 * liveness. The actual mapping is the pure [activeSessionCardModels].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActiveSessionsViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val cards: StateFlow<List<ActiveSessionCardModel>> = connectionManager.connections
        .flatMapLatest { slots ->
            if (slots.isEmpty()) {
                flowOf(emptyList())
            } else {
                // combine() re-fires on every per-slot state emission;
                // the transform re-snapshots ALL slots through the pure
                // mapper, so a single disconnect drops exactly its card.
                combine(slots.values.map { it.state }) {
                    activeSessionCardModels(slots.values)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    /** The card's ✕ affordance — a real teardown, same as the terminal's Disconnect. */
    fun disconnect(serverId: UUID) {
        connectionManager.disconnect(serverId)
    }
}

/**
 * "ACTIVE SESSIONS" — horizontal rail of live-session preview cards on
 * the home screen (Task #46, Moshi-style). Renders NOTHING when no
 * session is live, so the host screen can compose it unconditionally.
 *
 * Each card shows a ~1 Hz live thumbnail of the session's emulator,
 * the server label, and the LIVE transport badge (mosh/ssh as actually
 * connected, not as preferred). Tapping a card invokes [onSessionTap]
 * with the connection's registry server id — the `:app` host wires this
 * to `TerminalSheetViewModel.open` (Task #47), where ConnectionManager's
 * bind-if-alive `connect()` rebinds the live emulator instantly and the
 * terminal sheet slides up over the home screen. The ✕ tears the
 * session down via [ActiveSessionsViewModel.disconnect].
 */
@Composable
fun ActiveSessionsRail(
    onSessionTap: (UUID) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActiveSessionsViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    if (cards.isEmpty()) return

    val context = LocalContext.current
    // One renderer for every card: TerminalRenderer's glyph metrics are
    // session-independent, and the typeface bundle behind it is a
    // process-wide singleton anyway.
    val renderer = remember { TerminalThumbnailRenderer(context) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ACTIVE SESSIONS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cards, key = { it.serverId.toString() }) { card ->
                ActiveSessionCardItem(
                    card = card,
                    renderer = renderer,
                    onTap = { onSessionTap(card.serverId) },
                    onDisconnect = { viewModel.disconnect(card.serverId) },
                )
            }
        }
    }
}

@Composable
private fun ActiveSessionCardItem(
    card: ActiveSessionCardModel,
    renderer: TerminalThumbnailRenderer,
    onTap: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { CARD_WIDTH.roundToPx() }
    val heightPx = with(density) { THUMBNAIL_HEIGHT.roundToPx() }

    var thumbnail by remember(card.serverId) { mutableStateOf<ImageBitmap?>(null) }

    // Throttled live preview, collected ONLY while the host screen is
    // RESUMED — navigating into the terminal (or backgrounding the app)
    // stops the 1 Hz render loop instead of burning main-thread time on
    // an invisible card. repeatOnLifecycle restarts the flow on return,
    // so the first frame re-renders within one tick.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, card.session, widthPx, heightPx) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            renderer.thumbnails(
                emulatorProvider = { card.session.emulator },
                widthPx = widthPx,
                heightPx = heightPx,
            ).collect { bitmap ->
                thumbnail = bitmap.asImageBitmap()
            }
        }
    }

    Card(
        onClick = onTap,
        modifier = Modifier.width(CARD_WIDTH),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(THUMBNAIL_HEIGHT),
        ) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "Live preview of ${card.label}",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // First tick hasn't landed (or the emulator isn't
                // initialized yet) — hold the slot with a terminal-dark
                // placeholder so the card doesn't jump when the bitmap
                // arrives.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                )
            }
            // ✕ — explicit disconnect, the session-ending affordance
            // (sessions survive leaving the terminal screen, so the rail
            // must offer the kill switch the back button no longer is).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.75f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp),
            ) {
                IconButton(
                    onClick = onDisconnect,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Disconnect ${card.label}",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            LiveTransportBadge(moshBacked = card.moshBacked)
        }
    }
}

/**
 * Transport badge for ACTIVE cards: reflects what the connection is
 * actually running over ([TransportState.Connected.moshBacked]), unlike
 * the saved-connection cards' badge which shows the `Server.useMosh`
 * preference. Visual language mirrors `ServerCard.TransportBadge`
 * (feature:servers) — same tokens, same shape — so the two read as one
 * family; duplicated rather than shared because the modules must not
 * depend on each other.
 */
@Composable
private fun LiveTransportBadge(moshBacked: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = if (moshBacked) "mosh" else "ssh",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private val CARD_WIDTH = 180.dp
private val THUMBNAIL_HEIGHT = 110.dp
