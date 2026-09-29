package com.gamecore.ui.hunt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.HuntFilter
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.domain.overlay.OverlayController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The hunting filter (§Hunt): the master switch, and which grade it turns on.
 *
 * Like the crosshair and colour screens, there is no save button. The switch and the grade both write
 * through: turning Hunt on stores the flag and asks [OverlayController] to draw it in the same call, and
 * picking a grade stores the choice and — when Hunt is already on — re-asserts the request so the look
 * over the game swaps as it is tapped. A screen that stored a grade the user could not see the effect of
 * would be a screen they would conclude was broken.
 *
 * Nothing here draws anything or holds a `MediaProjection`. Which grade is a function of the pixels — a
 * warm tint over GameCore's own glass, or a re-grade of the captured screen — is [HuntOverlay]'s job in the
 * overlay service; the service is also the one place that owns the capture consent a capture grade needs,
 * which is why this class asks for the grade by name and knows nothing about the feed behind it. The split
 * matters for what survives a restart: [HuntFilter.MOVIE] needs no capture and comes straight back, so the
 * controller restores it, while a capture grade would restore into an empty window with no projection
 * behind it and is deliberately left off until the user turns it on again.
 */
@HiltViewModel
class HuntViewModel @Inject constructor(
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state this ViewModel owns rather than observes.
     *
     * The overlay permission is read from the platform here rather than taken from the controller's report,
     * because it is granted on a screen belonging to Settings and Android gives no callback for coming back
     * from it — so the screen re-reads it on resume through [refreshPermission].
     */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val hasPermission: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<HuntUiState> = combine(
        preferences.settings,
        overlay.desired,
        overlay.status,
        local,
    ) { settings, desired, status, own ->
        HuntUiState(
            isLoaded = own.isLoaded,
            filter = settings.huntFilter,
            // The overlay status carries no Hunt field, so "the grade is on" is read from the request.
            isOn = desired.hunt,
            hasOverlayPermission = own.hasPermission,
            isServiceRunning = status.serviceRunning,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
            message = own.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS), HuntUiState())

    init {
        local.value = local.value.copy(
            isLoaded = true,
            hasPermission = overlay.hasPermission(),
        )
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    /**
     * Turns the grade on or off, and stores the flag either way.
     *
     * Refuses rather than pretends when the permission is missing on the way on: the controller would
     * publish the request, find no permission and report nothing visible, and a switch that flicked back on
     * its own with no explanation is worse than one that says why it did not move. Turning off needs no
     * permission and always goes through. The stored flag is what a later launch reads to bring [HuntFilter]
     * .MOVIE back; a capture grade is not restored regardless, for the consent reason above.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !overlay.hasPermission()) {
            local.value = local.value.copy(
                message = "GameCore needs permission to draw over other apps before it can grade the screen.",
            )
            return
        }
        preferences.updateSettings { it.copy(huntEnabled = enabled) }
        overlay.setHunt(enabled)
    }

    /**
     * Chooses which grade is drawn.
     *
     * Stored as it is picked, and — only when the grade is already on — re-asserted to the controller so the
     * look over the game changes at the tap rather than the next time Hunt is switched on. When a profile is
     * driving the overlays the grade is off, so nothing is re-asserted and the choice is simply kept for the
     * next manual turn-on, which is what keeps a pick on this screen from fighting the game in front.
     */
    fun select(filter: HuntFilter) {
        preferences.updateSettings { it.copy(huntFilter = filter) }
        if (state.value.isOn) overlay.setHunt(true)
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
