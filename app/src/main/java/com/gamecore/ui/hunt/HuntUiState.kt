package com.gamecore.ui.hunt

import com.gamecore.core.model.HuntFilter

/**
 * The hunting filter screen (§Hunt): whether a grade is on, and which one it is.
 *
 * There are two facts here that look like one and are not. [filter] is the grade the user has chosen and
 * GameCore has stored; it is a durable preference and is set whether or not anything is on screen. [isOn]
 * is whether that grade is actually being requested right now, and it is read from
 * [com.gamecore.domain.overlay.OverlayController]'s *request* rather than its report — the overlay status
 * was never given a Hunt field, so "is the grade on" is answered by what GameCore asked for, not by a
 * window count. The two diverge in exactly one honest place: a capture grade left on across a process death
 * does not come back on its own, because its screen feed needs a projection consent the process no longer
 * holds, so [filter] can name a capture grade while [isOn] is false until the user switches it on again.
 *
 * [isDrivenByProfile] carries the same meaning it does on the crosshair and HUD screens: while a game's
 * profile owns the overlays, the manual switch is locked rather than hidden, because the honest answer to
 * "why will this not move" is that the game in front is in charge until it stops.
 */
data class HuntUiState(
    val isLoaded: Boolean = false,
    /** The grade the user has chosen and GameCore has stored, on screen or not. */
    val filter: HuntFilter = HuntFilter.DEFAULT,
    /**
     * Whether the grade is being requested right now.
     *
     * Read from the controller's request (`desired.hunt`) and never from its report: the overlay status
     * carries no Hunt field, so this is the honest source for "the grade is on", and it is what the master
     * switch shows.
     */
    val isOn: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    /** True when the overlay service is up, whatever it is drawing. */
    val isServiceRunning: Boolean = false,
    /** True while a game's profile owns the overlay, so the master switch says why it is locked. */
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val message: String? = null,
) {
    /**
     * Whether the master switch can be moved.
     *
     * Locked rather than hidden while a game profile is driving the overlays, and off entirely without the
     * permission to draw over other apps — a grade drawn in GameCore's own window still needs that window,
     * and a capture grade re-paints the screen into it. A switch that flicked itself back with no reason
     * given is worse than one that says why it did not move.
     */
    val canToggle: Boolean get() = hasOverlayPermission && !isDrivenByProfile

    /**
     * Whether the chosen grade re-grades the real screen through the projection feed.
     *
     * The one line §24 turns on: [HuntFilter.MOVIE] tints GameCore's own transparent glass and reads
     * nothing, while the other grades take the same capture feed the loupe and Scout draw from, transform
     * each frame and paint it back. None of them read the game — every grade is a function of the pixels
     * already on screen — but a capture grade means a live projection and its own capture banner, and the
     * screen says so before the user turns one on.
     */
    val gradeNeedsCapture: Boolean get() = filter.needsCapture
}
