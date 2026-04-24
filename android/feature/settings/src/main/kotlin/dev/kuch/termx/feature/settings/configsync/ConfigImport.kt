package dev.kuch.termx.feature.settings.configsync

import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.repository.KeyPairRepository
import dev.kuch.termx.core.domain.repository.ServerGroupRepository
import dev.kuch.termx.core.domain.repository.ServerRepository
import java.time.Instant
import java.util.UUID

/**
 * How to resolve a collision between an imported row and an existing row
 * with the same label. See [ConfigImport.apply].
 */
enum class ConflictStrategy {
    /** Leave the existing row alone; drop the imported one. */
    KeepExisting,

    /** Overwrite the existing row's fields with the imported row. */
    Overwrite,

    /** Append a suffix to the imported row's label and insert as new. */
    KeepBothRename,
}

/**
 * Pure-function diff describing what an import bundle would do if
 * applied against the current server/group/key repositories. Feature UI
 * renders this as "N new servers, 2 conflicts" in the import preview.
 */
data class ImportDiff(
    val newServers: List<ExportedServer>,
    val conflictServers: List<ExportedServer>,
    val newGroups: List<ExportedGroup>,
    val conflictGroups: List<ExportedGroup>,
    val newKeys: List<ExportedKey>,
    val conflictKeys: List<ExportedKey>,
    val newThemes: List<ExportedTheme>,
)

class ConfigImport(
    private val serverRepository: ServerRepository,
    private val groupRepository: ServerGroupRepository,
    private val keyRepository: KeyPairRepository,
) {

    /**
     * Compute the diff that [apply] would produce. The comparison is by
     * label on servers/groups/keys (labels are user-visible and usually
     * unique); id collisions alone do not count as conflicts because
     * they're only meaningful within this device's local database.
     */
    suspend fun preview(
        bundle: ConfigBundle,
        existingServers: List<Server>,
        existingGroups: List<ServerGroup>,
        existingKeys: List<KeyPair>,
    ): ImportDiff {
        val serverLabels = existingServers.map { it.label }.toSet()
        val groupNames = existingGroups.map { it.name }.toSet()
        val keyLabels = existingKeys.map { it.label }.toSet()

        val (conflictS, newS) = bundle.servers.partition { it.label in serverLabels }
        val (conflictG, newG) = bundle.groups.partition { it.name in groupNames }
        val (conflictK, newK) = bundle.keys.partition { it.label in keyLabels }

        return ImportDiff(
            newServers = newS,
            conflictServers = conflictS,
            newGroups = newG,
            conflictGroups = conflictG,
            newKeys = newK,
            conflictKeys = conflictK,
            newThemes = bundle.themes,
        )
    }

    /**
     * Apply [bundle] using [strategy] for every label collision. New
     * rows always get a fresh UUID to avoid colliding with unrelated
     * rows that happened to share an id across devices.
     */
    suspend fun apply(
        bundle: ConfigBundle,
        strategy: ConflictStrategy,
        existingServers: List<Server>,
        existingGroups: List<ServerGroup>,
        existingKeys: List<KeyPair>,
    ) {
        val existingServerByLabel = existingServers.associateBy { it.label }
        val existingGroupByName = existingGroups.associateBy { it.name }
        val existingKeyByLabel = existingKeys.associateBy { it.label }

        bundle.groups.forEach { incoming ->
            val existing = existingGroupByName[incoming.name]
            when {
                existing == null -> groupRepository.upsert(incoming.toDomain())
                strategy == ConflictStrategy.KeepExisting -> Unit
                strategy == ConflictStrategy.Overwrite -> {
                    groupRepository.upsert(incoming.toDomain().copy(id = existing.id))
                }
                strategy == ConflictStrategy.KeepBothRename -> {
                    groupRepository.upsert(
                        incoming
                            .copy(name = renameForConflict(incoming.name))
                            .toDomain(),
                    )
                }
            }
        }

        bundle.keys.forEach { incoming ->
            val existing = existingKeyByLabel[incoming.label]
            when {
                existing == null -> keyRepository.insert(incoming.toDomain())
                strategy == ConflictStrategy.KeepExisting -> Unit
                strategy == ConflictStrategy.Overwrite -> {
                    // KeyPair repo has no update, so delete + re-insert
                    // with the existing id preserved.
                    keyRepository.delete(existing.id)
                    keyRepository.insert(incoming.toDomain().copy(id = existing.id))
                }
                strategy == ConflictStrategy.KeepBothRename -> {
                    keyRepository.insert(
                        incoming
                            .copy(label = renameForConflict(incoming.label))
                            .toDomain(),
                    )
                }
            }
        }

        bundle.servers.forEach { incoming ->
            val existing = existingServerByLabel[incoming.label]
            when {
                existing == null -> serverRepository.upsert(incoming.toDomain())
                strategy == ConflictStrategy.KeepExisting -> Unit
                strategy == ConflictStrategy.Overwrite -> {
                    serverRepository.upsert(incoming.toDomain().copy(id = existing.id))
                }
                strategy == ConflictStrategy.KeepBothRename -> {
                    serverRepository.upsert(
                        incoming
                            .copy(label = renameForConflict(incoming.label))
                            .toDomain(),
                    )
                }
            }
        }
    }

    private fun renameForConflict(original: String): String = "$original (imported)"
}

internal fun ExportedServer.toDomain(): Server = Server(
    id = UUID.fromString(id),
    label = label,
    host = host,
    port = port,
    username = username,
    authType = AuthType.valueOf(authType),
    keyPairId = keyPairId?.let(UUID::fromString),
    groupId = groupId?.let(UUID::fromString),
    useMosh = useMosh,
    autoAttachTmux = autoAttachTmux,
    tmuxSessionName = tmuxSessionName,
    lastConnected = null,
    pingMs = null,
    sortOrder = sortOrder,
    companionInstalled = companionInstalled,
)

internal fun ExportedGroup.toDomain(): ServerGroup = ServerGroup(
    id = UUID.fromString(id),
    name = name,
    sortOrder = sortOrder,
    isCollapsed = isCollapsed,
)

internal fun ExportedKey.toDomain(): KeyPair = KeyPair(
    id = UUID.fromString(id),
    label = label,
    algorithm = KeyAlgorithm.valueOf(algorithm),
    publicKey = publicKey,
    keystoreAlias = keystoreAlias,
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
)
