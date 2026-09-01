package com.gamecore.core.model

import com.gamecore.core.common.AccessLevel

/**
 * Whether GameCore can do a thing on this device, and if not, what is in the way.
 *
 * Four states rather than a boolean, because "no" has three different answers and they lead to three
 * different buttons. [REQUIRES_PERMISSION] gets a button that opens a settings screen;
 * [REQUIRES_SHIZUKU] gets a link to the Shizuku setup page; [UNSUPPORTED] gets a sentence and no
 * button at all, because there is nothing the user can do and offering them an action implies
 * otherwise.
 *
 * §17 names these four as the states the optimization engine must report. They are also what the
 * capability checker returns for every feature the dashboard can offer, so a control that cannot
 * work is disabled with a reason before it is ever pressed rather than failing on press.
 */
enum class CapabilityStatus(val label: String, val isUsable: Boolean) {

    AVAILABLE("Available", true),

    /** The elevated shell would do it. Shizuku is not connected, or the user has it switched off. */
    REQUIRES_SHIZUKU("Needs Shizuku", false),

    /** A permission the user can grant in Settings: usage access, WRITE_SETTINGS, DND policy. */
    REQUIRES_PERMISSION("Needs permission", false),

    /**
     * The device or the platform does not offer it at all — a single-mode panel, a phone with no
     * flash, an OS version without the API. Not a failure and not fixable.
     */
    UNSUPPORTED("Not supported on this device", false),
    ;

    /** True when telling the user about it should come with something to press. */
    val isActionable: Boolean get() = this == REQUIRES_SHIZUKU || this == REQUIRES_PERMISSION
}

/**
 * What this device will actually let GameCore do, established once and then cached.
 *
 * Every field is the answer to a question some screen asks, and none of them is a guess: each is
 * produced by trying the cheap version of the operation — reading the panel's mode list, checking an
 * app-op, asking the shell whether it is alive — rather than by inspecting `Build.MANUFACTURER` and
 * assuming. §24B's MediaTek note is the reason: a device that reports a 120 Hz mode and silently
 * refuses to leave 60 is indistinguishable from a working one until something reads the rate back.
 *
 * Held as one immutable object so a screen cannot see half-refreshed state, and re-derived rather
 * than mutated when the user grants a permission or Shizuku connects.
 */
data class DeviceCapabilities(
    val shizuku: ShizukuState,
    val root: RootState,
    val effectiveAccess: AccessLevel,

    // ------------------------------------------------------------------------- display
    val refreshRateMechanism: RefreshRateMechanism,
    val supportedRefreshRates: List<Float>,
    /** True when the panel offers more than one rate. A 60 Hz-only device is §31's edge case. */
    val hasVariableRefreshRate: Boolean,
    val isKnownUnreliableRefreshChipset: Boolean,
    val brightnessControl: CapabilityStatus,
    val rotationControl: CapabilityStatus,
    val screenTimeoutControl: CapabilityStatus,

    // ------------------------------------------------------------------------ metrics
    val cpuUsageReadable: Boolean,
    val perCoreFrequenciesReadable: Boolean,
    val thermalStatusSupported: Boolean,
    val temperatureReadable: Boolean,
    val frameRate: FrameRateCapability,

    // ------------------------------------------------------------------------ features
    val gameDetection: CapabilityStatus,
    val overlay: CapabilityStatus,
    val doNotDisturbControl: CapabilityStatus,
    val volumeControl: CapabilityStatus,
    val torch: CapabilityStatus,
    val screenCapture: CapabilityStatus,
    val batterySaverControl: CapabilityStatus,
    val animationScaleControl: CapabilityStatus,
    val checkedAtMillis: Long,
) {

    /** True when the elevated paths are live, so the UI can stop offering to set them up. */
    val hasElevatedAccess: Boolean get() = shizuku.isUsable

    /**
     * The §24A.13 case: the sandbox may not be intact.
     *
     * Surfaced rather than acted on. GameCore does not refuse to run on a rooted device — that would
     * be punishing the user for their own phone — but it does say so next to the encrypted-storage
     * claim, because on a device with a root shell that claim is weaker than it looks.
     */
    val rootWarning: String? get() = root.warning

    /**
     * How many of the profile-relevant controls are usable right now.
     *
     * Used for the one-line summary on the Shizuku screen: "3 of 8 optimizations available". A
     * fraction is honest in a way "Shizuku: connected" is not, since connection does not imply the
     * device honours what gets written through it.
     */
    val usableControlCount: Int
        get() = listOf(
            brightnessControl, rotationControl, screenTimeoutControl,
            doNotDisturbControl, volumeControl, batterySaverControl,
            animationScaleControl,
        ).count { it.isUsable } + if (refreshRateMechanism != RefreshRateMechanism.NONE) 1 else 0

    val controlCount: Int get() = 8

    companion object {
        /**
         * What every screen sees before the first check finishes.
         *
         * Deliberately pessimistic: nothing is supported until something has established that it is.
         * The alternative — assuming availability and correcting on the first failure — shows the
         * user a control that disappears, which reads as a bug.
         */
        val UNKNOWN = DeviceCapabilities(
            shizuku = ShizukuState.RUNNING_PERMISSION_UNKNOWN,
            root = RootState.NOT_DETECTED,
            effectiveAccess = AccessLevel.NORMAL,
            refreshRateMechanism = RefreshRateMechanism.NONE,
            supportedRefreshRates = emptyList(),
            hasVariableRefreshRate = false,
            isKnownUnreliableRefreshChipset = false,
            brightnessControl = CapabilityStatus.UNSUPPORTED,
            rotationControl = CapabilityStatus.UNSUPPORTED,
            screenTimeoutControl = CapabilityStatus.UNSUPPORTED,
            cpuUsageReadable = false,
            perCoreFrequenciesReadable = false,
            thermalStatusSupported = false,
            temperatureReadable = false,
            frameRate = FrameRateCapability.Unavailable(FrameRateCapability.NO_GAME_SELECTED),
            gameDetection = CapabilityStatus.REQUIRES_PERMISSION,
            overlay = CapabilityStatus.REQUIRES_PERMISSION,
            doNotDisturbControl = CapabilityStatus.REQUIRES_PERMISSION,
            volumeControl = CapabilityStatus.UNSUPPORTED,
            torch = CapabilityStatus.UNSUPPORTED,
            screenCapture = CapabilityStatus.UNSUPPORTED,
            batterySaverControl = CapabilityStatus.REQUIRES_SHIZUKU,
            animationScaleControl = CapabilityStatus.REQUIRES_SHIZUKU,
            checkedAtMillis = 0L,
        )
    }
}
