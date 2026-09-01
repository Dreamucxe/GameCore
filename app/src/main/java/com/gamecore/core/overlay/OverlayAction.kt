package com.gamecore.core.overlay

/**
 * What the control panel of §7 can do, as a closed set.
 *
 * An enum rather than a list of lambdas built at the call site, for two reasons. The panel has to render
 * an action that is *not available on this device* differently from one that is switched off — §24's
 * rule, and the reason [OverlayPanelState.unavailable] exists — and a lambda cannot be asked whether it
 * would work. And the service executing these has to handle every one of them: a `when` over an enum
 * with no `else` fails to compile when an action is added and forgotten, which is exactly the "button
 * that does nothing" §32 rules out.
 */
enum class OverlayAction(val label: String) {
    /** Show or hide the stats pill. */
    PILL("Stats"),

    CROSSHAIR("Crosshair"),

    HUD("HUD"),

    SCREENSHOT("Screenshot"),

    /** Start or stop screen recording. Needs its own consent flow; see §24B. */
    RECORD("Record"),

    FLASHLIGHT("Torch"),

    /** Do Not Disturb. Needs notification-policy access. */
    DO_NOT_DISTURB("Silence"),

    ROTATION_LOCK("Rotation"),

    /** Ends the tracked session and restores whatever the profile changed. */
    STOP_SESSION("End session"),

    /** Brings GameCore to the front. */
    OPEN_APP("Open"),
    ;

    /** True for the actions whose button reads as on/off rather than as a one-shot. */
    val isToggle: Boolean
        get() = this == PILL || this == CROSSHAIR || this == HUD || this == RECORD ||
            this == FLASHLIGHT || this == DO_NOT_DISTURB || this == ROTATION_LOCK
}

/**
 * The two things in the panel that are set to a level rather than switched on and off.
 *
 * Volume and brightness are the adjustments a player actually makes mid-game — a loud cutscene, a dark
 * room — and they are the reason the panel needs something other than [OverlayAction]. An action is a
 * tap with one outcome; a level is a value between 0 and 100 that has to be read before it can be shown
 * and written when the finger lifts. Modelling them as actions would mean a "volume up" button that
 * takes six taps to cross the range, which is what the system's own volume keys already do better.
 *
 * Kept as an enum for the same reason [OverlayAction] is one: the service's `when` over it has no
 * `else`, so a level added here and not wired up fails to compile.
 */
enum class OverlayLevel(val label: String) {
    /** `STREAM_MUSIC` — the stream a game's audio plays on. */
    VOLUME("Volume"),

    /** `screen_brightness`, which needs WRITE_SETTINGS or the elevated shell. */
    BRIGHTNESS("Brightness"),
}

/**
 * Where one [OverlayLevel] stands: the level now, or why it cannot be used.
 *
 * [percent] is null when the value could not be read at all, and [reason] is set when it cannot be
 * *changed* — two different facts, because brightness always reads and does not always write. A slider
 * with neither is drawn dimmed with the reason beside it rather than left to move and do nothing.
 */
data class OverlayLevelState(
    val percent: Int? = null,
    val reason: String? = null,
) {
    val isUsable: Boolean get() = percent != null && reason == null

    companion object {
        val UNKNOWN = OverlayLevelState()
    }
}

/**
 * Everything the control panel draws, gathered by the service that owns it.
 *
 * [unavailable] carries a *reason* per action rather than a boolean, because a greyed-out button with no
 * explanation is the thing §24 objects to most: the user is left unable to tell a missing permission
 * from a device that cannot do it at all. The panel shows the reason under the label.
 */
data class OverlayPanelState(
    /** The game being played, or empty when the panel was opened outside a session. */
    val gameLabel: String = "",
    /** Session duration, already formatted, or null when nothing is being recorded. */
    val sessionElapsed: String? = null,
    /** The actions that are currently on. Only meaningful for [OverlayAction.isToggle]. */
    val active: Set<OverlayAction> = emptySet(),
    /** Why an action cannot be used, keyed by action. Absent means usable. */
    val unavailable: Map<OverlayAction, String> = emptyMap(),
    /** The sliders' levels. An absent entry is [OverlayLevelState.UNKNOWN] — not yet read. */
    val levels: Map<OverlayLevel, OverlayLevelState> = emptyMap(),
) {
    fun isActive(action: OverlayAction): Boolean = action in active

    fun reasonFor(action: OverlayAction): String? = unavailable[action]

    fun isUsable(action: OverlayAction): Boolean = action !in unavailable

    fun levelFor(level: OverlayLevel): OverlayLevelState =
        levels[level] ?: OverlayLevelState.UNKNOWN

    companion object {
        val EMPTY = OverlayPanelState()
    }
}
