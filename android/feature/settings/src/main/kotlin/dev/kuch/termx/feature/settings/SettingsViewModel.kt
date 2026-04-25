package dev.kuch.termx.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.data.prefs.GeminiApiKeyStore
import dev.kuch.termx.core.domain.theme.BuiltInThemes
import dev.kuch.termx.core.domain.theme.TerminalTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * View-model for the Settings screen. Thin wrapper around
 * [AppPreferences] — reads are [StateFlow]s, writes are fire-and-forget.
 *
 * Task #41 adds the Gemini API key surface: the key itself lives in the
 * sandboxed [GeminiApiKeyStore], never in the plain DataStore alongside
 * theme/font preferences. The UI only knows whether a key is present
 * and can issue "save" / "clear" verbs.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
    private val geminiApiKeyStore: GeminiApiKeyStore,
) : ViewModel() {

    private val geminiKeySaved = MutableStateFlow(false)
    private val geminiSaveStatus = MutableStateFlow<String?>(null)

    init {
        refreshGeminiKeyPresence()
    }

    /**
     * `combine` only has typed overloads up to 5 flows; collapse the
     * three PTT-language flows into a Triple first so we stay inside
     * the 5-arg form rather than reaching for the vararg
     * `Array<Any?>` overload (which would force casts on every field).
     */
    private val pttLanguageFlow = combine(
        appPreferences.pttSourceLanguage,
        appPreferences.pttTargetLanguage,
        appPreferences.pttContext,
    ) { source, target, context -> Triple(source, target, context) }

    val state: StateFlow<SettingsUiState> = combine(
        appPreferences.fontSizeSp,
        appPreferences.activeThemeId,
        geminiKeySaved,
        geminiSaveStatus,
        pttLanguageFlow,
    ) { fontSp, themeId, keySaved, saveStatus, ptt ->
        SettingsUiState(
            fontSizeSp = fontSp,
            activeThemeId = themeId,
            themes = BuiltInThemes.all,
            geminiKeyPresent = keySaved,
            geminiSaveStatus = saveStatus,
            pttSourceLanguage = ptt.first,
            pttTargetLanguage = ptt.second,
            pttContext = ptt.third,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(),
    )

    fun setFontSize(sp: Int) {
        viewModelScope.launch { appPreferences.setFontSizeSp(sp) }
    }

    fun setTheme(id: String) {
        viewModelScope.launch { appPreferences.setActiveThemeId(id) }
    }

    /**
     * Persist [key] into the sandboxed vault. Empty input is treated as
     * "no key" — same effect as [clearGeminiKey].
     */
    fun saveGeminiKey(key: String) {
        viewModelScope.launch {
            runCatching {
                val trimmed = key.trim()
                if (trimmed.isEmpty()) {
                    geminiApiKeyStore.clear()
                } else {
                    geminiApiKeyStore.put(trimmed)
                }
            }.onSuccess {
                geminiSaveStatus.value = "API key saved"
                refreshGeminiKeyPresence()
            }.onFailure { t ->
                geminiSaveStatus.value = t.message ?: "Failed to save API key"
            }
        }
    }

    fun clearGeminiKey() {
        viewModelScope.launch {
            runCatching { geminiApiKeyStore.clear() }
                .onSuccess {
                    geminiSaveStatus.value = "API key cleared"
                    refreshGeminiKeyPresence()
                }
                .onFailure { t ->
                    geminiSaveStatus.value = t.message ?: "Failed to clear API key"
                }
        }
    }

    fun consumeGeminiStatus() {
        geminiSaveStatus.value = null
    }

    fun setPttSourceLanguage(code: String) {
        viewModelScope.launch { appPreferences.setPttSourceLanguage(code) }
    }

    fun setPttTargetLanguage(code: String) {
        viewModelScope.launch { appPreferences.setPttTargetLanguage(code) }
    }

    fun setPttContext(value: String) {
        viewModelScope.launch { appPreferences.setPttContext(value) }
    }

    private fun refreshGeminiKeyPresence() {
        viewModelScope.launch {
            val present = runCatching { geminiApiKeyStore.exists() }.getOrDefault(false)
            geminiKeySaved.value = present
        }
    }
}

data class SettingsUiState(
    val fontSizeSp: Int = 14,
    val activeThemeId: String = "dracula",
    val themes: List<TerminalTheme> = BuiltInThemes.all,
    val geminiKeyPresent: Boolean = false,
    val geminiSaveStatus: String? = null,
    val pttSourceLanguage: String = "en-US",
    val pttTargetLanguage: String = "en-US",
    val pttContext: String = "",
)
