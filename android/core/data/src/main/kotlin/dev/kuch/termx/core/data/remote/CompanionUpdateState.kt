package dev.kuch.termx.core.data.remote

import java.util.UUID

/**
 * One-of state for the best-effort on-connect termxd companion check
 * (Task #32 / OPT-2), surfaced by [CompanionUpdateRepository] and rendered
 * by the server-list `CompanionUpdateBanner`.
 *
 * Modeled on `:feature:updater`'s `UpdaterState`, but for the VPS-side
 * companion rather than the APK. Unlike the APK updater there is no silent
 * auto-download path: the highest a check ever climbs on its own is
 * [UpdateAvailable] / [Missing]; the actual install runs only after the user
 * taps "Install" (consent-first — the wizard's promise that "nothing writes to
 * the VPS until you approve").
 *
 * The banner self-hides for [Idle] / [UpToDate] / [Skipped] / [Checking];
 * everything else renders. [serverId] is carried on the actionable states so a
 * later connect to a *different* server doesn't drive an install against the
 * wrong host.
 */
sealed interface CompanionUpdateState {

    /** Nothing to surface (no connect checked yet, or the offer was dismissed). */
    data object Idle : CompanionUpdateState

    /** A probe / release lookup is in flight. Banner stays hidden — checks are invisible. */
    data object Checking : CompanionUpdateState

    /**
     * An older `termx` is installed and a newer `termxd-v*` release exists.
     * [installed] is the raw `termx --version` capture (or the
     * "version unknown" sentinel), [latestTag] the release tag, [downloadUrl]
     * the arch-matched asset that the one-tap install feeds into
     * `InstallCompanionUseCase`'s preview → install pipeline.
     */
    data class UpdateAvailable(
        val serverId: UUID,
        val installed: String,
        val latestTag: String,
        val downloadUrl: String,
    ) : CompanionUpdateState

    /**
     * No `termx` binary on the VPS at all. [arch] is the resolved
     * architecture token (`amd64` / `arm64`); [downloadUrl] is the matching
     * asset for a fresh install.
     */
    data class Missing(
        val serverId: UUID,
        val arch: String,
        val latestTag: String,
        val downloadUrl: String,
    ) : CompanionUpdateState

    /** Installed companion is at or above the latest release. Banner hidden. */
    data object UpToDate : CompanionUpdateState

    /** User dismissed this version's offer ("Later"). Banner hidden until a newer tag. */
    data object Skipped : CompanionUpdateState

    /**
     * The user-initiated install is running. [log] grows line-by-line as the
     * underlying `termx install` stdout streams. (The on-connect *check* never
     * enters this state — only the Install tap does.)
     */
    data class Installing(
        val serverId: UUID,
        val log: List<String>,
    ) : CompanionUpdateState

    /** The install finished cleanly; banner shows a brief confirmation. */
    data object Installed : CompanionUpdateState

    /**
     * A check or install failed. [message] is best-effort context. Non-fatal —
     * the next connect (after the TTL) or a manual retry tries again.
     */
    data class Error(val message: String) : CompanionUpdateState
}
