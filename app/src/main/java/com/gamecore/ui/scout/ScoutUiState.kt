package com.gamecore.ui.scout

import com.gamecore.core.model.AppSettings

/**
 * The Scout screen: the zoom pane's two settings, whether it is on the glass right now, and the two facts
 * that decide whether its switch can move.
 *
 * Scout (§Scout) is the magnifier's sibling — a magnified crop of the centre of the screen, drawn into a
 * corner from the same shared screen feed the loupe uses. What it draws is governed by two durable
 * preferences, [zoomTenths] and [liftPercent], and those are the only part of this state that survives a
 * relaunch. They are read straight from [com.gamecore.core.model.AppSettings], so the value on screen is
 * always one the store accepted in [com.gamecore.core.model.AppSettings.normalised] and never a draft the
 * store would have clamped.
 *
 * ## Why "on" comes from the request, not a report
 *
 * The crosshair screen learns whether it is on screen from [OverlayController][com.gamecore.domain.overlay.OverlayController]'s
 * *report* — the service confirms a crosshair window exists — because a shape on the glass is entirely
 * GameCore's to place. Scout cannot be reported the same way: [com.gamecore.core.model.OverlayStatus]
 * carries no scout field, and Scout only actually paints while a live capture feed is running, which the
 * status flags do not describe. So [isScoutOn] reflects the *request* ([com.gamecore.domain.overlay.OverlayController.desired]'s
 * `scout`) — what the user asked for — and [isServiceRunning] is used only to say honestly, on the screen,
 * whether anything is up to draw it yet. "On" here means "asked for", and the copy never pretends the
 * request is a confirmed picture.
 *
 * This is also why the on/off flag is not remembered across a launch, while the zoom and the lift are:
 * [com.gamecore.domain.overlay.OverlayController.restoreManualState] deliberately leaves Scout out, because
 * restoring the switch would put it on with an empty window behind it and no feed to fill it. Scout comes
 * back only when the user turns it on again, once a feed exists.
 */
data class ScoutUiState(
    /** The zoom, in tenths of a factor: 20..80 is ×2.0..×8.0, read back from the store already clamped. */
    val zoomTenths: Int = AppSettings.SCOUT_ZOOM_DEFAULT_TENTHS,
    /** The dark-scene brightness lift, 0..[com.gamecore.core.model.AppSettings.SCOUT_LIFT_MAX]. Zero is no filter at all. */
    val liftPercent: Int = 0,
    /** What the user has asked for, from the overlay request — not a report that a pane is painting. */
    val isScoutOn: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    /** Whether the overlay service is up, so the screen can say whether there is anything to draw Scout yet. */
    val isServiceRunning: Boolean = false,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
) {
    /** The zoom as the factor the copy shows, so the screen never has to divide by ten in two places. */
    val zoomFactor: Float get() = zoomTenths / 10f

    /** True once the lift does anything at all; at zero Scout draws a faithful crop with no filter. */
    val isLiftOn: Boolean get() = liftPercent > 0

    /**
     * Whether the show/hide control can be moved.
     *
     * Locked rather than hidden while a game profile is driving the overlays: the user opened this screen to
     * change something, and the honest answer is that the game in front is in charge until it stops. Locked
     * too without the overlay permission, because a request to show Scout with no permission would publish,
     * find none, and report nothing up — a switch that flicks itself back off with no explanation.
     */
    val canToggleOverlay: Boolean get() = hasOverlayPermission && !isDrivenByProfile
}
