package com.gamecore.ui.crosshair

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.CustomCrosshairColours
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.CrosshairRepository
import com.gamecore.domain.overlay.OverlayController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One crosshair being adjusted, and the overlay it is drawn in.
 *
 * There is no save button on this screen and that is deliberate. A crosshair is judged by looking at it, so
 * every control writes through: a discrete change — a design, a colour, a switch — is stored as it is made,
 * and a slider stores once when the finger lifts. The alternative, a draft with a save gate, would mean the
 * user adjusts a size, looks at the game, sees the old crosshair, and concludes the app is broken.
 *
 * The write-through has a cost worth naming: dragging a slider does **not** move the live overlay until the
 * drag ends, because moving it during the drag would mean an encrypted database write per frame. The
 * in-screen preview follows the thumb, the overlay catches up on release.
 *
 * Nothing here touches a `WindowManager` or a `ContentResolver`. Showing the crosshair is a request to
 * [OverlayController]; importing an image is a `Uri` handed to [CrosshairRepository], which decodes it
 * before anything is stored (§24A.4) and returns null when it is not an image at all.
 */
@HiltViewModel
class CrosshairViewModel @Inject constructor(
    private val presets: CrosshairRepository,
    private val overlay: OverlayController,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    /**
     * The parts of the state this ViewModel owns rather than observes.
     *
     * The draft lives here because it is not in the database yet — that is the whole point of it during a
     * drag — and because a row rewritten on release must not push the controls back to the stored value
     * mid-gesture.
     */
    private data class LocalState(
        val isLoaded: Boolean = false,
        val draft: CrosshairPreset? = null,
        val activeId: Long? = null,
        val customColours: List<Int> = emptyList(),
        val hasPermission: Boolean = false,
        val isImporting: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<CrosshairUiState> = combine(
        presets.presets,
        overlay.desired,
        overlay.status,
        local,
    ) { saved, desired, status, own ->
        CrosshairUiState(
            isLoaded = own.isLoaded,
            presets = saved,
            draft = own.draft,
            activeId = own.activeId,
            customColours = own.customColours,
            hasOverlayPermission = own.hasPermission,
            isCrosshairVisible = status.crosshairVisible,
            isDrivenByProfile = desired.fromProfile,
            drivingGameLabel = desired.gameLabel,
            isImporting = own.isImporting,
            message = own.message,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        CrosshairUiState(),
    )

    init {
        local.value = local.value.copy(
            activeId = preferences.activeCrosshairPresetId,
            customColours = preferences.customCrosshairColours,
            hasPermission = overlay.hasPermission(),
        )
        viewModelScope.launch { openFirst() }
    }

    /**
     * Seeds the starting presets on first ever open, then puts one under the controls.
     *
     * The active preset if there is one, so a user who comes back to change the colour of the crosshair
     * they are actually using does not have to find it in the list first.
     */
    private suspend fun openFirst() {
        presets.seedDefaultsIfEmpty()
        val saved = presets.presets.first()
        val active = local.value.activeId
        local.value = local.value.copy(
            isLoaded = true,
            draft = saved.firstOrNull { it.id == active } ?: saved.firstOrNull(),
        )
    }

    /** Re-read after a trip to "display over other apps", which is a different app's screen. */
    fun refreshPermission() {
        local.value = local.value.copy(hasPermission = overlay.hasPermission())
    }

    // --------------------------------------------------------------------------- choosing a preset

    fun select(presetId: Long) {
        val saved = state.value.presets.firstOrNull { it.id == presetId } ?: return
        local.value = local.value.copy(draft = saved)
    }

    /**
     * Adds a preset and selects it.
     *
     * Written immediately rather than kept as an unsaved draft: it has to exist in the database before an
     * image can be imported for it — the file name is derived from the row id — and a list that does not
     * show what was just added looks like a button that did nothing.
     */
    fun create() {
        viewModelScope.launch {
            val existing = state.value.presets
            val preset = CrosshairPreset.default(name = "Crosshair ${existing.size + 1}")
            val id = presets.save(preset.normalised())
            local.value = local.value.copy(draft = preset.copy(id = id))
        }
    }

    /**
     * Deletes a preset, its image and every profile's reference to it.
     *
     * If it was the one on screen the overlay is switched off rather than swapped: the user deleted this
     * crosshair, and a different design appearing in its place is not what they asked for.
     */
    fun delete(presetId: Long) {
        viewModelScope.launch {
            presets.delete(presetId)
            if (preferences.activeCrosshairPresetId == presetId) {
                preferences.activeCrosshairPresetId = null
                preferences.showCrosshairOverlay = false
                overlay.setCrosshair(visible = false)
                local.value = local.value.copy(activeId = null)
            }
            val remaining = presets.presets.first()
            val current = local.value
            local.value = current.copy(
                draft = if (current.draft?.id == presetId) remaining.firstOrNull() else current.draft,
                message = "Preset deleted. Any profile that used it now shows no crosshair.",
            )
        }
    }

    // ------------------------------------------------------------------------------- the overlay

    /**
     * Draws the preset being edited over everything else.
     *
     * Refuses rather than pretends without the overlay permission: the controller would publish the
     * request, find no permission and report nothing visible, and a switch that flicks itself back off
     * with no explanation is worse than one that says why it did not move.
     */
    fun show() {
        val draft = local.value.draft ?: return
        if (!overlay.hasPermission()) {
            local.value = local.value.copy(
                message = "GameCore needs permission to draw over other apps before it can show a " +
                    "crosshair.",
            )
            return
        }
        commit()
        preferences.activeCrosshairPresetId = draft.id
        preferences.showCrosshairOverlay = true
        local.value = local.value.copy(activeId = draft.id)
        overlay.setCrosshair(visible = true, presetId = draft.id)
    }

    fun hide() {
        preferences.showCrosshairOverlay = false
        overlay.setCrosshair(visible = false)
    }

    // -------------------------------------------------------------------------------- adjusting

    fun setName(value: String) = edit { it.copy(name = value) }

    fun setDesign(design: CrosshairDesign) = edit {
        // Switching away from an imported image keeps the path: the file is still there, and switching
        // back should not mean importing it again. Only deleting the preset deletes the image.
        it.copy(design = design)
    }

    fun setSize(sizeDp: Int) = edit(store = false) { it.copy(sizeDp = sizeDp) }

    fun setThickness(thicknessDp: Int) = edit(store = false) { it.copy(thicknessDp = thicknessDp) }

    fun setCentreGap(gapDp: Int) = edit(store = false) { it.copy(centreGapDp = gapDp) }

    fun setOpacity(percent: Int) = edit(store = false) { it.copy(opacityPercent = percent) }

    fun setRotation(degrees: Int) = edit(store = false) { it.copy(rotationDegrees = degrees) }

    fun setColour(argb: Int) = edit { it.copy(colorArgb = argb) }

    /**
     * A colour from the picker: put it on the crosshair, and keep it for the next one.
     *
     * Two writes rather than one, and separate from [setColour] because tapping a built-in swatch must not
     * add that swatch to the custom list — it is already in the row above, and a colour appearing twice
     * reads as a bug. [com.gamecore.core.model.CustomCrosshairColours.remember] drops it anyway, so this is
     * belt and braces, but the distinction is real: the picker is the only thing that produces a colour
     * worth remembering.
     */
    fun pickCustomColour(argb: Int) {
        val kept = CustomCrosshairColours.remember(preferences.customCrosshairColours, argb)
        preferences.customCrosshairColours = kept
        local.value = local.value.copy(customColours = kept)
        setColour(argb)
    }

    fun setShowDot(show: Boolean) = edit { it.copy(showDot = show) }

    fun setShowOutline(show: Boolean) = edit { it.copy(showOutline = show) }

    /** Drag or tap in the preview. Fractions, so the position means the same thing on any screen. */
    fun setPosition(xFraction: Float, yFraction: Float) =
        edit(store = false) { it.copy(xFraction = xFraction, yFraction = yFraction) }

    fun centre() = edit { it.copy(xFraction = 0.5f, yFraction = 0.5f) }

    /**
     * Stores the draft. Called by the screen when a slider is released, and by every discrete control.
     *
     * The name is sanitised on the way to storage but not in the draft, so §24A.4's clean-before-storage
     * holds without the text field deleting the space the user just typed.
     */
    fun commit() {
        val draft = local.value.draft ?: return
        if (draft.id <= 0L) return
        val name = TextSanitizer
            .sanitizeName(draft.name, CrosshairPreset.MAX_NAME_LENGTH)
            .ifBlank { "Crosshair" }
        viewModelScope.launch { presets.save(draft.normalised().copy(name = name)) }
    }

    // ------------------------------------------------------------------------------ custom images

    /**
     * Imports the picked image, and only switches the design over if it decoded.
     *
     * The order is the honesty requirement: a preset whose design says "custom image" and whose path
     * points at a file that is not an image renders nothing over the game, with no explanation on any
     * screen. So the repository decodes and re-encodes first, and a null comes back as a message here
     * while the preset keeps the design it had.
     */
    fun importImage(uri: Uri) {
        val draft = local.value.draft ?: return
        if (draft.id <= 0L) return
        local.value = local.value.copy(isImporting = true)
        viewModelScope.launch {
            val path = presets.importImage(draft.id, uri)
            local.value = local.value.copy(isImporting = false)
            if (path == null) {
                local.value = local.value.copy(
                    message = "That file could not be read as an image. Pick a PNG or a JPEG.",
                )
                return@launch
            }
            edit { it.copy(design = CrosshairDesign.CUSTOM_IMAGE, imagePath = path) }
        }
    }

    /** Drops the image reference and goes back to a drawn design. The file goes with the preset. */
    fun clearImage() = edit { it.copy(design = CrosshairDesign.CROSS, imagePath = null) }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    // -------------------------------------------------------------------------------- internals

    /**
     * Applies a change to the draft and, unless told otherwise, stores it.
     *
     * `store = false` is the slider path — the draft moves, the database does not, until the screen calls
     * [commit] on release. Everything else is a single deliberate change and is stored as it is made.
     */
    private inline fun edit(store: Boolean = true, transform: (CrosshairPreset) -> CrosshairPreset) {
        val draft = local.value.draft ?: return
        local.value = local.value.copy(draft = transform(draft).normalised())
        if (store) commit()
    }

    private companion object {
        /** Long enough to survive a rotation, short enough to stop collecting when the screen is left. */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
