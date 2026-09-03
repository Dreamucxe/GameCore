package com.gamecore.core.overlay

import com.gamecore.core.model.AspectChoice
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField

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

    /**
     * Screen colour correction: on/off for whatever the panel's sliders and chips have set.
     *
     * A toggle rather than a one-shot even though it opens something, because the state it reports is
     * the one the user cares about — *is my screen corrected right now* — and that is exactly what the
     * filled plate behind Silence and Rotation means everywhere else in this grid. The tap opens the
     * full editor and a long press drops the saved presets into the panel; neither of those is a state,
     * and neither is what the button has to show.
     */
    COLOR("Colour"),

    /**
     * The display's own size: the shapes this panel can be stretched to, and the way back.
     *
     * A toggle for the same reason [COLOR] is one, and a plainer one: the filled plate means *this display
     * is not at its native size*, which is the fact a player who finds their screen stretched needs to see
     * without reading anything. The tap opens the chips under the grid rather than a screen — a shape is
     * one of four values, all four are computed from this panel, and each applies as it is tapped. Native
     * is the first of them, so the way out is one tap on a row the tile itself opened.
     */
    ASPECT("Aspect"),

    /** Ends the tracked session and restores whatever the profile changed. */
    STOP_SESSION("End session"),

    /** Brings GameCore to the front. */
    OPEN_APP("Open"),
    ;

    /** True for the actions whose button reads as on/off rather than as a one-shot. */
    val isToggle: Boolean
        get() = this == PILL || this == CROSSHAIR || this == HUD || this == RECORD ||
            this == FLASHLIGHT || this == DO_NOT_DISTURB || this == ROTATION_LOCK ||
            this == COLOR || this == ASPECT

    /**
     * True for the actions that have something to offer a long press.
     *
     * Only [COLOR] does: its presets are a list the user authored, and a second row of chips is the
     * only way to reach them without leaving the game. Declared here rather than checked as
     * `== COLOR` in the panel so the two places that need it — the button's gesture and the row's
     * visibility — cannot disagree.
     *
     * [ASPECT] opens a second row too and is deliberately not here. Its chips are the whole of the
     * control rather than a shortcut into one, so they belong on the tap; a shape hidden behind a held
     * press would be a control the user has to be told about.
     */
    val hasPresets: Boolean get() = this == COLOR
}

/**
 * The things in the panel that are set to a level rather than switched on and off.
 *
 * Volume and brightness are the adjustments a player actually makes mid-game — a loud cutscene, a dark
 * room — and they are the reason the panel needs something other than [OverlayAction]. An action is a
 * tap with one outcome; a level is a value in a range that has to be read before it can be shown and
 * written when the finger lifts. Modelling them as actions would mean a "volume up" button that takes
 * six taps to cross the range, which is what the system's own volume keys already do better.
 *
 * The three colour levels join them because they answer the same kind of question — *more or less of
 * this, now* — and because they are the three of the fourteen colour values that a player changes
 * mid-game rather than while authoring a preset. Each carries its [ColorField], so its range and its
 * label's format come from the model rather than being restated here: a hue is −180…180 degrees and a
 * saturation is −100…100 percent, and a panel that assumed 0…100 for both would silently refuse the
 * left half of every colour slider. It is `colourField` rather than `field` because inside a property
 * accessor `field` means that property's own backing store, which is not this.
 *
 * Kept as an enum for the same reason [OverlayAction] is one: the service's `when` over it has no
 * `else`, so a level added here and not wired up fails to compile.
 */
enum class OverlayLevel(val label: String, val colourField: ColorField? = null) {
    /** `STREAM_MUSIC` — the stream a game's audio plays on. */
    VOLUME("Volume"),

    /** `screen_brightness`, which needs WRITE_SETTINGS or the elevated shell. */
    BRIGHTNESS("Brightness"),

    SATURATION("Saturation", ColorField.SATURATION),

    CONTRAST("Contrast", ColorField.CONTRAST),

    HUE("Hue", ColorField.HUE),
    ;

    /** True for the levels that write a colour correction rather than a device setting. */
    val isColour: Boolean get() = colourField != null

