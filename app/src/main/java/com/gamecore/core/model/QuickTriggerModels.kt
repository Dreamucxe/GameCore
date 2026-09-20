package com.gamecore.core.model

/**
 * The shortcut that opens GameCore, and an honest account of what each way of firing it costs.
 *
 * The hard fact this whole feature is built around: **an ordinary Android application cannot intercept
 * hardware buttons globally.** Key events go to the focused window, and nothing GameCore can declare in
 * its manifest changes that. So there is no "double-tap volume up from anywhere" option here that works
 * by itself — there are five real mechanisms, each with a real requirement, each labelled with it.
 *
 * Every method below either works with no extra grant and says where it works, or needs one specific
 * thing and says which. None of them uses a hidden API, a reflected system service or root.
 */
enum class QuickTriggerMethod(
    val label: String,
    val requirement: TriggerRequirement,
    /** What the user is actually agreeing to, shown under the option before they pick it. */
    val explanation: String,
) {
    VOLUME_UP_DOUBLE(
        label = "Volume Up ×2",
        requirement = TriggerRequirement.FOCUSED_WINDOW,
        explanation = "Press Volume Up twice quickly. Android delivers volume keys to whichever " +
            "window has focus, so on its own this works while GameCore is on screen — not from " +
            "inside another app. Turning on GameCore's accessibility service lifts that limit, and " +
            "the settings screen says so next to the switch.",
    ),
    VOLUME_DOWN_DOUBLE(
        label = "Volume Down ×2",
        requirement = TriggerRequirement.FOCUSED_WINDOW,
        explanation = "The same as Volume Up ×2, on the other key. Useful if a game already uses " +
            "Volume Up for something.",
    ),
    VOLUME_BOTH(
        label = "Volume Up + Down",
        requirement = TriggerRequirement.FOCUSED_WINDOW,
        explanation = "Hold both volume keys together. Harder to press by accident than a double " +
            "tap, and subject to the same focus rule. Note that many devices take a screenshot on " +
            "Volume Down plus Power, not this combination, so the two do not collide.",
    ),
    SHAKE(
        label = "Shake the device",
        requirement = TriggerRequirement.ACCELEROMETER_SERVICE,
        explanation = "Shake sharply twice. This one does work from inside other apps, because the " +
            "accelerometer is readable from a foreground service rather than from the focused " +
            "window — so GameCore keeps a notification in the shade for as long as it is armed. " +
            "It needs a device with an accelerometer.",
    ),
    QUICK_TILE(
        label = "Quick Settings tile",
        requirement = TriggerRequirement.TILE_ADDED,
        explanation = "Adds a GameCore tile to the Quick Settings panel. One pull-down and one tap " +
            "from anywhere, with no permission, no service and no battery cost. The tile has to be " +
            "dragged into the active row once from the panel's edit screen.",
    ),
    FLOATING_BUTTON(
        label = "Floating button",
        requirement = TriggerRequirement.OVERLAY_PERMISSION,
        explanation = "GameCore's existing floating button, which is already a shortcut to the same " +
            "panel. Listed here so the trigger settings account for every way in. Needs the " +
            "draw-over-other-apps permission.",
    ),
    ;

    /** True for the three volume combinations, which share one detector. */
    val isKeyBased: Boolean
        get() = this == VOLUME_UP_DOUBLE || this == VOLUME_DOWN_DOUBLE || this == VOLUME_BOTH
}

/** What a method needs before it can fire. Each maps to a specific, checkable device state. */
enum class TriggerRequirement(val label: String) {
    /** Works only while a GameCore window has input focus. No permission, no service. */
    FOCUSED_WINDOW("Works on GameCore's screens"),

    /** Needs an accelerometer and a foreground service to watch it. */
    ACCELEROMETER_SERVICE("Needs a background service"),

    /** Needs the user to place the tile in Quick Settings. */
    TILE_ADDED("Needs the tile added once"),

    /** Needs SYSTEM_ALERT_WINDOW. */
    OVERLAY_PERMISSION("Needs display over other apps"),
}

