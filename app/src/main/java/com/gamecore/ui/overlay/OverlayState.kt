package com.gamecore.ui.overlay

import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.OverlayStatus

/**
 * §7's floating button and §8's performance pill, as configured and as they actually are.
 *
 * [button] and [pill] are held whole, and that is not §24A.2's passthrough: both are the user's own
 * settings objects, every field of which is something this screen sets. What is deliberately *not*
 * carried whole is the platform's answer — [isButtonVisible] and [isPillVisible] come from the overlay
 * service's report rather than from the request that produced it. On a device where the overlay
 * permission was revoked while a game was running those two disagree, and a screen that showed the
 * request would be claiming a window that is not on screen.
 */
data class OverlayUiState(
    val isLoaded: Boolean = false,
    val hasPermission: Boolean = false,
    val button: FloatingButtonConfig = FloatingButtonConfig(),
    val pill: OverlayConfig = OverlayConfig(),
    val isServiceRunning: Boolean = false,
    val isButtonVisible: Boolean = false,
    val isPillVisible: Boolean = false,
    /**
     * The other two windows, which this screen does not configure but does report on.
     *
     * Carried because "hide everything" is on this screen: a user reaching for it is entitled to know
     * that it will also take down a crosshair set up two screens away, and a card that offered the
     * button only when its own two windows were up would leave a crosshair with no way down from here.
     */
    val isCrosshairVisible: Boolean = false,
    val isHudVisible: Boolean = false,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val statusSummary: String = OverlayStatus.OFF.summary,
    val message: String? = null,
) {
    /**
     * Whether a switch on this screen will do anything.
     *
     * False while a game's profile is driving the overlay: the profile decides what is on screen for the
     * length of that session, so a toggle flicked here would be overridden and the honest thing is to
     * lock it and say who is in charge.
     */
    val canToggle: Boolean get() = hasPermission && !isDrivenByProfile

    val addableStats: List<HudStat> get() = HudStat.entries.filterNot { it in pill.stats }

    val isPillFull: Boolean get() = pill.stats.size >= OverlayConfig.MAX_STATS

    val hasStats: Boolean get() = pill.stats.isNotEmpty()

    val isAnythingShown: Boolean
        get() = isButtonVisible || isPillVisible || isCrosshairVisible || isHudVisible

    /** Tenths of a second: the slider steps in integers, and 100 ms is the smallest step worth having. */
    val intervalTenths: Int get() = (pill.updateIntervalMillis / TENTH_MILLIS).toInt()

    val intervalLabel: String
        get() = if (pill.updateIntervalMillis < 1_000L) {
            "${pill.updateIntervalMillis} ms"
        } else {
            val tenths = intervalTenths
            if (tenths % 10 == 0) "${tenths / 10} s" else "${tenths / 10}.${tenths % 10} s"
        }

    companion object {
        const val TENTH_MILLIS = 100L

        /** The interval slider's range, in tenths, derived from the config's own bounds. */
        val INTERVAL_TENTHS: IntRange = (OverlayConfig.MIN_INTERVAL_MILLIS / TENTH_MILLIS).toInt()..
            (OverlayConfig.MAX_INTERVAL_MILLIS / TENTH_MILLIS).toInt()
    }
}
