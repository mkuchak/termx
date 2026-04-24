package dev.kuch.termx.feature.terminal.diff

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.session.EventStreamHub
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Loads one diff payload from the VPS over SFTP and exposes it to
 * [DiffViewerScreen].
 *
 * Input contract (via [SavedStateHandle]):
 *  - `diffId` (String): the uuid of the diff file under `~/.termx/diffs/`
 *  - `serverId` (String): the server whose [EventStreamHub] entry owns
 *    the SFTP channel to fetch from.
 *
 * Failure modes handled:
 *  - Server entry gone (VM still navigable while session disconnected):
 *    surfaces a clear "Server disconnected" state.
 *  - SFTP read error: message exposed verbatim; user can back out.
 *  - JSON parse error: ignoreUnknownKeys is on so schema drift is safe,
 *    but a catastrophically bad payload still surfaces a message.
 */
@HiltViewModel
class DiffViewerViewModel @Inject constructor(
    private val hub: EventStreamHub,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow<DiffViewerState>(DiffViewerState.Loading)
    val state: StateFlow<DiffViewerState> = _state.asStateFlow()

    private val diffId: String = savedStateHandle["diffId"] ?: ""
    private val serverId: UUID? = savedStateHandle.get<String>("serverId")?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
    }

    init {
        viewModelScope.launch {
            if (diffId.isBlank() || serverId == null) {
                _state.value = DiffViewerState.Error("Invalid diff link")
                return@launch
            }
            val client = hub.clients.value[serverId]?.client
            if (client == null) {
                _state.value = DiffViewerState.Error("Server not connected")
                return@launch
            }
            _state.value = DiffViewerState.Loading
            runCatching {
                val bytes = client.loadDiff(diffId)
                JSON.decodeFromString<DiffPayload>(bytes.toString(Charsets.UTF_8))
            }.onSuccess { payload ->
                _state.value = DiffViewerState.Loaded(payload)
            }.onFailure { t ->
                _state.value = DiffViewerState.Error(t.message ?: "Couldn't load diff")
            }
        }
    }

    private companion object {
        val JSON: Json = Json { ignoreUnknownKeys = true }
    }
}
