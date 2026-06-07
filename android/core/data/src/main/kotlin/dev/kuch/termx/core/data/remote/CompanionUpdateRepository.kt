package dev.kuch.termx.core.data.remote

import android.util.Log
import dev.kuch.termx.core.common.version.VersionTag
import dev.kuch.termx.core.data.network.TermxReleaseFetcher
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import dev.kuch.termx.libs.sshnative.SshSession
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Best-effort, NON-INTRUSIVE on-connect check for the VPS-side `termx`
 * companion (termxd) — the OPT-2 piece (Task #32).
 *
 * The setup wizard is otherwise the ONLY place that ever compares the
 * installed companion against the latest `termxd-v*` release, so an
 * already-configured server never learns about a companion update (e.g. the
 * watch-herdr daemon). This repository owns a single [StateFlow] of
 * [CompanionUpdateState] that the server-list banner observes; the
 * [TerminalViewModel] feeds it a probe result on each connect.
 *
 * Consent-first by construction: the on-connect path climbs at most to
 * [CompanionUpdateState.UpdateAvailable] / [CompanionUpdateState.Missing]
 * (the banner's offer). Nothing writes to the VPS until the user taps
 * Install, which runs the EXISTING [InstallCompanionUseCase] preview →
 * install-over-SSH pipeline — this class invents no new install transport.
 *
 * Anti-nag: [maybeOfferUpdate] honors a per-server 24h TTL (so reconnects in
 * the window don't re-probe, which also protects GitHub's 60 req/h
 * unauthenticated limit since [TermxReleaseFetcher] is uncached) and a
 * per-(server, tag) skip memory.
 *
 * Single source of truth, `@Singleton`: there is one live offer at a time
 * (the most recent connect wins), mirroring how `UpdaterRepository` owns the
 * one APK-update offer.
 */
@Singleton
class CompanionUpdateRepository @Inject constructor(
    private val releaseFetcher: TermxReleaseFetcher,
    private val appPreferences: AppPreferences,
    private val installUseCase: InstallCompanionUseCase,
) {

    private val _state = MutableStateFlow<CompanionUpdateState>(CompanionUpdateState.Idle)
    val state: StateFlow<CompanionUpdateState> = _state.asStateFlow()

    /**
     * On-connect probe + decision, run over an already-open [session]. This is
     * the heart of the feature and the unit-under-test:
     *
     *  1. Within-TTL short-circuit — if this server was checked < 24h ago,
     *     return without probing (no SSH exec, no GitHub call).
     *  2. Probe `termx` via PATH and the canonical `$HOME/.local/bin/termx`
     *     fallback (identical to [InstallCompanionUseCaseImpl]'s detect probe).
     *  3. Resolve the arch + latest release, compare with [VersionTag].
     *  4. Push the outcome onto [state] — but respect the per-(server, tag)
     *     skip set, collapsing a previously-dismissed offer to
     *     [CompanionUpdateState.Skipped] instead of re-nagging.
     *
     * The TTL is stamped only on a probe that actually reached the network, so
     * a transient failure (or a torn session) doesn't suppress the next
     * connect's retry. Strictly best-effort: any unexpected throw collapses to
     * [CompanionUpdateState.Error]; the caller additionally treats a throw as a
     * no-op so a sick VPS can never break the terminal coming up.
     */
    suspend fun maybeOfferUpdate(serverId: UUID, session: SshSession) {
        val lastCheck = appPreferences.companionUpdateLastCheck(serverId).first()
        val age = System.currentTimeMillis() - lastCheck
        if (age in 0 until CHECK_TTL_MS) {
            Log.i(LOG_TAG, "skipping companion check for $serverId; last check ${age}ms ago")
            return
        }

        _state.value = CompanionUpdateState.Checking
        try {
            decide(serverId, session)
        } catch (t: Throwable) {
            // Should be unreachable — `decide` swallows its own probe/network
            // failures — but keep the StateFlow consistent if anything leaks.
            Log.w(LOG_TAG, "companion check failed for $serverId", t)
            _state.value = CompanionUpdateState.Error(t.message ?: "companion check failed")
        }
    }

    private suspend fun decide(serverId: UUID, session: SshSession) {
        // Resolve the installed binary's path via PATH *and* the canonical
        // self-install location — `which`/`command -v` return non-zero for a
        // non-login sshj shell, so the explicit `$HOME/.local/bin/termx` check
        // is what makes detection reliable. Same probe as the wizard
        // (InstallCompanionUseCaseImpl detect stage).
        val pathLine = execCapture(
            session,
            "command -v termx 2>/dev/null || " +
                "([ -x \"\$HOME/.local/bin/termx\" ] && printf '%s' \"\$HOME/.local/bin/termx\") || true",
        ).trim()

        // Resolve arch + latest release once; needed by both the "missing"
        // (fresh install) and "installed" (compare) branches. A failure here
        // means we have nothing sane to offer — leave the prior state, stamp
        // the TTL so we don't hammer GitHub, and bail.
        val archRaw = execCapture(session, "uname -m").trim()
        val arch = normalizeArch(archRaw)
        if (arch == null) {
            Log.i(LOG_TAG, "unsupported arch '$archRaw' on $serverId; skipping companion offer")
            stampChecked(serverId)
            _state.value = CompanionUpdateState.Idle
            return
        }

        val release = try {
            releaseFetcher.fetchLatest()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "companion release fetch failed for $serverId", t)
            stampChecked(serverId)
            _state.value = CompanionUpdateState.Idle
            return
        }
        val downloadUrl = release.assetForArch(arch)
        if (downloadUrl.isNullOrBlank()) {
            Log.i(LOG_TAG, "no $arch asset in ${release.tag} for $serverId")
            stampChecked(serverId)
            _state.value = CompanionUpdateState.Idle
            return
        }

        // Network round-trip done — record it so reconnects inside the window
        // skip the probe entirely.
        stampChecked(serverId)

        if (pathLine.isBlank()) {
            emitRespectingSkip(
                serverId = serverId,
                tag = release.tag,
                offer = CompanionUpdateState.Missing(
                    serverId = serverId,
                    arch = arch,
                    latestTag = release.tag,
                    downloadUrl = downloadUrl,
                ),
            )
            return
        }

        // RAW `termx --version` output passed straight to the comparator — a
        // blank capture becomes the "version unknown" sentinel that VersionTag
        // treats as the zero version, so an unparseable/missing version offers
        // a reinstall rather than reading as up to date.
        val quoted = shellQuote(pathLine)
        val rawVersion = execCapture(session, "$quoted --version 2>/dev/null || true").trim()
        val installed = rawVersion.ifBlank { VERSION_UNKNOWN }

        if (VersionTag.isCompanionUpdateAvailable(installed, release.tag)) {
            emitRespectingSkip(
                serverId = serverId,
                tag = release.tag,
                offer = CompanionUpdateState.UpdateAvailable(
                    serverId = serverId,
                    installed = installed,
                    latestTag = release.tag,
                    downloadUrl = downloadUrl,
                ),
            )
        } else {
            _state.value = CompanionUpdateState.UpToDate
        }
    }

    /**
     * Surface [offer], unless the user already dismissed this exact
     * (server, tag) pair — in which case stay quiet via [CompanionUpdateState.Skipped].
     */
    private suspend fun emitRespectingSkip(
        serverId: UUID,
        tag: String,
        offer: CompanionUpdateState,
    ) {
        if (appPreferences.isCompanionUpdateSkipped(serverId, tag).first()) {
            Log.i(LOG_TAG, "companion $tag previously skipped for $serverId")
            _state.value = CompanionUpdateState.Skipped
            return
        }
        _state.value = offer
    }

    /**
     * User tapped "Install" on the banner. Reuses the wizard's
     * [InstallCompanionUseCase] — Preview (curl + dry-run) then Install
     * (the real over-SSH install) — translating its [InstallStep3State]
     * stream into this repo's [CompanionUpdateState]. Invents no new install
     * path; the consent gate is this explicit call.
     */
    suspend fun install(serverId: UUID, downloadUrl: String) {
        _state.value = CompanionUpdateState.Installing(serverId, emptyList())

        // Preview stage: download + `termx install --dry-run`. We don't render
        // the diff in this non-wizard flow (the user already consented to the
        // update on the banner), but running it primes /tmp/termx that the
        // Install stage reuses, and surfaces a download/incompatibility failure
        // before we touch the real install.
        val previewError = runStage(
            serverId = serverId,
            stage = InstallCompanionUseCase.Stage.Preview,
            context = InstallCompanionUseCase.Context(downloadUrl = downloadUrl),
        )
        if (previewError != null) {
            _state.value = CompanionUpdateState.Error(previewError)
            return
        }

        val installError = runStage(
            serverId = serverId,
            stage = InstallCompanionUseCase.Stage.Install,
            context = InstallCompanionUseCase.Context(),
        )
        _state.value = if (installError != null) {
            CompanionUpdateState.Error(installError)
        } else {
            CompanionUpdateState.Installed
        }
    }

    /**
     * Collect one [InstallCompanionUseCase] stage to completion, streaming
     * `Installing` log lines into [state]. Returns the error message if the
     * stage ended in [InstallStep3State.Error], else null.
     */
    private suspend fun runStage(
        serverId: UUID,
        stage: InstallCompanionUseCase.Stage,
        context: InstallCompanionUseCase.Context,
    ): String? {
        var error: String? = null
        installUseCase.run(serverId, stage, context).collect { step ->
            when (step) {
                is InstallStep3State.Installing ->
                    _state.value = CompanionUpdateState.Installing(serverId, step.log)
                is InstallStep3State.Downloading ->
                    _state.value = CompanionUpdateState.Installing(serverId, listOf(step.status))
                is InstallStep3State.Error -> error = step.message
                else -> Unit
            }
        }
        return error
    }

    /** User tapped "Later" — remember the dismissal and hide the banner. */
    suspend fun skip(serverId: UUID, tag: String) {
        appPreferences.addCompanionUpdateSkipped(serverId, tag)
        _state.value = CompanionUpdateState.Skipped
    }

    /** Dismiss a terminal banner (Installed / Error) back to the hidden Idle state. */
    fun dismiss() {
        _state.value = CompanionUpdateState.Idle
    }

    private suspend fun stampChecked(serverId: UUID) {
        appPreferences.setCompanionUpdateLastCheck(serverId, System.currentTimeMillis())
    }

    /**
     * Capture a command's stdout, draining stderr concurrently. Mirrors
     * [InstallCompanionUseCaseImpl.execCapture]: sshj gives each stream its own
     * flow-control window, so reading only stdout can deadlock a command that
     * writes enough to stderr. Failures are swallowed to a blank capture —
     * this whole flow is best-effort.
     */
    private suspend fun execCapture(session: SshSession, command: String): String {
        val exec = session.openExec(command)
        return try {
            val buf = StringBuilder()
            coroutineScope {
                val stdoutJob = launch {
                    exec.stdout.collect { chunk -> buf.append(String(chunk, Charsets.UTF_8)) }
                }
                val stderrJob = launch { exec.stderr.collect { /* drain, discard */ } }
                stdoutJob.join()
                stderrJob.join()
            }
            exec.exitCode.await()
            buf.toString()
        } finally {
            runCatching { exec.close() }
        }
    }

    private fun normalizeArch(raw: String): String? = when (raw) {
        "x86_64", "amd64" -> "amd64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val LOG_TAG = "CompanionUpdateRepo"

        /** 24h per-server check window; matches the APK updater's cadence. */
        const val CHECK_TTL_MS: Long = 24L * 60L * 60L * 1000L

        /** Sentinel passed to the comparator when `termx --version` is blank. */
        const val VERSION_UNKNOWN = "termx (version unknown)"
    }
}
