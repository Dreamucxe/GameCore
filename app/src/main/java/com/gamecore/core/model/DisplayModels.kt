package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * What the display is doing, and what it is capable of.
 *
 * The distinction between [currentRefreshRate] and [supportedRates] is the whole
 * point of this type. `Display.getRefreshRate()` reports the mode active at the
 * instant it was called, and Android switches modes constantly — a 120 Hz panel
 * showing a still screen reports 60. So a "current" reading below the peak means
 * nothing about the panel's capability, and the two are never conflated.
 */
data class DisplayReading(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
    /** The mode active when this was read. Changes without notice. */
    val currentRefreshRate: Observed<Float>,
    /** Every distinct rate the panel advertises, descending. */
    val supportedRates: Observed<List<Float>>,
    val activeMode: Observed<DisplayMode>,
    val modes: Observed<List<DisplayMode>>,
    val rotationDegrees: Int,
    val isHdr: Boolean,
) {
    val peakRefreshRate: Float?
        get() = (supportedRates as? Observed.Value)?.value?.maxOrNull()

    /**
     * True when the panel offers more than one rate at the resolution it is running.
     * The strongest signal Android exposes: there is no flag for LTPO and none for a
     * continuous rate range.
     */
    val hasMultipleRates: Boolean
        get() = (supportedRates as? Observed.Value)?.value?.size?.let { it > 1 } == true

    val resolutionLabel: String get() = "$widthPixels × $heightPixels"

    companion object {
        fun unavailable(detail: String) = DisplayReading(
            widthPixels = 0,
            heightPixels = 0,
            densityDpi = 0,
            currentRefreshRate = Observed.Failed(detail),
            supportedRates = Observed.Failed(detail),
            activeMode = Observed.Failed(detail),
            modes = Observed.Failed(detail),
            rotationDegrees = 0,
            isHdr = false,
        )
    }
}

/** One entry from `Display.getSupportedModes()`. */
data class DisplayMode(
    val modeId: Int,
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRate: Float,
) {
    val label: String get() = "$widthPixels × $heightPixels @ ${refreshRate.rounded()} Hz"

    private fun Float.rounded(): String {
        val whole = Math.round(this)
        return if (kotlin.math.abs(this - whole) < 0.2f) "$whole" else String.format("%.1f", this)
    }
}

/**
 * The result of asking the display to change refresh rate.
 *
 * This type exists because of one specific, verified platform behaviour: on MediaTek
 * and PowerVR devices the standard `WindowManager.LayoutParams.preferredDisplayModeId`
 * and `Surface.setFrameRate()` calls are accepted without error and then ignored. An
 * app that reports success because the setter did not throw is lying to its user on
 * every one of those devices.
 *
 * So there is no "applied" case that means "we asked". [Applied] is only returned
 * after the change has been read back and confirmed, [NotHonoured] is the specific
 * case where the request was accepted and the panel did not move, and
 * [AppliedUnverified] exists for the one situation where confirmation is genuinely
 * impossible — and says so in the UI rather than being folded into success.
 */
sealed interface RefreshRateOutcome {

    /** Confirmed: the display is now running at [rateHz], read back after the change. */
    data class Applied(val rateHz: Float, val verifiedBy: String) : RefreshRateOutcome

    /**
     * The request went through and the panel did not adopt it. The MediaTek case, and
     * the reason [RefreshRateOutcome] is a sealed type at all.
     */
    data class NotHonoured(val requestedHz: Float, val actualHz: Float?) : RefreshRateOutcome

    /**
     * Applied, but the result could not be read back — no Shizuku to query the
     * platform's own dump, and the app's own window is not on screen to observe. Shown
     * as "requested, not confirmed", never as success.
     */
    data class AppliedUnverified(val requestedHz: Float, val reason: String) : RefreshRateOutcome

    /** The panel does not offer this rate. Includes what it does offer. */
    data class RateUnsupported(val requestedHz: Float, val available: List<Float>) : RefreshRateOutcome

    /** Needs an access the user has not granted. */
    data class RequiresAccess(val detail: String, val needsShizuku: Boolean) : RefreshRateOutcome

    /** A genuine failure: the command errored, or the platform threw. */
    data class Failed(val detail: String) : RefreshRateOutcome

    val isSuccess: Boolean get() = this is Applied

    /** What the user is told. Every case has its own sentence; none of them says "done". */
    val message: String
        get() = when (this) {
            is Applied -> "Display is running at ${rateHz.toInt()} Hz."
            is NotHonoured -> if (actualHz != null) {
                "This device accepted the request but stayed at ${actualHz.toInt()} Hz. " +
                    "Some chipsets ignore the standard refresh-rate API; Shizuku can " +
                    "usually set it directly."
            } else {
                "This device accepted the request but did not change rate. Some chipsets " +
                    "ignore the standard refresh-rate API; Shizuku can usually set it directly."
            }
            is AppliedUnverified ->
                "Requested ${requestedHz.toInt()} Hz. The change could not be confirmed: $reason"
            is RateUnsupported -> if (available.isEmpty()) {
                "This display does not report any selectable refresh rates."
            } else {
                "This display does not support ${requestedHz.toInt()} Hz. Available: " +
                    available.joinToString(", ") { "${it.toInt()} Hz" } + "."
            }
            is RequiresAccess -> detail
            is Failed -> detail
        }
}

/**
 * Which mechanism a refresh-rate change should go through on this device.
 *
 * Determined once by the capability layer and stated in the UI, because "why does the
 * refresh rate button need Shizuku on my phone but not on my friend's" is a question
 * the app should be able to answer.
 */
enum class RefreshRateMechanism(val label: String, val explanation: String) {
    /**
     * `WindowManager.LayoutParams.preferredRefreshRate`, which only affects GameCore's
     * own window. Useful for nothing here — a game's window is not ours — so this is
     * never used to claim a device-wide change.
     */
    WINDOW_PREFERENCE(
        "Per-window preference",
        "Android only lets an app set the refresh rate for its own windows. That does " +
            "not change the rate the game runs at.",
    ),

    /**
     * `Settings.System.min_refresh_rate` / `peak_refresh_rate`, the keys the platform's
     * own display settings write. Device-wide and effective, and needs WRITE_SETTINGS —
     * which on most builds is not enough for these two particular keys, hence
     * [SHIZUKU_SETTINGS].
     */
    SYSTEM_SETTINGS(
        "System settings",
        "Writes the same refresh-rate bounds Android's own display settings use.",
    ),

    /**
     * The same keys, written through the elevated shell. The path that actually works
     * on the devices where it matters most, including MediaTek builds where the
     * standard API is accepted and ignored.
     */
    SHIZUKU_SETTINGS(
        "Shizuku",
        "Writes the refresh-rate bounds with ADB-level authority, which is what most " +
            "devices require for a device-wide change.",
    ),

    /** Nothing works here: a single-mode panel, or every path denied. */
    NONE(
        "Not available",
        "This device does not expose a way for an app to change the refresh rate.",
    ),
}
