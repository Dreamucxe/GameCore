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

/**
 * The colours a crosshair can be drawn in.
 *
 * A fixed list rather than a picker, chosen for contrast against a game rather than for prettiness: a
 * crosshair is only useful if the eye finds it instantly on whatever is behind it. White and cyan read on
 * the most scenes, red is here because it is what people expect, and the dark outline switch covers the
 * cases none of them survive on their own.
 *
 * Longer than the HUD's list because a crosshair is one shape on one background, where a HUD is text that
 * has to stay readable — the constraint is looser, so there is room for the colours people ask for.
 */
internal val CROSSHAIR_COLOURS: List<Int> = listOf(
    0xFFFFFFFF.toInt(),
    0xFF00E5FF.toInt(),
    0xFF4CE07A.toInt(),
    0xFFC6FF00.toInt(),
    0xFFFFB300.toInt(),
    0xFFFF5A87.toInt(),
    0xFFFF1744.toInt(),
    0xFF9C6BFF.toInt(),
)
