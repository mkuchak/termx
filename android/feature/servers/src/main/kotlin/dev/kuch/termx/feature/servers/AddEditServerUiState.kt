package dev.kuch.termx.feature.servers

import dev.kuch.termx.core.domain.model.AuthType
import dev.kuch.termx.core.domain.model.KeyPair
import dev.kuch.termx.core.domain.model.ServerGroup
import java.util.UUID

/**
 * UI state for [AddEditServerSheet].
 *
 * Port is a [String] because [androidx.compose.material3.OutlinedTextField]
 * manages text; the viewmodel only coerces to [Int] at save time.
 *
 * [availableKeys] and [availableGroups] are pushed in by collectors on the
 * repositories so the dropdowns stay live with what the user has created.
 *
 * [testResult] carries the latest "Test connection" outcome — it is reset to
 * [TestResult.Idle] whenever any field changes so the user never sees a stale
 * green check against an edited form.
 */
data class AddEditServerUiState(
    val id: UUID? = null,
    val label: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "",
    val authType: AuthType = AuthType.KEY,
    val selectedKeyPairId: UUID? = null,
    val password: String = "",
    val passwordVisible: Boolean = false,
    val useMosh: Boolean = true,
    val autoAttachTmux: Boolean = true,
    val tmuxSessionName: String = "main",
    val selectedGroupId: UUID? = null,
    val testResult: TestResult = TestResult.Idle,
    val availableKeys: List<KeyPair> = emptyList(),
    val availableGroups: List<ServerGroup> = emptyList(),
    val isLoading: Boolean = false,
    /**
     * True in edit mode when the row has a `passwordAlias` but the vault
     * was locked so we couldn't decrypt it. The UI renders a soft notice
     * so the user knows why the password field is blank.
     */
    val passwordVaultLocked: Boolean = false,
) {
    /** True when the minimum set of fields needed to attempt a connection are present. */
    val canTestConnection: Boolean
        get() {
            if (host.isBlank() || username.isBlank()) return false
            return when (authType) {
                AuthType.KEY -> selectedKeyPairId != null
                AuthType.PASSWORD -> password.isNotEmpty()
            }
        }

    /** Whether the form has enough to persist a row. */
    val canSave: Boolean
        get() = host.isNotBlank() && username.isNotBlank()
}
