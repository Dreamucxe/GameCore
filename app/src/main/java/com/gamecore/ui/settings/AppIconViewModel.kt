package com.gamecore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.plugin.icon.AppIconSlot
import com.gamecore.data.icon.AppIconApplier
import com.gamecore.data.icon.AppIconController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app-icon picker's state.
 *
 * A ViewModel of its own rather than another corner of [SettingsViewModel] because the icon is not a
 * setting: it is not in the settings store, it is not in an export, and it can be changed by a plugin
 * rather than by the user. Keeping it separate is what lets the card be one self-contained piece in
 * the Settings list while the rest of Settings stays a form over `AppSettings`.
 *
 * Every action funnels through [AppIconController] and then re-reads its state, so the UI never
 * assumes a switch succeeded — a request the registry refuses simply leaves the card showing the old
 * icon.
 */
@HiltViewModel
class AppIconViewModel @Inject constructor(
    private val controller: AppIconController,
) : ViewModel() {

    data class UiState(
        val icon: AppIconController.State = AppIconController.State(),
        val busy: Boolean = false,
        val message: String? = null,
    )

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> =
        combine(controller.state, busy, message) { icon, working, note ->
            UiState(icon = icon, busy = working, message = note)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // Guarded for the same reason [run] is: this reads the package manager and the encrypted
        // store before the user has asked for anything, and an exception escaping a `viewModelScope`
        // launch is not a failed refresh, it is a dead process — taking Settings with it.
        viewModelScope.launch {
            try {
                controller.refresh()
            } catch (_: Exception) {
                message.value = "The app icon could not be read on this device."
            }
        }
    }

    fun chooseSlot(slot: AppIconSlot) = run("Switched to the ${slot.label} icon.") {
        controller.chooseSlot(slot)
        null
    }

    fun usePluginIcon(pluginId: String) = run("That plugin's icon is now in use.") {
        val after = controller.acceptPluginIcon(pluginId)
        if (after.inForce == null) "That plugin could not supply its icon just now." else null
    }

    fun reset() = run("Back to your own icon.") {
        controller.resetToUserChoice()
        null
    }

    /**
     * Pins the plugin's artwork as a home-screen shortcut.
     *
     * The three results get three sentences because they need three different reactions: accept a
     * dialog, pick another launcher, or tell the author their image is broken.
     */
    fun pinArtwork(pluginId: String) = run(null) {
        when (controller.pinArtwork(pluginId)) {
            AppIconApplier.PinResult.Requested -> "Confirm the shortcut in your launcher."
            AppIconApplier.PinResult.Unsupported -> "This launcher does not support pinned icons."
            AppIconApplier.PinResult.Unreadable -> "That image could not be read for a shortcut."
        }
    }

    fun dismissMessage() {
        message.value = null
    }

    /**
     * Runs one action with the card disabled, then reports [success] — unless the action returned its
     * own sentence, which wins because it knows what actually happened.
     */
    private fun run(success: String?, action: suspend () -> String?) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            val override = try {
                action()
            } catch (_: Exception) {
                // The controller touches the package manager and the encrypted store; either can
                // throw on a device having a bad day. The card stays usable and says so.
                "That could not be changed just now."
            } finally {
                busy.value = false
            }
            message.value = override ?: success
        }
    }
}
