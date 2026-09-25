package com.gamecore.core.overlay

import com.gamecore.core.model.AspectChoice
import com.gamecore.core.model.AspectPreset
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField
import com.gamecore.core.model.CrosshairDesign

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

    /**
     * The pinned magnifier: a crop-and-scale loupe drawn in a screen corner (§13, the no-ML half of it).
     *
     * A toggle, and grouped with the drawn overlays above rather than with the capture actions, because that
     * is what it *is* to the user — a window GameCore draws, shown or hidden. But unlike [PILL], [CROSSHAIR]
     * and [HUD] it is not [QuickToggle.isAlwaysAvailable]: the loupe magnifies the live screen, and the only
     * honest source of those pixels is the same `MediaProjection` frame feed the capture actions use. So it
     * carries the capture path's one hard truth — it renders disabled, with a reason, on a build with no
     * projection support — while reading as an overlay everywhere else. It is deliberately *not* macroable;
     * see [macroBehavior].
     */
    MAGNIFIER("Magnifier"),

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

    /**
     * The display's refresh rate: the rates this panel advertises, and the way off a pinned one.
     *
     * A toggle for the same reason [ASPECT] is one, and the plate means the same *kind* of thing: this
     * display is not doing what it would have done on its own. That is worth a lit plate because the cost is
     * invisible otherwise — a panel held at 120 Hz through a menu screen is battery going somewhere the
     * player cannot see.
     *
     * With one difference from every other toggle in this grid, and it is the honest part. The others read
     * their state back from the device each probe. This one cannot: `Display.getRefreshRate()` reports what
     * is being composited *now*, and the platform is entitled to drop a pinned panel to 60 on a static
     * screen, so a low reading is not evidence the pin failed and a high one is not evidence it holds. So
     * the plate is lit from GameCore's own confirmed change and nothing else — see
     * [OverlayPanelState.pinnedRefreshRate]. A rate that was written and could not be read back leaves the
     * plate dark and the row unfilled, because [com.gamecore.core.model.RefreshRateOutcome.AppliedUnverified]
     * is not evidence the panel moved.
     *
     * Opens its chips on the tap rather than behind a long press, again like [ASPECT]: the whole control is
     * the rates this display reports plus the way back off them, they fit in a row, and there is nothing
     * left over to put on a screen.
     */
    REFRESH_RATE("Refresh"),

    /**
     * The shape of this panel: one plate near the button, or two on the screen's edges with the game
     * between them. [com.gamecore.core.model.PanelLayoutStyle], reachable from inside the panel.
     *
     * A toggle rather than a row of chips, unlike the two tiles above, and the difference is the reason
     * this tile exists at all. A shape and a rate are one of several values the *device* reports, so their
     * control has to be a row; a layout is one of two, and the tile's own plate already says which of them
     * is on. A chip row behind a tap would be two taps to change one bit — and the point of putting this in
     * the grid is that changing it currently costs leaving the game, opening GameCore and finding the
     * setting.
     *
     * The plate reads as it does everywhere else in this grid — *this is not what the panel does by
     * default* — so lit means split. Nothing confirms the tap because the panel rearranges itself under
     * the finger that made it: the confirmation is the thing the user asked for.
     *
     * The one action here that changes the window it was tapped in. That is handled where every layout
     * change is handled — the stored preference is written, and the service's own observer rebuilds the
     * open panel — rather than by this tile reaching for a window, so a switch from here and a switch from
     * the settings screen end in the same place.
     *
     * Never [OverlayPanelState.unavailable], and unlike the colour tile that is not a judgement call: this
     * writes a preference of GameCore's own and reads nothing from the device, so there is no elevation,
     * permission or platform version that could refuse it.
     */
    PANEL_LAYOUT("Layout"),

    /** Ends the tracked session and restores whatever the profile changed. */
    STOP_SESSION("End session"),

    /** Brings GameCore to the front. */
    OPEN_APP("Open"),
    ;

    /** True for the actions whose button reads as on/off rather than as a one-shot. */
    val isToggle: Boolean
        get() = this == PILL || this == CROSSHAIR || this == HUD || this == RECORD ||
            this == FLASHLIGHT || this == DO_NOT_DISTURB || this == ROTATION_LOCK ||
            this == COLOR || this == ASPECT || this == REFRESH_RATE || this == PANEL_LAYOUT ||
            this == MAGNIFIER

    /**
     * Which second row a held press on this tile opens, or null for the tiles that have nothing behind one.
     *
     * Declared here, once, rather than checked as `== COLOR` in each place that cares, so the button's
     * gesture and the service's handler cannot disagree about which tiles are holdable — the gate asks
     * whether this is null and the handler asks which row it names, and both get their answer from this
     * line. It was a boolean while [COLOR] was the only holdable tile; naming the row is what lets a second
     * one exist without the gesture becoming a lie on the first.
     *
     * Both tiles that qualify have the same shape of thing behind them: a set the user cannot fit in a grid
     * cell and would otherwise have to leave the game to reach. What they do *not* have is a tap that would
     * be wasted — [COLOR] toggles the correction and [CROSSHAIR] shows and hides the overlay, and those are
     * the actions a player wants most often, so neither may be spent on opening a row.
     *
     * [ASPECT] and [REFRESH_RATE] open a second row too and are deliberately not here. Their chips are the
     * whole of the control rather than a shortcut into one — there is no other thing their tap could mean —
     * so they belong on the tap. A shape or a rate reachable only by holding would be a control the user has
     * to be told about.
     */
    val heldRow: HeldRow?
        get() = when (this) {
            COLOR -> HeldRow.COLOUR_PRESETS
            CROSSHAIR -> HeldRow.CROSSHAIR_QUICK_PICK
            else -> null
        }

    /**
     * How a one-tap macro (§14) replays this action, or null for an action a macro must not carry.
     *
     * A macro is a deterministic composition: tapping it must end in the same place whatever state it was
     * fired from, which is exactly the "forces a target state" the spec asks for. Several kinds of action
     * cannot honour that and are left out — a `when` with no `else`, so a sixteenth action cannot be added
     * without a decision here, the same discipline [isToggle] and [heldRow] keep.
     *
     *  * [FORCE_ON]: the overlays and the device toggles that report a real on/off state through
     *    [OverlayPanelState.active]. A macro forces them *on* — the runner reads the current state and fires
     *    the toggle only when it is off, so replaying a macro twice does not turn a light back off.
     *  * [FIRE_ONCE]: [SCREENSHOT], the one self-contained one-shot; it has no state to force, it just runs.
     *  * `null`: [COLOR], [ASPECT] and [REFRESH_RATE] are grid toggles whose tap opens an editor or a chip
     *    row rather than setting a state — there is nothing to force them *to*. [OPEN_APP] and
     *    [STOP_SESSION] leave the overlay (and the session) behind, so a later step would run against a
     *    surface that is going away. [PANEL_LAYOUT] flips a preference with no natural "on" and would
     *    rearrange the very window the macro was tapped from. [MAGNIFIER] is the one barred for a different
     *    reason: it does force a state, but turning it on may raise the system's `MediaProjection` consent
     *    dialog, and a consent prompt cannot honestly appear in the middle of a silent one-tap replay — a
     *    macro that sometimes stops to ask permission is not the deterministic thing §14 promises. None
     *    belongs in a one-tap set.
     */
    val macroBehavior: MacroBehavior?
        get() = when (this) {
            PILL, CROSSHAIR, HUD, RECORD, FLASHLIGHT, DO_NOT_DISTURB, ROTATION_LOCK ->
                MacroBehavior.FORCE_ON
            SCREENSHOT -> MacroBehavior.FIRE_ONCE
            COLOR, ASPECT, REFRESH_RATE, PANEL_LAYOUT, STOP_SESSION, OPEN_APP, MAGNIFIER -> null
        }

    /** True for the actions a macro may replay; see [macroBehavior] for why the rest are excluded. */
    val isMacroable: Boolean get() = macroBehavior != null

    companion object {
        /**
         * Parse a stored action name back to its [OverlayAction], or null for one this build does not know.
         *
         * Null rather than a fallback, and for [QuickToggle.of]'s reason: a macro is a list of choices, and
         * a name written by a newer build or since removed must drop out of the list rather than resolve to
         * some stand-in the user never chose. Doing the drop here lets the macro layer reason in real
         * [OverlayAction] values and never see a junk string.
         */
        fun of(name: String?): OverlayAction? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How a macro replays one [OverlayAction] (§14). See [OverlayAction.macroBehavior] for which action gets
 * which, and why the actions that get neither are barred from a macro.
 */
enum class MacroBehavior {
    /** A toggle the macro drives *to on*: fired only when the action is currently off, so replay is idempotent. */
    FORCE_ON,

    /** A one-shot the macro simply runs; it carries no state to force. */
    FIRE_ONCE,
}

/**
 * The rows that open on a held press rather than on a tap.
 *
 * An enum rather than the boolean this replaced, because with two of them the gesture gate and the handler
 * need different answers from the same fact: the gate needs to know *whether* to offer the gesture, and the
 * handler needs to know *which* row to open. One nullable property answers both, so a tile can never be
 * holdable and then have the press fall through to another row's toggle.
 *
 * Deliberately not extended to the rows [OverlayAction.ASPECT] and [OverlayAction.REFRESH_RATE] open. Those
 * are tap rows, and folding all four into one enum would suggest the four are interchangeable when the
 * difference between them — whether the tile's tap already means something — is the reason two are held.
 */
enum class HeldRow {
    /** [OverlayAction.COLOR]: the colour presets the user saved. */
    COLOUR_PRESETS,

    /** [OverlayAction.CROSSHAIR]: the active crosshair's design and colour. */
    CROSSHAIR_QUICK_PICK,
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
 * The active crosshair, reduced to what a quick-pick row draws and what it needs to compare against.
 *
 * [OverlayPreset]'s rule applied to a preset the panel *edits* rather than selects, which is the one
 * difference between them and the reason this carries two values instead of a name: the crosshair row's
 * chips are not a list of saved things to load, they are the two fields of one saved thing to change. So it
 * carries the two fields the chips have to fill from — which design is drawn, which colour it is drawn in —
 * and the id the service needs to write the change back to the right row.
 *
 * What it deliberately does not carry is everything else a [com.gamecore.core.model.CrosshairPreset] holds.
 * Size, thickness, gap, rotation, opacity, outline, position and image path are all reachable from the
 * crosshair screen and none of them is a two-tap decision over a game, so putting them in the window state
 * would be fourteen fields crossing a process boundary to draw two rows.
 *
 * [name] is the user's, and the row draws it: a player with three crosshairs saved needs to know which one
 * these chips are about to change. It is already sanitised at the database boundary, and the row caps it on
 * render for the reason §24A gives.
 */
data class OverlayCrosshair(
    val id: Long,
    val name: String,
    val design: CrosshairDesign,
    val colorArgb: Int,
)

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
    /**
     * The rates this panel advertises, or empty when there is nothing to choose from.
     *
     * Empty covers two different situations and the row says which: a panel with one mode, where the user
     * has nothing to decide, and a display whose modes could not be read, where GameCore will not offer a
     * rate it cannot check. [refreshRateNote] carries the distinction; a row of chips assembled from an
     * assumed 60/90/120 would offer rates this panel does not have.
     */
    val refreshRates: List<Float> = emptyList(),
    /** Whether the rate chips are showing. Toggled by a tap on [OverlayAction.REFRESH_RATE]. */
    val refreshRatesExpanded: Boolean = false,
    /**
     * The rate GameCore has *confirmed* the display adopted, or null for every other state there is.
     *
     * The one piece of panel state that is not read back from the device on each probe, and the reason is in
     * [OverlayAction.REFRESH_RATE]'s own KDoc: the platform drops a pinned panel to 60 on a static screen,
     * so what the display reports at probe time cannot distinguish a pin that failed from a screen that is
     * not moving. Reading it would therefore mean unlighting a chip that is still in force, every time the
     * user held still. So this holds the last change GameCore made and verified, and nothing else.
     *
     * Null is the honest answer for a great deal: no change made this session, a change the platform
     * accepted and did not honour, a change that could not be confirmed, and a rate someone else pinned.
     * All of them leave every chip unfilled, which is right — an unfilled row claims nothing.
     */
    val pinnedRefreshRate: Float? = null,
    /** What to say under the rate chips: why there are none, or what a confirmed pin is not. */
    val refreshRateNote: String? = null,
    /**
     * The active crosshair, as much of it as a row of chips needs, or null when there is none to change.
     *
     * §24A.2 again, the rule [OverlayPreset] follows: the row draws a design and a colour and reports back
     * which was tapped, so the window state does not carry a preset's fourteen fields — a size, a rotation,
     * two position fractions and an image path — into a process whose job is to draw two rows of chips. The
     * service turns the tap back into the preset it belongs to.
     *
     * Null is not the same as "the crosshair is off". The overlay's visibility is [OverlayAction.CROSSHAIR]'s
     * own toggle and lives in [active]; this is null when there is no *preset* to edit, which on a device
     * where the defaults seeded is close to impossible and is still the state the row must handle rather than
     * draw a design row for a crosshair that does not exist.
     */
    val crosshair: OverlayCrosshair? = null,
    /** Whether the crosshair chips are showing. Toggled by a long press on [OverlayAction.CROSSHAIR]. */
    val crosshairExpanded: Boolean = false,
    /**
     * The colours the crosshair swatches offer, as ARGB, in the order they are drawn.
     *
     * Carried through the state while the designs beside them are not, and the asymmetry is the point: the
     * drawn designs are a property of the renderer and are the same list on every device, while this is a
     * list the settings layer owns — the fixed contrast set, and in time whatever the user has mixed. A row
     * built from a constant here would be a row that could not show the user their own colour.
     *
     * Empty draws no swatches at all rather than falling back to a default set, for the reason every other
     * empty list in this state does: a row assembled from something other than what the settings layer holds
     * would show a colour the crosshair screen does not offer.
     */
    val crosshairColours: List<Int> = emptyList(),
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
