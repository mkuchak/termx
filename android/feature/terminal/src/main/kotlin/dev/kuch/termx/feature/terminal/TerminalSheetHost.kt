package dev.kuch.termx.feature.terminal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.feature.terminal.connection.ConnectionManager
import dev.kuch.termx.feature.terminal.connection.TerminalSheetState
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/**
 * Thin Hilt bridge between Compose and the two process-wide singletons
 * the sheet needs: [TerminalSheetState] (which session is maximized) and
 * [ConnectionManager] (the transports).
 *
 * [open] is THE entry point every "open a terminal" affordance now goes
 * through — home-screen saved-row tap, active-session card tap, the diff
 * viewer's "open terminal" — replacing the deleted `terminal/{serverId}`
 * nav route. It is connect-then-maximize: the manager's bind-if-alive
 * `connect()` makes the call an instant rebind for a live session and a
 * fresh dial for a dead one, then the sheet slides up either way.
 */
@HiltViewModel
class TerminalSheetViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val sheetState: TerminalSheetState,
) : ViewModel() {

    /** See [TerminalSheetState.maximizedServerId]. */
    val maximizedServerId: StateFlow<UUID?> = sheetState.maximizedServerId

    /**
     * Connect (bind-if-alive) then maximize. [serverId] is the REGISTRY
     * id — the all-zeros [ConnectionManager.FALLBACK_SERVER_ID] sentinel
     * (a live BuildConfig test-server session tapped on the rail) maps
     * back to the manager's null-id path, mirroring the ReconnectBroker
     * collector in [ConnectionManager].
     */
    fun open(serverId: UUID) {
        connectionManager.connect(
            serverId.takeUnless { it == ConnectionManager.FALLBACK_SERVER_ID },
        )
        sheetState.maximize(serverId)
    }

    /** Hide the sheet; the session keeps running. */
    fun minimize() = sheetState.minimize()
}

/**
 * Root-level terminal sheet overlay (Task #47) — the terminal's only
 * remaining host. Mounted in `:app`'s TermxNavHost as a ROOT SIBLING of
 * the NavHost (inside a shared Box, above all routes, below
 * `PermissionDialogHost`), exactly the placement technique the
 * permission dialog already uses, so the sheet rides over whatever
 * screen is showing without being a destination itself.
 *
 * Renders nothing while no session is maximized. While maximized it
 * fills the screen with a draggable sheet:
 *
 *  - SLIDE-IN: the sheet animates from Hidden (off-screen bottom) to
 *    Expanded (offset 0) on maximize.
 *  - GESTURE ARBITRATION (load-bearing): ONLY the ~32dp top handle
 *    strip carries the [anchoredDraggable] modifier. The terminal body
 *    keeps its existing touch routing untouched — its listener feeds
 *    the scale + double-tap detectors and then deliberately falls
 *    through to Termux's `onTouchEvent` (scrollback/selection); a
 *    whole-sheet drag surface would starve that path.
 *  - DISMISS: the default [anchoredDraggable] fling behavior settles to
 *    the nearer anchor at a 50% positional threshold, so dragging past
 *    half the screen and releasing — or flinging down — settles on
 *    Hidden, which clears [TerminalSheetState] (minimize). Anything
 *    less snaps back to Expanded.
 *  - BACK = MINIMIZE: a [BackHandler] animates the sheet down instead
 *    of popping navigation. Disconnect remains the explicit in-screen
 *    action; back never ends a session.
 *
 * Deliberately NOT a Material `ModalBottomSheet`: modal scrim +
 * tap-outside-dismiss semantics are wrong for a full-screen terminal,
 * and the modal's gesture ownership would fight the terminal body's
 * touch listener.
 *
 * The content is `key`ed on the server id so maximizing a different
 * session while one is up rebuilds the sheet (fresh drag state, fresh
 * slide-in, correctly keyed [TerminalViewModel]).
 */
