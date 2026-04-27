package dev.kuch.termx.feature.updater

import java.io.File

/**
 * One-of state for the in-app updater. Drives both the
 * server-list banner and the Settings card.
 */
sealed interface UpdaterState {

    /** No check has run yet (cold-start, before the first network call). */
    data object Idle : UpdaterState

    /** A check is in flight. Banner stays hidden — checks should be invisible. */
    data object Checking : UpdaterState

    /** Latest GitHub release equals or precedes the installed version. */
    data class UpToDate(val installedVersion: String) : UpdaterState

    /**
     * A newer release exists. The banner shows version + size and
     * offers Download / Skip. On Wi-Fi, the repository auto-promotes
     * this to [Downloading] without UI input.
     */
    data class Available(
        val version: String,
        val downloadUrl: String,
        val sizeBytes: Long,
    ) : UpdaterState

    /** APK is being fetched — banner shows a progress bar. */
    data class Downloading(
        val version: String,
        val bytesRead: Long,
        val bytesTotal: Long,
    ) : UpdaterState

    /** Download finished; banner shows Install / Cancel. */
    data class ReadyToInstall(
        val version: String,
        val apkFile: File,
    ) : UpdaterState

    /** User skipped this version explicitly. Banner stays hidden. */
    data object Skipped : UpdaterState

    /**
     * Network / parse / install failure. Banner shows the message +
     * Dismiss. Non-fatal — next launch / "Check now" tap retries.
     */
    data class Error(val message: String) : UpdaterState
}
