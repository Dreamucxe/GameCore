package com.gamecore.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.BuildConfig
import com.gamecore.core.model.AppSettings
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.setup.SetupSignals
import com.gamecore.domain.setup.WizardEntry
import com.gamecore.domain.setup.decideEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The appearance settings for the shell that wraps every screen, and the one first-run decision the shell
 * has to make before it draws anything.
 *
 * A composable cannot be injected, so something has to hold the preference store on [GameCoreRoot]'s
 * behalf. It observes rather than reads once, which matters more here than it looks: the theme, the accent
 * and the UI scale are edited on the Settings screen, and that screen is *inside* the theme it is changing.
 * A one-off read would leave the user's new accent showing only after the next launch.
 *
 * No preload call. `MainActivity` runs the startup pass on a coroutine before the first frame, and that is
 * what takes the keystore unwrap off the main thread; asking for it again here would either race with it or
 * pay for it twice.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    preferences: SecurePreferenceStore,
    profiles: GameProfileRepository,
    sessions: SessionRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = preferences.settings

    /**
     * How the setup wizard should present itself this launch (spec §A1), or null until the facts it reads
     * have loaded. [GameCoreRoot] acts only on [WizardEntry.FullWizard] — a fresh install, taken straight
     * into the wizard; the Home card owns [WizardEntry.HomeCard], and [WizardEntry.Nothing] is nothing to do.
     *
     * Null is the initial value on purpose: it is the difference between "not decided yet" and "decided:
     * nothing", and the root routes on neither of those. The three sources the decision reads all emit a
     * real value on subscription — `settings` is seeded synchronously from the store, and the two Room flows
     * emit their first query result — so [combine]'s first emission is already a settled decision rather than
     * a guess over half-loaded state. That is what stops an upgrader with existing profiles from being
     * mistaken for a fresh install for one frame and having their Home screen taken over.
     */
    val launchEntry: StateFlow<WizardEntry?> = combine(
        preferences.settings,
        profiles.profiles,
        sessions.sessionCount,
    ) { settings, saved, sessionCount ->
        decideEntry(
            SetupSignals(
                hasCompletedVersion = settings.setupCompletedVersion.takeIf { it > 0 },
                currentVersion = BuildConfig.VERSION_CODE,
                dismissed = settings.setupDismissed,
                hasProfiles = saved.isNotEmpty(),
                hasSessions = sessionCount > 0,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