@Composable
fun TerminalSheetHost(
    modifier: Modifier = Modifier,
    viewModel: TerminalSheetViewModel = hiltViewModel(),
) {
    val maximizedId by viewModel.maximizedServerId.collectAsStateWithLifecycle()
    val serverId = maximizedId ?: return
    key(serverId) {
        TerminalSheet(
            serverId = serverId,
            onMinimize = viewModel::minimize,
            modifier = modifier,
        )
    }
}

/** The sheet's two anchor points. */
private enum class SheetAnchor {
    /** Fully up; the terminal fills the screen below the handle strip. */
    Expanded,

    /** Fully off-screen below; settling here clears the maximized state. */
    Hidden,
}

@Composable
private fun TerminalSheet(
    serverId: UUID,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sheetHeightPx = constraints.maxHeight.toFloat()

        // Anchors are set inside the remember block so the state never
        // exists without them (animateTo/requireOffset below would have
        // nothing to resolve against). Keyed on the height so a window
        // resize (rotation) rebuilds the anchors; the LaunchedEffect
        // below then re-runs and settles the fresh state back to
        // Expanded.
        val dragState = remember(sheetHeightPx) {
            AnchoredDraggableState(initialValue = SheetAnchor.Hidden).apply {
                updateAnchors(
                    DraggableAnchors {
                        SheetAnchor.Expanded at 0f
                        SheetAnchor.Hidden at sheetHeightPx
                    },
                )
            }
        }

        // Slide-in on maximize: the state starts at Hidden (off-screen)
        // and animates up as soon as the sheet enters composition.
        LaunchedEffect(dragState) {
            dragState.animateTo(SheetAnchor.Expanded)
        }

        // Settling on Hidden — drag past the 50% threshold + release, a
        // downward fling, or the BackHandler's animated dismissal —
        // clears the maximized state. dropWhile skips the initial
        // Hidden settle the state is BORN with (and any emission before
        // the slide-in lands) so the sheet can't self-dismiss while
        // appearing.
        LaunchedEffect(dragState) {
            snapshotFlow { dragState.settledValue }
                .dropWhile { it != SheetAnchor.Expanded }
                .collect { settled ->
                    if (settled == SheetAnchor.Hidden) onMinimize()
                }
        }

        // Back = minimize, never disconnect (explicit Disconnect stays
        // the in-screen action). Registered AFTER the NavHost in the
        // root composition order, so while the sheet is up this handler
        // wins over navigation back-handling.
        val scope = rememberCoroutineScope()
        BackHandler {
            scope.launch { dragState.animateTo(SheetAnchor.Hidden) }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(x = 0, y = dragState.requireOffset().roundToInt()) },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // THE drag surface — and only it. anchoredDraggable sits
                // before the status-bar padding so a grab anywhere on the
                // strip (including the inset region while mid-drag)
                // drives the sheet; the visible pill row itself sits
                // below the status bar.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .anchoredDraggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                        )
                        .statusBarsPadding()
                        .height(DRAG_HANDLE_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                    .copy(alpha = 0.5f),
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                }
                // The handle strip above already accounts for the status
                // bar, so consume that inset here — TerminalScreen's own
                // statusBarsPadding (it is also a standalone-capable
                // composable) resolves to zero inside the sheet instead
                // of double-padding. IME + navigation-bar insets flow
                // through untouched; ConnectedPane's
                // windowInsetsPadding(ime ∪ navigationBars) keeps
                // working unchanged inside this overlay.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(WindowInsets.statusBars),
                ) {
                    TerminalScreen(
                        // The all-zeros registry sentinel maps back to the
                        // manager's null-id BuildConfig test-server path.
                        serverId = serverId.takeUnless {
                            it == ConnectionManager.FALLBACK_SERVER_ID
                        },
                        // Key the binder VM per server: the sheet host has
                        // no per-destination NavBackStackEntry to scope
                        // VMs anymore (the terminal route is gone), so
                        // without the key every session would share one
                        // Activity-scoped TerminalViewModel and clobber
                        // each other's bound connection slot.
                        viewModel = hiltViewModel(key = "terminal-$serverId"),
                    )
                }
            }
        }
    }
}

private val DRAG_HANDLE_HEIGHT = 32.dp