/** What firing the trigger does. All three reuse the Game Mode that already exists. */
enum class QuickTriggerAction(val label: String, val description: String) {
    TOGGLE_PANEL(
        label = "Toggle the GameCore panel",
        description = "Opens the floating control panel if it is closed, closes it if it is open.",
    ),
    OPEN_PANEL(
        label = "Open the GameCore panel",
        description = "Always opens the floating control panel, never closes it.",
    ),
    OPEN_APP(
        label = "Open GameCore",
        description = "Brings the GameCore app to the front.",
    ),
}

/**
 * Whether one method can actually run right now, and what is missing if it cannot.
 *
 * Built from live device state — the sensor list, the permission, the accessibility setting — and never
 * from a guess. A method whose requirement cannot be met on this device at all is reported as
 * [Status.UNSUPPORTED] and is not offered.
 */
data class TriggerAvailability(
    val method: QuickTriggerMethod,
    val status: Status,
    val detail: String,
) {
    enum class Status(val label: String) {
        AVAILABLE("AVAILABLE"),
        NEEDS_PERMISSION("REQUIRES PERMISSION"),
        NEEDS_SETUP("NEEDS SETUP"),
        UNSUPPORTED("NOT SUPPORTED"),
    }

    val isUsable: Boolean get() = status == Status.AVAILABLE
}

/**
 * The user's trigger configuration, held inside [AppSettings].
 *
 * Its own type rather than six loose fields because the settings screen edits it as a unit and the
 * detector reads it as a unit, and because a trigger whose method and window disagree is not a state
 * worth being able to represent.
 */
data class QuickTriggerSettings(
    val enabled: Boolean = false,
    val method: QuickTriggerMethod = QuickTriggerMethod.VOLUME_UP_DOUBLE,
    val action: QuickTriggerAction = QuickTriggerAction.TOGGLE_PANEL,

    /**
     * How long the second press may arrive after the first, in milliseconds.
     *
     * Too short and a deliberate double tap is missed; too long and two ordinary volume adjustments
     * become a trigger. The default is the same order as the platform's own double-tap timeout.
     */
    val windowMillis: Long = DEFAULT_WINDOW,

    /** How hard a shake has to be, 0 (a nudge) to 100 (a deliberate jolt). */
    val shakeSensitivity: Int = DEFAULT_SHAKE_SENSITIVITY,

    /**
     * Whether the volume keys keep their normal job as well.
     *
     * On by default. Swallowing the second press means a user who wanted to turn the volume down twice
     * gets one step and a panel, which is worse than the panel appearing alongside a volume change.
     */
    val passThroughKeys: Boolean = true,
) {
    fun normalised(): QuickTriggerSettings = copy(
        windowMillis = windowMillis.coerceIn(MIN_WINDOW, MAX_WINDOW),
        shakeSensitivity = shakeSensitivity.coerceIn(0, 100),
    )

    /**
     * The acceleration, in m/s² above gravity, that counts as a shake at this sensitivity.
     *
     * Linear between the two ends: at 0 it takes a deliberate jolt, at 100 a firm flick. Both ends were
     * chosen above the noise floor of a phone resting on a desk, which sits under 0.3 m/s².
     */
    val shakeThreshold: Float
        get() = MAX_SHAKE_THRESHOLD - (MAX_SHAKE_THRESHOLD - MIN_SHAKE_THRESHOLD) *
            (shakeSensitivity.coerceIn(0, 100) / 100f)

    companion object {
        const val DEFAULT_WINDOW = 500L
        const val MIN_WINDOW = 200L
        const val MAX_WINDOW = 1_500L

        const val DEFAULT_SHAKE_SENSITIVITY = 50

        /** At sensitivity 100. A firm flick of the wrist. */
        const val MIN_SHAKE_THRESHOLD = 6f

        /** At sensitivity 0. A deliberate jolt, roughly 1.5 g above rest. */
        const val MAX_SHAKE_THRESHOLD = 15f

        val DEFAULT = QuickTriggerSettings()
    }
}
