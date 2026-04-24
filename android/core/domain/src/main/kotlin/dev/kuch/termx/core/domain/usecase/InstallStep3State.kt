package dev.kuch.termx.core.domain.usecase

import dev.kuch.termx.core.domain.model.PlannedAction

/**
 * State machine for the Setup Wizard's companion-install step.
 *
 * Emitted by [InstallCompanionUseCase.run]. The flow is a funnel: each state
 * either progresses automatically (Detecting to FetchingRelease to
 * ReadyToDownload) or waits for a user tap that re-enters the use-case with a
 * subsequent call (Download button re-runs to transition ReadyToDownload to
 * Downloading to PreviewingDiff; Install button re-runs to transition from
 * PreviewingDiff to Installing to Success or Error).
 *
 * Terminal states (Success, Error, AlreadyInstalled) stay put until the user
 * retries or advances.
 */
sealed class InstallStep3State {

    /** Initial probe: `which termx` + `uname -m`. */
    data object Detecting : InstallStep3State()

    /** termx already on PATH; version string is whatever `termx --version` prints. */
    data class AlreadyInstalled(val version: String) : InstallStep3State()

    /** Hitting `api.github.com/.../releases` to pick the right asset. */
    data object FetchingRelease : InstallStep3State()

    /** Release picked; waiting for user confirmation before curling the asset. */
    data class ReadyToDownload(
        val arch: String,
        val downloadUrl: String,
        val releaseTag: String,
    ) : InstallStep3State()

    /** curl in progress — status line is the human-readable step caption. */
    data class Downloading(val status: String) : InstallStep3State()

    /** Dry-run parsed; user sees grouped diff and confirms. */
    data class PreviewingDiff(val actions: List<PlannedAction>) : InstallStep3State()

    /** Real install running; [log] grows line-by-line as stdout streams. */
    data class Installing(val log: List<String>) : InstallStep3State()

    /** Companion live; Server.companionInstalled flipped to true. */
    data object Success : InstallStep3State()

    /** Anything non-recoverable. [log] is best-effort context to aid the retry. */
    data class Error(val message: String, val log: List<String> = emptyList()) : InstallStep3State()
}
