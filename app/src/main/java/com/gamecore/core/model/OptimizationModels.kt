package com.gamecore.core.model

import com.gamecore.core.common.AccessLevel

/**
 * One thing the optimization engine can change, with what it actually does written down.
 *
 * [explanation] is not marketing copy and it is not optional — §17 requires that every optimization
 * explain itself, and the honest explanation for most of these is "it writes a setting you could
 * write yourself in Settings, from one place, and puts it back afterwards". Nothing in this list
 * frees memory or raises a clock, because Android does not let an app do either and the apps that
 * claim to are writing settings and taking credit for the scheduler.
 *
 * Closing background applications is deliberately *not* an action here. Every entry in this enum is
 * a device setting with a restore point, applied on launch and put back on exit; a closed app has no
 * restore point, cannot be reopened by GameCore, and is the user's own to decide about — so it lives
 * behind its own per-profile switch ([GameProfile.freeRamOnLaunch]) and reports itself separately,
 * rather than arriving as one item in a profile's applied list.
 *
 * [requiredAccess] is the *usual* requirement, not a verdict. The capability checker asks the device
 * rather than trusting this field: `screen_brightness` is writable with WRITE_SETTINGS on stock
 * Android and blocked on some OEM builds, and `peak_refresh_rate` is occasionally writable without
 * Shizuku. This is what to tell the user before the check has run, not instead of it.
 */
