package com.gamecore.ui

import androidx.lifecycle.ViewModel
import com.gamecore.core.model.AppSettings
import com.gamecore.data.preferences.SecurePreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * The appearance settings for the shell that wraps every screen.
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
) : ViewModel() {

    val settings: StateFlow<AppSettings> = preferences.settings
}
