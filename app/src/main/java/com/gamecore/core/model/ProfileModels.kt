package com.gamecore.core.model

/**
 * Everything GameCore will do when one game starts, and undo when it stops.
 *
 * Every adjustable field is nullable, and null means *leave it alone*. That is not the same
 * as a default value: a profile with `brightnessPercent = null` does not touch brightness at
 * all, while `brightnessPercent = 50` sets it and records what it was so it can be put back.
 * A non-nullable field with a sentinel default would make "don't change this" impossible to
 * express, and the app would end up writing settings the user never asked it to.
 *
 * [packageName] is the identity. A profile survives its game being uninstalled — the Games
 * screen shows it as "no longer installed" rather than deleting the user's configuration
 * behind their back — so nothing here assumes the package resolves.
 */
data class GameProfile(
    val packageName: String,
    /** Sanitised at the boundary; a package label is another developer's text. */
    val label: String,
    val isEnabled: Boolean = true,

    // -------------------------------------------------------------- display
    /** Hz, or null to leave the refresh rate alone. Checked against the panel before use. */
    val targetRefreshRate: Float? = null,
    val brightnessPercent: Int? = null,
    val rotationLock: ScreenOrientationLock? = null,
    /** Milliseconds, or null. A profile that raises this restores it on exit. */
    val screenTimeoutMillis: Long? = null,

    // ---------------------------------------------------------------- audio
    val mediaVolumePercent: Int? = null,
    val enableDoNotDisturb: Boolean = false,

    // -------------------------------------------------------------- overlay
    val showFloatingButton: Boolean = true,
    val showPerformancePill: Boolean = false,
    val showCrosshair: Boolean = false,
    /** Which saved HUD layout to raise, or null for none. */
    val hudLayoutId: Long? = null,
    val crosshairPresetId: Long? = null,

    // ---------------------------------------------------------------- colour
    /**
     * Which saved colour preset to apply while this game runs, or null to leave the screen's
     * colour alone.
     *
     * An id rather than an embedded [ColorCorrection], so editing "Night" changes what every
     * profile using it does — the alternative is fourteen columns copied into each profile and
     * a user who edits a preset and cannot work out why their game still looks the same.
     */
    val colorPresetId: Long? = null,

    // ---------------------------------------------------------- display size
    /**
     * The logical display size to stretch to while this game runs, or null to leave the
     * display's own size alone.
     *
     * Pixels rather than an [AspectPreset], because a ratio is not a size until there is a
     * panel to compute it against: "4:3" is 1080×1440 on one phone and 1440×1920 on another,
     * and a profile that stored the ratio would have to re-derive it on every apply against a
     * display that may already be overridden. Storing what was actually asked for keeps the
     * request unambiguous, and [AspectPreset.of] turns it back into a chip label for the editor.
     *
     * The most consequential field in this class. A `wm size` override outlives a reboot, so a
     * profile carrying one is a promise to put it back — see
     * [com.gamecore.domain.display.DisplaySizeController].
     */
    val displaySize: DisplaySize? = null,

    // ---------------------------------------------------------- optimization
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    /** Only attempted when Shizuku is connected; skipped, not failed, when it is not. */
    val useShizukuOptimizations: Boolean = false,
    val trackSession: Boolean = true,

    /**
     * Close background apps when this game starts, so it has the memory they were holding.
     *
     * Off by default, and the only field in this class whose effect is on *other* applications
     * rather than on a device setting — which is why it is opt-in per game rather than a global
     * switch, and why [com.gamecore.domain.memory.BackgroundAppReclaimer] reports every app it
     * left running along with the reason it left it.
     *
     * Needs Shizuku to do the job properly: without it the platform is asked to close apps and
     * does not say what it did, so the report says "asked" rather than "closed". The profile
     * editor says so before the switch is turned on.
     */
    val freeRamOnLaunch: Boolean = false,

    /**
     * Which group of CPU cores to restrict this game's process to, or null to leave it to Android.
     *
     * Nullable and null by default, and there is deliberately no `LEAVE_TO_OS` member on
     * [CpuAffinityPreset] to store instead. The invariant at the top of this class is the whole
     * reason: null already means *leave it alone* for every other field here, and a sentinel enum
     * value would give the same idea a second spelling that the editor, the applier and the database
     * would each have to remember to check for.
     *
     * The second field whose effect is on a process rather than a device setting, and the only one
     * whose effect is on the *game's own* process. Marked experimental everywhere it is presented,
     * with [CpuAffinityPreset.HONESTY] beside it: it may reduce stutter and it may make things worse,
     * it does not make the device faster, and a preset the device's core layout cannot express is not
     * offered at all — see [com.gamecore.domain.cpu.CpuAffinityController].
     */
    val cpuAffinity: CpuAffinityPreset? = null,
) {
    /** True when applying this would write nothing, so the UI can say so plainly. */
    val changesNothing: Boolean
        get() = targetRefreshRate == null &&
            brightnessPercent == null &&
            rotationLock == null &&
            screenTimeoutMillis == null &&
            mediaVolumePercent == null &&
            !enableDoNotDisturb &&
            // A colour preset counts: it writes device settings and restores them on exit,
            // which is the same kind of change as brightness and not the same kind as an
            // overlay the profile happens to raise.
            colorPresetId == null &&
            // A display size counts twice over: it writes device state and it is the one change
            // here that survives a reboot, so a profile carrying one is never a no-op.
            displaySize == null &&
            performanceMode == PerformanceMode.BALANCED &&
            !useShizukuOptimizations &&
            // Leaves nothing behind to restore, so it is not a change in the sense the rest of
            // this list means. It is still emphatically not nothing: a profile whose only setting
            // is this one closes the user's background apps, and a UI that called that "changes
            // nothing" would be lying about the one thing here that touches another app.
            !freeRamOnLaunch &&
            // Counts, and the honest reading of it is the same as the one above: it writes something
            // and records the previous value for restore. Whether it helps is a separate question
            // from whether it changes anything, and this property answers the second one.
            cpuAffinity == null

    companion object {
        /** A new profile for a game the user just picked: overlay on, nothing written. */
        fun forGame(packageName: String, label: String) = GameProfile(
            packageName = packageName,
            label = label,
        )
    }
}

