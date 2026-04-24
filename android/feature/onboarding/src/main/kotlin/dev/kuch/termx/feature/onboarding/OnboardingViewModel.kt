package dev.kuch.termx.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kuch.termx.core.data.prefs.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * View-model for the 3-screen first-launch onboarding (Task #46).
 *
 * The view-model's only real job is to flip the
 * [AppPreferences.onboardingComplete] gate — every UI-side animation
 * lives in [OnboardingScreen]. Completion is fire-and-forget; the
 * top-level NavHost observes the same flow and will switch start
 * destination the next time the effect re-runs.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val appPreferences: AppPreferences,
) : ViewModel() {

    fun markComplete() {
        viewModelScope.launch {
            appPreferences.setOnboardingComplete(true)
        }
    }
}
