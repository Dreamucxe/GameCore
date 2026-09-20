package com.gamecore.ui.trigger

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.core.model.QuickTriggerMethod
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.trigger.QuickTriggerCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The Quick Trigger settings: the shortcut a user configures to open GameCore, and an honest reading of
 * what each way of firing it can actually do on this device.
 *
 * The configuration is written straight through [SecurePreferenceStore] like every other setting. The
 * availability is not stored — it is device state, re-read by [refresh] every time the screen resumes,
 * because the answer changes when the user grants the overlay permission, turns the accessibility service
 * on, or adds the tile, none of which this screen can observe from a flow. After every change that could
 * start or stop the shake watcher, [QuickTriggerCoordinator.syncService] is called from the foreground,
 * which is the only place it is allowed to succeed on modern Android.
 */
@HiltViewModel
class QuickTriggerViewModel @Inject constructor(
    private val preferences: SecurePreferenceStore,
    private val coordinator: QuickTriggerCoordinator,
) : ViewModel() {

    /** What this screen owns rather than reads from the store: the drafts and the last message. */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val availability: List<com.gamecore.core.model.TriggerAvailability> = emptyList(),
        val accessibilityEnabled: Boolean = false,
        val windowDraft: Int? = null,
        val shakeDraft: Int? = null,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<QuickTriggerUiState> = combine(
        preferences.settings,
        local,
    ) { settings, own ->
        QuickTriggerUiState(
            isLoaded = own.isLoaded,
            settings = settings.quickTrigger,
            availability = own.availability,
            accessibilityEnabled = own.accessibilityEnabled,
            canRequestTile = coordinator.canRequestTile(),
            windowDraft = own.windowDraft,
            shakeDraft = own.shakeDraft,
            message = own.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        QuickTriggerUiState(),
    )

    init {
        refresh()
    }

    /**
     * Re-reads the live availability. Called on load and on every resume.
     *
     * This is where a permission granted out in the system settings becomes visible: the accessibility
     * setting, the overlay permission and the accelerometer are all read here, not held in a flow, because
     * nothing publishes a change to them back into the app.
     */
    fun refresh() {
        local.value = local.value.copy(
            isLoaded = true,
            availability = coordinator.availability(),
            accessibilityEnabled = coordinator.isAccessibilityEnabled(),
        )
    }

    // --------------------------------------------------------------------------------- configuration

    private fun edit(transform: (AppSettings) -> AppSettings) {
        preferences.updateSettings(transform)
        // The shake service must be started or stopped from the foreground, and this screen is it.
        coordinator.syncService()
    }

    fun setEnabled(enabled: Boolean) {
        edit { it.copy(quickTrigger = it.quickTrigger.copy(enabled = enabled)) }
    }

    fun setMethod(method: QuickTriggerMethod) {
        if (method == state.value.settings.method) return
        edit { it.copy(quickTrigger = it.quickTrigger.copy(method = method)) }
    }

    fun setAction(action: QuickTriggerAction) {
        if (action == state.value.settings.action) return
        edit { it.copy(quickTrigger = it.quickTrigger.copy(action = action)) }
    }

    fun setPassThroughKeys(passThrough: Boolean) {
        edit { it.copy(quickTrigger = it.quickTrigger.copy(passThroughKeys = passThrough)) }
    }

    // The window and the shake sensitivity are dragged, so the store is written once on release rather than
    // on every frame — the same rule the appearance sliders follow.

    fun setWindow(millis: Int) {
        local.value = local.value.copy(windowDraft = millis)
    }

    fun commitWindow() {
        val draft = local.value.windowDraft ?: return
        local.value = local.value.copy(windowDraft = null)
        edit { it.copy(quickTrigger = it.quickTrigger.copy(windowMillis = draft.toLong())) }
    }

    fun setShake(sensitivity: Int) {
        local.value = local.value.copy(shakeDraft = sensitivity)
    }

    fun commitShake() {
        val draft = local.value.shakeDraft ?: return
        local.value = local.value.copy(shakeDraft = null)
        edit { it.copy(quickTrigger = it.quickTrigger.copy(shakeSensitivity = draft)) }
    }

    // ------------------------------------------------------------------------------------- system

    /** The system accessibility screen, so the user can turn GameCore's service on for global keys. */
    fun accessibilityIntent(): Intent = coordinator.accessibilitySettingsIntent()

    fun onAccessibilityIntentFailed() {
        local.value = local.value.copy(message = NO_ACCESSIBILITY_SCREEN)
    }

    /** Fires the trigger once so the user can confirm the configured action does what they expect. */
    fun testFire() {
        coordinator.fire(state.value.settings.action)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    fun onTileResult(added: Boolean) {
        local.value = local.value.copy(message = if (added) TILE_ADDED else null)
    }

    fun onTilePromptUnavailable() {
        local.value = local.value.copy(message = TILE_MANUAL)
    }

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        const val NO_ACCESSIBILITY_SCREEN =
            "This device would not open its accessibility settings. You can reach them from Android's own " +
                "Settings app, under Accessibility."

        const val TILE_ADDED =
            "The GameCore tile was added to Quick Settings. Pull down the shade to use it."

        const val TILE_MANUAL =
            "This version of Android has no way for an app to add the tile for you. Pull down Quick " +
                "Settings, open its edit screen, and drag the GameCore tile into the active row."
    }
}
