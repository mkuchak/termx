package dev.kuch.termx.feature.servers.fakes

import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory replacement for [InstallCompanionUseCase] that records every
 * invocation and lets the test script the emitted state sequence per
 * [InstallCompanionUseCase.Stage].
 */
class FakeInstallCompanionUseCase : InstallCompanionUseCase {

    data class Invocation(
        val serverId: UUID,
        val stage: InstallCompanionUseCase.Stage,
        val context: InstallCompanionUseCase.Context,
    )

    val invocations = mutableListOf<Invocation>()
    var scripts: MutableMap<InstallCompanionUseCase.Stage, List<InstallStep3State>> = mutableMapOf(
        InstallCompanionUseCase.Stage.Detect to listOf(
            InstallStep3State.Detecting,
            InstallStep3State.Error("scripted default"),
        ),
    )

    override fun run(
        serverId: UUID,
        stage: InstallCompanionUseCase.Stage,
        context: InstallCompanionUseCase.Context,
    ): Flow<InstallStep3State> = flow {
        invocations += Invocation(serverId, stage, context)
        val script = scripts[stage] ?: listOf(InstallStep3State.Detecting)
        script.forEach { emit(it) }
    }
}
