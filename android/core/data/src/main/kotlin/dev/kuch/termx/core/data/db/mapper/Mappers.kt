package dev.kuch.termx.core.data.db.mapper

import dev.kuch.termx.core.data.db.entity.KeyPairEntity
import dev.kuch.termx.core.data.db.entity.ServerEntity
import dev.kuch.termx.core.data.db.entity.ServerGroupEntity
import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyAlgorithm
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup

/**
 * Pure mapping functions between the Room entity layer and the `:core:domain`
 * models. Enum columns live in the entities as `String` (so the SQLite dump
 * stays human-readable) and are translated here via `name()` / `valueOf`.
 */

fun ServerEntity.toDomain(): Server = Server(
    id = id,
    label = label,
    host = host,
    port = port,
    username = username,
    authType = AuthType.valueOf(authType),
    keyPairId = keyPairId,
    groupId = groupId,
    useMosh = useMosh,
    autoAttachTmux = autoAttachTmux,
    tmuxSessionName = tmuxSessionName,
    lastConnected = lastConnected,
    pingMs = pingMs,
    sortOrder = sortOrder,
    companionInstalled = companionInstalled,
)

fun Server.toEntity(): ServerEntity = ServerEntity(
    id = id,
    label = label,
    host = host,
    port = port,
    username = username,
    authType = authType.name,
    keyPairId = keyPairId,
    groupId = groupId,
    useMosh = useMosh,
    autoAttachTmux = autoAttachTmux,
    tmuxSessionName = tmuxSessionName,
    lastConnected = lastConnected,
    pingMs = pingMs,
    sortOrder = sortOrder,
    companionInstalled = companionInstalled,
)

fun KeyPairEntity.toDomain(): KeyPair = KeyPair(
    id = id,
    label = label,
    algorithm = KeyAlgorithm.valueOf(algorithm),
    publicKey = publicKey,
    keystoreAlias = keystoreAlias,
    createdAt = createdAt,
)

fun KeyPair.toEntity(): KeyPairEntity = KeyPairEntity(
    id = id,
    label = label,
    algorithm = algorithm.name,
    publicKey = publicKey,
    keystoreAlias = keystoreAlias,
    createdAt = createdAt,
)

fun ServerGroupEntity.toDomain(): ServerGroup = ServerGroup(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isCollapsed = isCollapsed,
)

fun ServerGroup.toEntity(): ServerGroupEntity = ServerGroupEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isCollapsed = isCollapsed,
)