/**
 * The orientation a profile pins, as a user would describe it.
 *
 * Distinct from [com.gamecore.core.system.ScreenRotation], which is the platform's
 * natural-orientation-relative index. This is what the user chose; the controller maps it to
 * whichever `user_rotation` value means that on this device.
 */
enum class ScreenOrientationLock(val label: String) {
    CURRENT("Whatever it is when the game starts"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    ;
}

/**
 * The four performance presets, with what each one actually does written down.
 *
 * [explanation] is shown next to every one of these in the UI, and it is deliberately a list
 * of settings rather than a promise about frame rates. No mode here frees memory or "boosts"
 * anything — Android does not expose a way for an app to make a device faster, and the ones
 * that claim to are writing settings and taking credit for the scheduler's own behaviour.
 *
 * Closing background applications is [GameProfile.freeRamOnLaunch]'s job and not a mode's, on
 * purpose. It acts on the user's *other* apps rather than on this device setting or that one,
 * it is off unless it is asked for, and it reports what it measured afterwards — so it is a
 * switch the user throws deliberately rather than a side effect of picking "Performance".
 */
enum class PerformanceMode(
    val label: String,
    val explanation: String,
) {
    BALANCED(
        label = "Balanced",
        explanation = "Changes nothing. The device's own scheduler and thermal governor " +
            "decide, which is what they are tuned to do.",
    ),

    PERFORMANCE(
        label = "Performance",
        explanation = "Pins the display to its highest refresh rate, turns off battery " +
            "saver, and switches window animations off so a game's own frames are the only " +
            "thing being drawn. It does not raise CPU or GPU clocks — no app can.",
    ),

    BATTERY_SAVER(
        label = "Battery saver",
        explanation = "Pins the display to its lowest refresh rate and turns Android's " +
            "battery saver on. The display is usually the largest single draw while gaming, " +
            "so this is the one setting that measurably extends a session.",
    ),

    CUSTOM(
        label = "Custom",
        explanation = "Applies exactly the settings you chose in this profile and nothing " +
            "else.",
    ),
    ;

    /** True for modes that need the elevated shell on most devices. */
    val needsElevatedShellUsually: Boolean
        get() = this == PERFORMANCE || this == BATTERY_SAVER
}
