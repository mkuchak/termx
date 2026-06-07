package dev.kuch.termx.core.data.remote.fakes

import dev.kuch.termx.core.domain.usecase.InstallCompanionUseCase
import dev.kuch.termx.core.domain.usecase.InstallStep3State
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * In-memory replacement for [InstallCompanionUseCase], used by
 * `CompanionUpdateRepositoryTest`. Records every invocation and lets the test
 * script the state sequence emitted per [InstallCompanionUseCase.Stage] so the
 * repo's preview → install relay can be driven deterministically without sshj.
 *
 * Mirrors the equivalent fake in `:feature:servers`' test sources.
 */
class FakeInstallCompanionUseCase : InstallCompanionUseCase {

    data class Invocation(
        val serverId: UUID,
        val stage: InstallCompanionUseCase.Stage,
        val context: InstallCompanionUseCase.Context,
    )

    val invocations = mutableListOf<Invocation>()
    val scripts: MutableMap<InstallCompanionUseCase.Stage, List<InstallStep3State>> = mutableMapOf()

    override fun run(
        serverId: UUID,
        stage: InstallCompanionUseCase.Stage,
        context: InstallCompanionUseCase.Context,
    ): Flow<InstallStep3State> = flow {
        invocations += Invocation(serverId, stage, context)
        (scripts[stage] ?: emptyList()).forEach { emit(it) }
    }
}