    /** The values the slider may take: the field's own range, or a plain percentage. */
    val range: IntRange get() = colourField?.range ?: PERCENT_RANGE

    /**
     * The readout beside the slider.
     *
     * Delegated to [ColorField.format] for the colour levels so a negative saturation reads "−40%" and
     * a rotation reads "+90°", which is the same string the full editor shows for the same value. Two
     * formatters for one number would eventually disagree.
     */
    fun format(value: Int): String = colourField?.format(value) ?: "$value%"

    /**
     * Puts [value] into a correction under this level's field, or hands the correction back untouched.
     *
     * Here rather than as a `when` at the write site so that the field a slider was *drawn* from is the
     * field it *writes* to. Two mappings — one for the range and the label, another for the write — would
     * eventually disagree, and the failure would be a saturation slider quietly editing contrast.
     */
    fun applyTo(correction: ColorCorrection, value: Int): ColorCorrection =
        colourField?.let { correction.with(it, value) } ?: correction

    companion object {
        val PERCENT_RANGE = 0..100
    }
}

/**
 * Where one [OverlayLevel] stands: the level now, why it cannot be used, and what it cannot reach.
 *
 * Three facts rather than two, because the colour levels made a third one possible. [value] is null
 * when the level could not be read at all, and [reason] is set when it cannot be *changed* — two
 * different facts already, because brightness always reads and does not always write. [note] is the
 * third: the slider moves, the value is stored and travels with the preset, and *this* display has no
 * control that can express it. That is not "unusable" — disabling the slider would strand the value
 * wherever the user's last drag left it — and it is not silence either, which is what §24 forbids.
 *
 * A slider with a [reason] is drawn dimmed with the reason beside it rather than left to move and do
 * nothing. A slider with a [note] is drawn live, with the note under it.
 */
data class OverlayLevelState(
    val value: Int? = null,
    val reason: String? = null,
    val note: String? = null,
) {
    val isUsable: Boolean get() = value != null && reason == null

    /** The sentence under the slider, whichever of the two facts there is one for. */
    val detail: String? get() = reason ?: note

    companion object {
        val UNKNOWN = OverlayLevelState()
    }
}

/**
 * One saved colour preset, as a chip.
 *
 * §24A.2 applied to the overlay: the panel draws a name and reports back an id, so the window state
 * does not carry fourteen colour values per preset into a process whose job is to draw a row of chips.
 * The service turns the id back into the preset it came from.
 */
data class OverlayPreset(val id: Long, val name: String)

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
    /** The saved colour presets, in the order the library lists them. Empty until they are read. */
    val presets: List<OverlayPreset> = emptyList(),
    /** Whether the preset chips are showing. Toggled by a long press on [OverlayAction.COLOR]. */
    val presetsExpanded: Boolean = false,
    /**
     * The preset whose values are on screen now, or null for values the user typed themselves.
     *
     * Null is the honest answer for a hand-adjusted correction, and it is the common one: a user who
     * loads "Night" and then drags saturation is no longer on Night. Highlighting the chip they started
     * from would be the panel claiming a preset is applied when it is not.
     */
    val activePresetId: Long? = null,
    /**
     * The shapes this panel can be stretched to, native first, or empty when the size could not be read.
     *
     * Carried as [AspectChoice] rather than as the enum alone because the chip shows both halves — "16:9"
     * and what 16:9 is in pixels on *this* display. Empty is the honest state on a device with no elevated
     * shell: `wm size` is the only way to learn the panel's own size, and a chip row computed from a
     * guessed panel would offer sizes this display cannot take.
     */
    val aspects: List<AspectChoice> = emptyList(),
    /** Whether the shape chips are showing. Toggled by a tap on [OverlayAction.ASPECT]. */
    val aspectsExpanded: Boolean = false,
    /**
     * The shape on screen now, or null when the active size is not one of [aspects].
     *
     * Null is the ordinary answer for a size typed into a profile by hand, and it leaves every chip
     * unfilled — which is right. A row that highlighted the nearest shape would be claiming a preset is
     * in force when the display is running something else.
     */
    val activeAspect: AspectPreset? = null,
    /** What the display is doing when no chip matches it, or when the size could not be read at all. */
    val aspectNote: String? = null,
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
