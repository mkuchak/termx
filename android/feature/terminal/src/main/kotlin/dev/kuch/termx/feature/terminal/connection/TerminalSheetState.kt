package dev.kuch.termx.feature.terminal.connection

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder of the terminal sheet's maximized/minimized state
 * (Task #47, the Moshi-style sheet).
 *
 * The terminal is no longer a nav destination: it renders as a draggable
 * sheet OVERLAY mounted as a root sibling of the NavHost (see
 * `TerminalSheetHost`). This singleton is the single source of truth for
 * WHICH session the sheet is showing:
 *
 *  - [maximizedServerId] non-null → the sheet is up, bound to that
 *    server's [ConnectionManager] slot (registry id — may be
 *    [ConnectionManager.FALLBACK_SERVER_ID] for the BuildConfig
 *    test-server session).
 *  - null → no sheet; the home screen's live cards are the only
 *    terminal UI.
 *
 * STRICTLY VIEW-LAYER STATE: maximize/minimize never touches the
 * transport. Connections start/stop exclusively through
 * [ConnectionManager]; minimizing just hides the terminal UI while the
 * session keeps running (the Task #43 minimize semantics). That is why
 * this holder deliberately has NO ConnectionManager dependency — the
 * "connect then maximize" composition lives in the small
 * `TerminalSheetViewModel` (and `MainActivity`'s notification entry),
 * keeping this class trivially testable.
 *
 * @Singleton so `:app` (MainActivity's notification deep-link handler,
 * TermxNavHost's lock/nav interplay) and `:feature:terminal` (the sheet
 * host) observe the same instance.
 */
@Singleton
class TerminalSheetState @Inject constructor() {

    private val _maximizedServerId = MutableStateFlow<UUID?>(null)

    /** Registry server id of the maximized session, or null when minimized. */
    val maximizedServerId: StateFlow<UUID?> = _maximizedServerId.asStateFlow()

    /**
     * Show the sheet for [serverId]. Calling while another session is
     * maximized swaps the sheet to the new session (the host keys its
     * content on the id, so the swap re-runs the slide-in).
     */
    fun maximize(serverId: UUID) {
        _maximizedServerId.value = serverId
    }

    /**
     * Hide the sheet. Idempotent; never touches the transport — the
     * session stays live in [ConnectionManager] and keeps feeding the
     * home screen's live card.
     */
    fun minimize() {
        _maximizedServerId.value = null
    }
}
