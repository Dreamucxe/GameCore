package com.gamecore.ui.scout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * The Scout zoom pane (§Scout), and the two settings that shape it.
 *
 * The magnifier's sibling, and this ViewModel is deliberately the loupe's shape too: no save button, every
 * control writing straight through. A discrete change — the show/hide switch — takes effect as it is made,
 * and a slider stores once when the finger lifts. The alternative, a draft with a save gate, would have the
 * user drag the zoom up, look at their game, see the old magnification, and conclude the app is broken.
 *
 * The write-through has the same cost the crosshair's does, and it is worth naming: dragging a slider does
 * **not** move the live pane until the drag ends, because [SecurePreferenceStore.updateSettings] writes an
 * encrypted file and doing that per frame would be a write a frame. The label under the thumb follows the
 * finger through [zoomDraftTenths]/[liftDraftPercent]; the pane over the game catches up on release.
 *
 * Nothing here touches a `WindowManager` or a `MediaProjection`. Showing Scout is a request to
 * [OverlayController]; the service owns the capture feed and starts it when it reconciles that request,
 * because the feed needs the projection consent this layer knows nothing about. That is also why the
 * on/off flag is not persisted the way the zoom and the lift are — see [ScoutUiState] — and why turning
 * Scout on without the overlay permission is refused here rather than published to fail silently.
 */
@HiltViewModel
class ScoutViewModel @Inject constructor(
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state this ViewModel owns rather than observes.
     *
     * The two drafts are null except while a slider is being dragged, so the combined state falls back to
     * the stored value the moment a gesture ends and the store becomes the one copy of the truth again. The
     * permission is held here because it is granted on a screen belonging to Settings, which gives no
     * callback on the way back, so it is re-read on resume rather than observed.
     */
    private data class LocalState(
        val hasPermission: Boolean = false,
        val zoomDraftTenths: Int? = null,
        val liftDraftPercent: Int? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<ScoutUiState> = combine(
        preferences.settings,
        overlay.desired,
        overlay.status,
        local,
    ) { settings, desired, status, own ->
        ScoutUiState(
            zoomTenths = own.zoomDraftTenths ?: settings.scoutZoomTenths,
            liftPercent = own.liftDraftPercent ?: settings.scoutLiftPercent,
            // The request, not a report: OverlayStatus has no scout field, and Scout only paints while a
            // live feed runs, so "on" here means the user asked for it. See ScoutUiState's KDoc.
            isScoutOn = desired.scout,
            hasOverlayPermission = own.hasPermission,
            isServiceRunning = status.serviceRunning,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        ScoutUiState(),
    )

    init {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    // ------------------------------------------------------------------------------- the overlay

    /**
     * Turns Scout on or off.
     *
     * Turning it on refuses without the overlay permission rather than pretends: the controller would
     * publish the request, find no permission and report nothing visible, and a switch that flicks itself
     * back off with no explanation is worse than one that stays put while the screen says why. Re-reading
     * the permission on the way out flips [ScoutUiState.canToggleOverlay] so the switch settles disabled.
     * Turning it off is always allowed — the flag is never persisted, so this is the whole of the write.
     */
    fun setVisible(visible: Boolean) {
        if (visible && !overlay.hasPermission()) {
            refreshPermission()
            return
        }
        overlay.setScout(visible)
    }

    // -------------------------------------------------------------------------------- adjusting

    /** Zoom drag: the draft moves, the store does not, until [commitZoom] on release. Tenths of a factor. */
    fun setZoom(tenths: Int) {
        local.value = local.value.copy(zoomDraftTenths = tenths)
    }

    /** Stores the dragged zoom, then drops the draft so the state reads the clamped stored value again. */
    fun commitZoom() {
        val tenths = local.value.zoomDraftTenths ?: return
        preferences.updateSettings { it.copy(scoutZoomTenths = tenths) }
        local.value = local.value.copy(zoomDraftTenths = null)
    }

    /** Dark-scene lift drag: draft only, committed on release by [commitLift]. Percent, 0 meaning no filter. */
    fun setLift(percent: Int) {
        local.value = local.value.copy(liftDraftPercent = percent)
    }

    /** Stores the dragged lift, then drops the draft so the state reads the clamped stored value again. */
    fun commitLift() {
        val percent = local.value.liftDraftPercent ?: return
        preferences.updateSettings { it.copy(scoutLiftPercent = percent) }
        local.value = local.value.copy(liftDraftPercent = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
