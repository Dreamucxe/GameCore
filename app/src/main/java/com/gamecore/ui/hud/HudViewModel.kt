package com.gamecore.ui.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.HudLayoutRepository
import com.gamecore.domain.overlay.OverlayController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The HUD tab: saved layouts, and the state of every overlay window.
 *
 * Nothing here draws anything. Turning a layout on is a write to [OverlayController], which is the one
 * place in the app that starts the overlay service — so a screen cannot end up holding a `WindowManager`
 * token, and two screens cannot disagree about what is on top.
 *
 * The visibility flags come from the controller's *report*, not its request. That is the difference
 * between "GameCore asked for a HUD" and "there is a HUD on screen", and on a device where the user
 * revoked the overlay permission while a game was running they are not the same thing.
 */
@HiltViewModel
class HudViewModel @Inject constructor(
    private val layouts: HudLayoutRepository,
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state that are this ViewModel's own.
     *
     * Widget counts live here because the repository's list flow deliberately omits widgets; the count
     * is one grouped read, refreshed when the list changes rather than per row.
     */
    private data class LocalState(
        val counts: Map<Long, Int> = emptyMap(),
        val activeId: Long? = null,
        val hasPermission: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<HudUiState> = combine(
        layouts.layouts,
        overlay.desired,
        overlay.status,
        local,
    ) { saved, desired, status, own ->
        HudUiState(
            isLoaded = true,
            layouts = saved.map { layout ->
                HudLayoutRow(
                    id = layout.id,
                    name = layout.name,
                    widgetCount = own.counts[layout.id] ?: 0,
                    isActive = layout.id == own.activeId,
                    updatedAtMillis = layout.updatedAtMillis,
                )
            },
            hasOverlayPermission = own.hasPermission,
            isServiceRunning = status.serviceRunning,
            isHudVisible = status.hudVisible,
            isPillVisible = status.pillVisible,
            isButtonVisible = status.buttonVisible,
            isCrosshairVisible = status.crosshairVisible,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
            message = own.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS), HudUiState())

    init {
        local.value = local.value.copy(
            activeId = preferences.activeHudLayoutId,
            hasPermission = overlay.hasPermission(),
        )
        viewModelScope.launch {
            layouts.layouts.collect {
                local.value = local.value.copy(counts = layouts.widgetCounts())
            }
        }
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    /**
     * Puts one layout on screen and remembers it as the active one.
     *
     * Refuses rather than pretends when the permission is missing: `OverlayController` would publish the
     * request, find no permission and report nothing visible, and a switch that flicked back on its own
     * with no explanation is worse than a switch that says why it did not move.
     */
    fun showLayout(layoutId: Long) {
        if (!overlay.hasPermission()) {
            local.value = local.value.copy(
                message = "GameCore needs permission to draw over other apps before it can show a HUD.",
            )
            return
        }
        preferences.activeHudLayoutId = layoutId
        preferences.showHudOverlay = true
        local.value = local.value.copy(activeId = layoutId)
        overlay.setHud(visible = true, layoutId = layoutId)
    }

    fun hideHud() {
        preferences.showHudOverlay = false
        overlay.setHud(visible = false)
    }

    /**
     * Deletes a layout.
     *
     * The repository also clears every profile's reference to it, so the message says so — a profile that
     * silently stopped showing a HUD would be exactly the kind of quiet failure this app exists to avoid.
     */
    fun delete(layoutId: Long) {
        viewModelScope.launch {
            layouts.delete(layoutId)
            if (preferences.activeHudLayoutId == layoutId) {
                preferences.activeHudLayoutId = null
                preferences.showHudOverlay = false
                overlay.setHud(visible = false)
                local.value = local.value.copy(activeId = null)
            }
            local.value = local.value.copy(
                message = "Layout deleted. Any profile that used it now shows no HUD.",
            )
        }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the tab is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
