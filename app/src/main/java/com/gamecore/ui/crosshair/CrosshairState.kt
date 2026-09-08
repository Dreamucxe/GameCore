package com.gamecore.ui.crosshair

import com.gamecore.core.model.CrosshairPreset

/**
 * The crosshair screen: every saved preset, the one the controls are adjusting, and what is on screen.
 *
 * [draft] is held apart from [presets] rather than being looked up in it, and the difference matters while
 * a slider is moving: the draft changes at finger speed and is written to the database when the finger
 * lifts, so the preview follows the thumb without a row being rewritten forty times a second.
 *
 * The visibility flags come from [com.gamecore.domain.overlay.OverlayController]'s *report* rather than its
 * request, so "showing" here means there is a crosshair window on screen, not that GameCore asked for one.
 */
data class CrosshairUiState(
    val isLoaded: Boolean = false,
    val presets: List<CrosshairPreset> = emptyList(),
    val draft: CrosshairPreset? = null,
    /** The preset the overlay draws, remembered across launches. */
    val activeId: Long? = null,
    /**
     * The colours the user mixed in the picker, most recent first, or empty before they mixed any.
     *
     * Part of the state rather than read from the store where the swatch row is drawn, because it changes
     * while the screen is open — mixing a colour adds one — and a composable reading a `var` on a
     * preferences object would not recompose when it did.
     */
    val customColours: List<Int> = emptyList(),
    val hasOverlayPermission: Boolean = false,
    val isCrosshairVisible: Boolean = false,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val isImporting: Boolean = false,
    val message: String? = null,
) {
    /**
     * True when the live overlay is drawing the preset being edited.
     *
     * The screen says so, because it changes what the controls mean: an adjustment made in this state
     * lands on the crosshair over the game as soon as the slider is released, and a user who does not know
     * that will wonder why the thing on their screen moved.
     */
    val isEditingLive: Boolean
        get() = isCrosshairVisible && draft != null && draft.id == activeId

    /** Whether the draft's design is one the renderer draws, as opposed to an imported image. */
    val isDrawnDesign: Boolean get() = draft?.design?.isDrawn ?: true

    /** An imported image that is missing is a preset that renders nothing. The screen warns about it. */
    val hasBrokenImage: Boolean
        get() = draft != null && !draft.design.isDrawn && draft.imagePath == null

    /**
     * Whether the show/hide control can be moved.
     *
     * Locked rather than hidden while a game profile is driving the overlays: the user opened this screen
     * to change something, and the honest answer is that the game in front is in charge until it stops.
     */
    val canToggleOverlay: Boolean get() = hasOverlayPermission && !isDrivenByProfile
}
