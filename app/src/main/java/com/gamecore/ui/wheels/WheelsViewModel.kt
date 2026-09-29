package com.gamecore.ui.wheels

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
 * The high-sensitivity wheels feature (§Wheels), and the two-halved thing it is.
 *
 * There is no save button here, exactly as on the crosshair screen and for the same reason: a feel guide is
 * judged by looking at it and a sensitivity by moving with it, so every control writes through. The on/off
 * switch and its two sliders store as they settle — a switch the moment it flips, a slider once when the
 * finger lifts. A draft-with-a-save-gate would let a user drag the ring wider, glance at the game, see the
 * old ring, and conclude the app is broken.
 *
 * The write-through carries the crosshair screen's cost, named there too: dragging a slider moves the
 * in-screen value but does **not** rewrite the encrypted settings file until the drag ends, because writing
 * on every pointer event would be dozens of writes a second. [LocalState] holds the mid-drag value; the
 * store catches up on release, at which point the draft is dropped so the persisted value is authoritative
 * again.
 *
 * Nothing here touches a `WindowManager`. Showing the ring is a request to [OverlayController] via
 * [OverlayController.setWheel]; the genuine input remap the feature pairs with is not this class's business
 * at all — it lives inside GameCore's own Aim Lab training surface, driven by the same
 * [SecurePreferenceStore.settings] gain this screen edits. This ViewModel edits the preference; the surface
 * reads it.
 */
@HiltViewModel
class WheelsViewModel @Inject constructor(
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state this ViewModel owns rather than observes.
     *
     * The two slider drafts live here because they are, mid-drag, not in the settings file yet — that is the
     * whole point of them during a gesture — and a file rewritten on release must not shove the thumb back
     * to the stored value before the finger has lifted. Null means "no drag in flight, show what is stored".
     * [hasPermission] is a snapshot of the overlay permission, seeded on entry and refreshed when the screen
     * resumes, because granting it happens on a Settings screen Android gives no callback for.
     */
    private data class LocalState(
        val hasPermission: Boolean = false,
        val radiusDraft: Int? = null,
        val sensitivityDraft: Int? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<WheelsUiState> = combine(
        preferences.settings,
        overlay.desired,
        overlay.status,
        local,
    ) { settings, desired, _, own ->
        // overlay.status is collected to keep this a faithful sibling of the crosshair screen and to
        // recompute when the service's report changes, but it carries no wheel field to read: §Wheels' ring
        // has no capture feed and no failure of its own to report, so its live on/off is the published
        // request (desired.wheel), and the permission the toggle gates on is the local snapshot below.
        WheelsUiState(
            hasOverlayPermission = own.hasPermission,
            isGuideOn = desired.wheel,
            guideRadiusPercent = own.radiusDraft ?: settings.wheelGuideRadiusPercent,
            sensitivityPercent = own.sensitivityDraft ?: settings.wheelSensitivityPercent,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        WheelsUiState(),
    )

    init {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    // ------------------------------------------------------------------------------- the guide ring

    /**
     * Switches the feel-only guide ring on or off.
     *
     * Two effects, both wanted: the choice is persisted to [SecurePreferenceStore.settings] so
     * [OverlayController.restoreManualState] brings the ring back on the next launch — it needs no capture,
     * so unlike Scout and the capture filters it is restored plainly — and the live request is updated via
     * [OverlayController.setWheel] so the ring appears or disappears now, not a launch later. The switch is
     * only movable while the permission is held and no profile is driving the overlays, so this is not
     * reached without permission; [OverlayController] would in any case refuse to start the service when
     * publishing without it rather than draw a ring that is not there.
     */
    fun setGuideEnabled(enabled: Boolean) {
        preferences.updateSettings { it.copy(wheelGuideEnabled = enabled) }
        overlay.setWheel(enabled)
    }

    // ---------------------------------------------------------------------------------- the sliders

    /** Drag on the radius slider: move the shown value, leave the store alone until [commitRadius]. */
    fun setRadius(percent: Int) {
        local.value = local.value.copy(radiusDraft = percent)
    }

    /**
     * Radius slider released: persist the drafted value and drop the draft.
     *
     * Dropping the draft is what hands authority back to the stored value: with it null the combine reads
     * [SecurePreferenceStore.settings] again, which is the same number just written, so nothing flickers and
     * a later change from elsewhere is no longer masked by a stale gesture.
     */
    fun commitRadius() {
        val draft = local.value.radiusDraft ?: return
        preferences.updateSettings { it.copy(wheelGuideRadiusPercent = draft) }
        local.value = local.value.copy(radiusDraft = null)
    }

    /** Drag on the sensitivity slider: move the shown value, leave the store alone until [commitSensitivity]. */
    fun setSensitivity(percent: Int) {
        local.value = local.value.copy(sensitivityDraft = percent)
    }

    /**
     * Sensitivity slider released: persist the drafted gain and drop the draft.
     *
     * The number stored here is the one [com.gamecore.aimlab.engine.WheelDisplacementMath] reads inside the
     * Aim Lab training surface — this screen and that surface share the one preference, so raising the
     * multiplier here raises it there. The same drop-the-draft dance as [commitRadius].
     */
    fun commitSensitivity() {
        val draft = local.value.sensitivityDraft ?: return
        preferences.updateSettings { it.copy(wheelSensitivityPercent = draft) }
        local.value = local.value.copy(sensitivityDraft = null)
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
