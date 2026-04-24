package dev.kuch.termx.core.domain.usecase

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Orchestrates the termxd companion install flow for a given server.
 *
 * The use-case is invoked up to three times during a wizard step:
 *
 *  1. On step enter — [Stage.Detect] runs `which termx` and, if missing,
 *     fetches the latest `termxd-v*` GitHub release so the UI can advance to
 *     [InstallStep3State.ReadyToDownload].
 *  2. On the user's Download+Preview tap — [Stage.Preview] curls the binary
 *     and runs `termx install --dry-run` to produce the
 *     [InstallStep3State.PreviewingDiff] preview.
 *  3. On the user's Install tap — [Stage.Install] runs `termx install` and
 *     streams each stdout line back as an [InstallStep3State.Installing] log
 *     update, finally emitting [InstallStep3State.Success] or
 *     [InstallStep3State.Error].
 *
 * The stage split keeps SSH state (the downloaded URL, the parsed actions) in
 * Kotlin view-model memory rather than shoving it through the Flow — each
 * stage is a one-shot Flow that either succeeds (emitting one or more states,
 * last one terminal for the stage) or fails (Error).
 */
interface InstallCompanionUseCase {

    enum class Stage { Detect, Preview, Install }

    /**
     * Run [stage] against [serverId]. [context] carries stage-specific inputs:
     *
     *  - [Stage.Detect] ignores [context].
     *  - [Stage.Preview] expects [context.downloadUrl] set.
     *  - [Stage.Install] ignores [context] (the remote `/tmp/termx` binary
     *    placed by Preview is reused).
     */
    fun run(
        serverId: UUID,
        stage: Stage,
        context: Context = Context(),
    ): Flow<InstallStep3State>

    data class Context(
        val downloadUrl: String? = null,
    )
}