enum class OptimizationAction(
    val label: String,
    val explanation: String,
    val requiredAccess: AccessLevel,
) {

    PIN_PEAK_REFRESH_RATE(
        label = "Pin highest refresh rate",
        explanation = "Writes the display's minimum and maximum refresh rate to the same value, " +
            "which is what stops Android dropping to 60 Hz when it decides the screen is static. " +
            "It cannot make a panel exceed a rate it has.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    PIN_LOWEST_REFRESH_RATE(
        label = "Pin lowest refresh rate",
        explanation = "Holds the display at its lowest rate. The panel is usually the single " +
            "largest power draw during a game, so this is the one change that measurably extends " +
            "a session.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    RELEASE_REFRESH_RATE(
        label = "Release refresh rate",
        explanation = "Clears both bounds so the platform chooses again, which is what it was " +
            "doing before GameCore touched it.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    DISABLE_ANIMATIONS(
        label = "Switch off window animations",
        explanation = "Sets the three animation scales to zero, exactly as Developer options " +
            "does. The system stops compositing window transitions, so those frames are not " +
            "drawn. It changes nothing inside the game.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    ENABLE_BATTERY_SAVER(
        label = "Turn on battery saver",
        explanation = "Switches on Android's own battery saver — the same toggle as in Settings. " +
            "GameCore does not invent a power-saving mode of its own.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    DISABLE_BATTERY_SAVER(
        label = "Turn off battery saver",
        explanation = "Battery saver caps the refresh rate and throttles background work on most " +
            "builds, so a performance profile turns it off rather than fighting it.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    SET_BRIGHTNESS(
        label = "Set brightness",
        explanation = "Writes a manual brightness level and switches automatic brightness off, " +
            "because a manual level with the light sensor still in charge lasts until the next " +
            "time the sensor disagrees.",
        requiredAccess = AccessLevel.NORMAL,
    ),

    LOCK_ROTATION(
        label = "Lock orientation",
        explanation = "Turns off auto-rotate and pins the orientation, so a game that supports " +
            "both does not flip mid-match.",
        requiredAccess = AccessLevel.NORMAL,
    ),

    EXTEND_SCREEN_TIMEOUT(
        label = "Extend screen timeout",
        explanation = "Raises how long the screen stays on without a touch, for games with long " +
            "cut-scenes or idle phases.",
        requiredAccess = AccessLevel.NORMAL,
    ),

    SET_MEDIA_VOLUME(
        label = "Set media volume",
        explanation = "Sets the media stream to a level, so a game does not start at whatever " +
            "volume the last video left behind.",
        requiredAccess = AccessLevel.NORMAL,
    ),

    ENABLE_DO_NOT_DISTURB(
        label = "Turn on Do Not Disturb",
        explanation = "Uses the platform's notification-policy API to silence interruptions for " +
            "the length of the session, then puts the previous mode back.",
        requiredAccess = AccessLevel.NORMAL,
    ),

    APPLY_COLOR_CORRECTION(
        label = "Apply colour correction",
        explanation = "Writes the display keys a colour preset maps onto — the night-display " +
            "white point, the colour mode, the colour-vision filter, extra dimming, inversion — " +
            "and puts each of them back when the game closes. Android exposes no per-channel " +
            "gain, gamma, contrast or hue rotation to any app at any privilege level GameCore " +
            "uses, so a preset's remaining values are reported as unreachable on this device " +
            "rather than quietly dropped.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    SET_DISPLAY_SIZE(
        label = "Set display size",
        explanation = "Overrides the logical size the display reports to apps — the same thing " +
            "`wm size` does from a shell. A game handed a shorter logical display renders a " +
            "shorter frame, and the compositor spreads that frame across the whole panel. It " +
            "stretches the image: it is not a wider field of view, and no app can give a game " +
            "one. The override outlives a reboot, so GameCore records the size the display had " +
            "before it wrote one and puts it back when the session ends.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),

    SET_CPU_AFFINITY(
        label = "Choose which cores the game runs on",
        explanation = "Restricts the game's process to one group of CPU cores while it runs — the " +
            "same thing `taskset` does from a shell — and puts the previous assignment back " +
            "afterwards. " + CpuAffinityPreset.HONESTY + " Experimental, and the only action here " +
            "whose subject is a process rather than a device setting: the assignment dies with the " +
            "game, so a session GameCore does not get to finish leaves nothing behind.",
        requiredAccess = AccessLevel.SHIZUKU,
    ),
    ;

    /** True for the actions a profile can request; the rest are undo steps. */
    val isProfileAction: Boolean
        get() = this != RELEASE_REFRESH_RATE && this != DISABLE_BATTERY_SAVER

    /**
     * True for the actions the two optimizer tiers carry out from an optimization request.
     *
     * False for two of them, and for the same underlying reason: the manager records a previous
     * value for every key an action *could* touch before handing it to a tier, which works only
     * because every other action here touches a fixed set of `settings` keys.
     *
     * [APPLY_COLOR_CORRECTION] touches between one and eight keys depending on what the preset
     * asks for, so capturing all eight would leave restore rows for keys GameCore never wrote —
     * and the restore at session end would then set a user's own colour-vision filter and
     * inversion back to values they never chose. `ColorCorrectionController` records one row per
     * key it actually writes.
     *
     * [SET_DISPLAY_SIZE] touches no `settings` key at all. `wm size` is a window-manager command,
     * so there is nothing for the manager to capture or write; `DisplaySizeController` records the
     * previous override under the repository's non-setting namespace and reads the new size back
     * before reporting it.
     *
     * [SET_CPU_AFFINITY] touches no `settings` key either, and its subject is not the device: it
     * writes a mask onto one running process, which the manager has no pid for and no business
     * finding. `CpuAffinityController` records the mask that process was already on, keyed by the
     * package it belongs to. All three profile steps therefore go through their own controller and
     * the tiers decline all three actions.
     */
    val isEngineAction: Boolean
        get() = this != APPLY_COLOR_CORRECTION &&
            this != SET_DISPLAY_SIZE &&
            this != SET_CPU_AFFINITY
}

/**
 * What happened when the engine tried one [OptimizationAction].
 *
 * There is no case here that means "the call did not throw". [Applied] requires that the value was
 * read back and matched; a write that went through unverified is [Unverified] and is shown as
 * "requested, not confirmed". That distinction is the whole reason this is a sealed type rather than
 * a boolean, and it is the same rule `SettingsWriteOutcome` and [RefreshRateOutcome] follow, because
 * a profile that reports success for a setting the device ignored is the fake-optimizer behaviour
 * §24 prohibits.
 *
 * [Skipped] is a success, not a failure: a profile that leaves brightness alone produces a skip for
 * [OptimizationAction.SET_BRIGHTNESS], and the report says "not requested" rather than listing it as
 * a problem.
 */
sealed interface OptimizationResult {

    val action: OptimizationAction

    /** Written and confirmed by reading it back. [detail] is what the device reports now. */
    data class Applied(
        override val action: OptimizationAction,
        val detail: String,
    ) : OptimizationResult

    /**
     * Written, and the result could not be read back.
     *
     * The MediaTek refresh-rate case and the no-Shizuku settings case both land here. Never counted
     * as applied, and never restored on the assumption that it took effect — the restore point was
     * recorded before the write, so unwinding it is safe either way.
     */
    data class Unverified(
        override val action: OptimizationAction,
        val detail: String,
    ) : OptimizationResult

    /** Written and the device stayed where it was. The honest failure, and the loudest one. */
    data class NotHonoured(
        override val action: OptimizationAction,
        val detail: String,
    ) : OptimizationResult

    /** The profile did not ask for it, or the device is already in that state. */
    data class Skipped(
        override val action: OptimizationAction,
        val reason: String,
    ) : OptimizationResult

    /** Cannot be attempted. [status] is what to offer the user. */
    data class Blocked(
        override val action: OptimizationAction,
        val status: CapabilityStatus,
        val detail: String,
    ) : OptimizationResult

    /** It was attempted and something went wrong. [detail] is the reason, not a stack trace. */
    data class Failed(
        override val action: OptimizationAction,
        val detail: String,
    ) : OptimizationResult

    val isApplied: Boolean get() = this is Applied

    /** True for anything the user should see in red rather than grey. */
    val isProblem: Boolean get() = this is NotHonoured || this is Failed

    val message: String
        get() = when (this) {
            is Applied -> detail
            is Unverified -> "$detail (not confirmed)"
            is NotHonoured -> detail
            is Skipped -> reason
            is Blocked -> detail
            is Failed -> detail
        }
}

/**
 * The outcome of applying one game profile, as one object the UI can render and the log can keep.
 *
 * Kept because "did the profile work" is a question with a per-setting answer. A profile that pinned
 * the refresh rate, set brightness, and could not enable Do Not Disturb because the user has not
 * granted policy access is a partial success, and the notification says which part failed rather than
 * "profile applied" or "profile failed".
 *
 * [overlayRequested] is separate from the results list on purpose: raising the pill and the crosshair
 * is not a device setting, has nothing to restore, and cannot fail for a reason the user needs a
 * capability explanation for.
 */
data class ProfileApplication(
    val packageName: String,
    val profileLabel: String,
    val results: List<OptimizationResult>,
    val overlayRequested: Boolean,
    val sessionTracking: Boolean,
    val appliedAtMillis: Long,
) {
    val appliedCount: Int get() = results.count { it.isApplied }

    val problems: List<OptimizationResult> get() = results.filter { it.isProblem }

    val blocked: List<OptimizationResult> get() = results.filterIsInstance<OptimizationResult.Blocked>()

    /** Requested changes, excluding the ones the profile never asked for. */
    val attemptedCount: Int get() = results.count { it !is OptimizationResult.Skipped }

    val changedNothing: Boolean get() = attemptedCount == 0

    /**
     * One line for the foreground-service notification.
     *
     * Says the count rather than the word "optimized", and names the failure when there is exactly
     * one, because a single named problem is actionable and "2 problems" sends the user hunting.
     */
    fun summary(): String = when {
        changedNothing && overlayRequested -> "Overlay on. No device settings changed."
        changedNothing -> "No device settings changed."
        problems.size == 1 -> "$appliedCount applied · ${problems.first().action.label} failed"
        problems.isNotEmpty() -> "$appliedCount applied · ${problems.size} failed"
        blocked.isNotEmpty() -> "$appliedCount applied · ${blocked.size} unavailable"
        else -> "$appliedCount of $attemptedCount applied"
    }
}

/**
 * Who asked for a change, which settles what happens when the user has since moved the same setting
 * by hand.
 *
 * The distinction is not decoration. A profile re-applying Do Not Disturb over the user's manual
 * "off" is a bug; the Performance screen refusing to pin a refresh rate because the user changed it
 * in Android Settings first would be a worse one. Both go through the same call, so the caller has to
 * say which it is.
 */
enum class ChangeOrigin {

    /** The user asked for this, now. It is made whatever state the device is already in. */
    USER,

    /**
     * GameCore acting on a standing preference by itself — a profile applied because a game came to
     * the foreground.
     *
     * A key the user has changed since GameCore last wrote it is left alone, because the user's
     * change is the more recent instruction of the two.
     */
    AUTOMATIC,
}

/**
 * What came of putting the device back.
 *
 * [outstanding] is the field that matters. A restore that could not finish leaves rows in the restore
 * table, and this is what the dashboard reads to say "3 settings still changed" with a button to
 * retry — the alternative being a device left pinned to 120 Hz by an app that has forgotten it did
 * that.
 *
 * [keptByUser] is neither of the other two on purpose. A setting the user took over during the
 * session is not restored — writing over it is the bug [com.gamecore.domain.optimization.WriteLedger]
 * exists to stop — but counting it as [restored] would claim GameCore put something back that it
 * deliberately left, and counting it as [outstanding] would nag the user to retry a write that fights
 * them.
 */
data class RestoreReport(
    val restored: Int,
    val outstanding: Int,
    val failures: List<String>,
    val keptByUser: Int = 0,
) {
    val isComplete: Boolean get() = outstanding == 0

    val didNothing: Boolean get() = restored == 0 && outstanding == 0 && keptByUser == 0

    /**
     * The sentence for the settings the user took over, or null when they took none.
     *
     * Here rather than in each screen because all three places that report a restore say "put back"
     * or "restored" in their own voice and then need this same clause after it.
     */
    val keptNote: String?
        get() = when (keptByUser) {
            0 -> null
            1 -> "1 setting was left as you set it."
            else -> "$keptByUser settings were left as you set them."
        }

    companion object {
        val NOTHING_TO_DO = RestoreReport(restored = 0, outstanding = 0, failures = emptyList())
    }
}

