package com.gamecore.ui.hud

import com.gamecore.core.model.HudStat

/**
 * The HUD tab's state: the saved layouts, and what is currently drawn over other apps.
 *
 * This screen is the app's overlay hub — layouts, the stats pill, the floating button and the crosshair
 * are all reached or toggled from here — so the state carries the *reported* visibility of each window
 * rather than the request. A switch that showed what GameCore asked for would sit in the "on" position on
 * a device where the overlay permission was revoked from the notification shade an hour ago.
 */
data class HudUiState(
    val isLoaded: Boolean = false,
    val layouts: List<HudLayoutRow> = emptyList(),
    val hasOverlayPermission: Boolean = false,
    /** True when the overlay service is up, whatever it is drawing. */
    val isServiceRunning: Boolean = false,
    val isHudVisible: Boolean = false,
    val isPillVisible: Boolean = false,
    val isButtonVisible: Boolean = false,
    val isCrosshairVisible: Boolean = false,
    /** True while a game's profile owns the overlay, so the manual switches say why they are locked. */
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val message: String? = null,
) {
    val isEmpty: Boolean get() = isLoaded && layouts.isEmpty()

    val activeLayoutId: Long? get() = layouts.firstOrNull { it.isActive }?.id

    /**
     * Whether the manual overlay switches can be moved.
     *
     * Locked while a profile is driving, rather than hidden: the user opened this screen to change
     * something, and the honest answer is that the game in front is in charge until it stops.
     */
    val canToggleOverlays: Boolean get() = hasOverlayPermission && !isDrivenByProfile
}

/**
 * One saved layout, as the list draws it.
 *
 * [widgetCount] is carried rather than derived because the repository's list flow returns layouts with
 * empty widget lists on purpose — joining every widget row to render a count would read the whole HUD
 * table to draw a handful of numbers.
 */
data class HudLayoutRow(
    val id: Long,
    val name: String,
    val widgetCount: Int,
    val isActive: Boolean,
    val updatedAtMillis: Long,
) {
    val summary: String get() = when (widgetCount) {
        0 -> "No stats on it yet"
        1 -> "1 stat"
        else -> "$widgetCount stats"
    }
}

/**
 * Why a stat may show nothing once it is on screen.
 *
 * The builder needs this before anything is saved, so it is a property of the stat rather than of a
 * reading: [HudStat.isAlwaysAvailable] is false for the six that depend on a device, a permission or a
 * network, and the builder says so at the point where the user adds one. The live preview then shows the
 * real reading — including "n/a" — so the answer for *this* device is never guessed.
 */
internal fun HudStat.conditionNote(): String? = when (this) {
    HudStat.FRAME_RATE -> "Frame timing is not exposed by public Android APIs. GameCore reads it only " +
        "where the elevated shell can, and shows n/a everywhere else."
    HudStat.CPU_TEMPERATURE -> "Many devices do not publish a CPU temperature. Shows n/a if this one " +
        "does not."
    HudStat.BATTERY_CURRENT -> "Not every device reports charge current. Shows n/a if this one does not."
    HudStat.NETWORK_LATENCY -> "Measured against a reference host, not the game's server, and only " +
        "while latency measurement is on in Settings."
    HudStat.NETWORK_DOWN, HudStat.NETWORK_UP -> "Needs a network connection to mean anything."
    HudStat.NETWORK -> "Transport, Wi-Fi band, signal and latency in one line. Each piece appears only " +
        "where this device reports it; needs a connection to show anything."
    HudStat.THERMAL_STATUS -> "Reported from Android 10 onwards, and only on devices that implement it."
    else -> null
}
