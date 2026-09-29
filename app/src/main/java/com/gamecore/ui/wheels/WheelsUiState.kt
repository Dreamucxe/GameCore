package com.gamecore.ui.wheels

import com.gamecore.core.model.AppSettings

/**
 * The high-sensitivity wheels screen (§Wheels): what the feel-only guide ring is doing, and the two numbers
 * that shape the feature's honest half — the ring's size and the remap gain applied inside Aim Lab.
 *
 * The feature is deliberately two things wearing one name, and this state keeps both visible. The guide is a
 * ring drawn in GameCore's own `FLAG_NOT_TOUCHABLE` window over whatever is underneath — a sticker on the
 * glass that reads nothing and takes no touch — and [guideRadiusPercent] is its size as a fraction of the
 * shorter screen edge. The remap is the genuine input re-scale, and it does not live out here at all: it
 * runs inside GameCore's own Aim Lab training surface, where there is a real stick to re-scale and no other
 * app to reach into, driven by [sensitivityPercent] through
 * [com.gamecore.aimlab.engine.WheelDisplacementMath]. The screen shows the gain here because it is the same
 * preference either place reads, but the honest line — the ring only hints at the feel, the surface delivers
 * it — is drawn in the copy, not implied by the layout.
 *
 * [isGuideOn] is read from [com.gamecore.domain.overlay.OverlayController]'s *request* rather than its
 * report, which is the opposite of the crosshair screen and worth stating.
 * [com.gamecore.core.model.OverlayStatus] was never given a wheel field — the ring has no capture feed and no
 * failure mode of its own to report — so "is the ring up" is answered by `desired.wheel`, the request the
 * controller is publishing, while the status is consulted only for [hasOverlayPermission] and whether the
 * service is running at all. A profile in charge is read the
 * same way the crosshair reads it, from the request's [isDrivenByProfile] and [drivingGameLabel].
 */
data class WheelsUiState(
    val hasOverlayPermission: Boolean = false,
    /**
     * Whether the guide ring is on screen right now, taken from the published overlay request.
     *
     * Not the persisted [AppSettings.wheelGuideEnabled] and not a service report: it is `desired.wheel`, so a
     * profile switching the overlays around, or the "stop overlay" control, is reflected here the instant it
     * happens rather than a launch later.
     */
    val isGuideOn: Boolean = false,
    /** The ring's radius as a percent of the shorter screen edge, [AppSettings.WHEEL_RADIUS_MIN]..MAX. */
    val guideRadiusPercent: Int = AppSettings.WHEEL_RADIUS_DEFAULT,
    /**
     * The remap gain in percent where 100 is neutral, [AppSettings.WHEEL_SENSITIVITY_MIN]..MAX.
     *
     * Shown on this screen as a multiplier (x0.50–x3.00) because that is what it means to the hand, but
     * stored as the percent the settings model clamps, and read unchanged by the Aim Lab surface.
     */
    val sensitivityPercent: Int = AppSettings.WHEEL_SENSITIVITY_DEFAULT,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
) {
    /**
     * Whether the guide switch can be moved.
     *
     * Locked, not hidden, without the overlay permission or while a game's profile is driving the overlays —
     * the crosshair screen's [com.gamecore.ui.crosshair.CrosshairUiState.canToggleOverlay] rule, for the same
     * reason: the user opened this screen to change something, and the honest answer is that nothing draws
     * over other apps without the permission, and the game in front is in charge until it stops.
     */
    val canToggleGuide: Boolean get() = hasOverlayPermission && !isDrivenByProfile
}
