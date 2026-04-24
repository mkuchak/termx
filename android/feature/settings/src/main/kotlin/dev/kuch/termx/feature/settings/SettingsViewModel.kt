package dev.kuch.termx.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppPreferences
import dev.kuch.termx.core.domain.theme.BuiltInThemes
import dev.kuch.termx.core.domain.theme.TerminalTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * View-model for the Settings screen. Thin wrapper around
 * [AppPreferences] — reads are [StateFlow]s, writes are fire-and-forget.
 *
 * Kept provider-agnostic: the themes list is pulled from
 * [BuiltInThemes] directly because Task #18 ships only the six baked-in
 * entries; Task #48's theme editor will wrap this in a repository.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> = combine(
        appPreferences.fontSizeSp,
        appPreferences.activeThemeId,
    ) { fontSp, themeId ->
        SettingsUiState(
            fontSizeSp = fontSp,
            activeThemeId = themeId,
            themes = BuiltInThemes.all,
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
}

data class SettingsUiState(
    val fontSizeSp: Int = 14,
    val activeThemeId: String = "dracula",
    val themes: List<TerminalTheme> = BuiltInThemes.all,
)
