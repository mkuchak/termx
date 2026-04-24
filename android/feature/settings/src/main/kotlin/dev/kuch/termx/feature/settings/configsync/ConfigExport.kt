package dev.kuch.termx.feature.settings.configsync

import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.Server
import dev.kuch.termx.core.domain.model.ServerGroup
import dev.kuch.termx.core.domain.theme.TerminalTheme
import kotlinx.serialization.Serializable

/**
 * Plain-JSON shape of a termx configuration bundle (Task #47). Private
 * key material is intentionally omitted — we include only the public
 * OpenSSH strings and the keystore aliases so users can re-link them
 * after importing on a new device.
 *
 * Bumping [VERSION] requires a matching reader branch; until then, the
 * import flow rejects anything with a mismatched version.
 */
@Serializable
data class ConfigBundle(
    val version: Int = VERSION,
    val exportedAtEpochMs: Long,
    val servers: List<ExportedServer>,
    val groups: List<ExportedGroup>,
    val keys: List<ExportedKey>,
    val themes: List<ExportedTheme>,
    val preferences: ExportedPreferences,
) {
    companion object {
        const val VERSION: Int = 1
    }
}

@Serializable
data class ExportedServer(
    val id: String,
    val label: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String,
    val keyPairId: String?,
    val groupId: String?,
    val useMosh: Boolean,
    val autoAttachTmux: Boolean,
    val tmuxSessionName: String,
    val sortOrder: Int,
    val companionInstalled: Boolean,
)

@Serializable
data class ExportedGroup(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val isCollapsed: Boolean,
)

/**
 * Key row as exported. [keystoreAlias] stays as a plain reference —
 * the private bytes live in the sandboxed vault and are deliberately
 * NOT round-tripped through JSON (the export is intended to be safe
 * to share across machines). On import, rows with aliases that don't
 * resolve are flagged and the user is prompted to re-import each
 * private key manually.
 */
@Serializable
data class ExportedKey(
    val id: String,
    val label: String,
    val algorithm: String,
    val publicKey: String,
    val keystoreAlias: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class ExportedTheme(
    val id: String,
    val displayName: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: List<Long>,
)

@Serializable
data class ExportedPreferences(
    val paranoidMode: Boolean,
    val autoLockMinutes: Int,
    val fontSizeSp: Int,
    val activeThemeId: String,
    val pttMode: String,
)

fun Server.toExported(): ExportedServer = ExportedServer(
    id = id.toString(),
    label = label,
    host = host,
    port = port,
    username = username,
    authType = authType.name,
    keyPairId = keyPairId?.toString(),
    groupId = groupId?.toString(),
    useMosh = useMosh,
    autoAttachTmux = autoAttachTmux,
    tmuxSessionName = tmuxSessionName,
    sortOrder = sortOrder,
    companionInstalled = companionInstalled,
)

fun ServerGroup.toExported(): ExportedGroup = ExportedGroup(
    id = id.toString(),
    name = name,
    sortOrder = sortOrder,
    isCollapsed = isCollapsed,
)

fun KeyPair.toExported(): ExportedKey = ExportedKey(
    id = id.toString(),
    label = label,
    algorithm = algorithm.name,
    publicKey = publicKey,
    keystoreAlias = keystoreAlias,
    createdAtEpochMs = createdAt.toEpochMilli(),
)

fun TerminalTheme.toExported(): ExportedTheme = ExportedTheme(
    id = id,
    displayName = displayName,
    background = background,
    foreground = foreground,
    cursor = cursor,
    ansi = ansi,
)
