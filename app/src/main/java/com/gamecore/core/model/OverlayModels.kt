package com.gamecore.core.model

import com.gamecore.core.common.TextSanitizer

/**
 * The floating performance pill's configuration.
 *
 * Position is stored as pixels rather than fractions, unlike [HudWidget], and the difference
 * is intentional: the pill is dragged by the user in a live overlay and snapped to an edge, so
 * the pixel position *is* what they chose. Rotating the device re-snaps it to the nearest edge
 * rather than trying to preserve a fraction that would put it somewhere they did not put it.
 */
data class OverlayConfig(
    val showPill: Boolean = false,
    val pillX: Int = 0,
    val pillY: Int = 200,
    val stats: List<HudStat> = HudStat.DEFAULT_SET,
    val updateIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val textSizeSp: Int = 11,
    val opacityPercent: Int = 85,
    val cornerRadiusDp: Int = 12,
    val isVertical: Boolean = false,
    val showLabels: Boolean = true,
    val displayMode: PillDisplayMode = PillDisplayMode.DETAILED,
    // The quick sheet's pinned toggles (spec §4), as an ordered list of `QuickToggle` *names* rather than
    // the enum itself. `QuickToggle` lives in `com.gamecore.core.overlay`, which depends on this model and
    // not the reverse — holding the typed enum here would be the first back-edge in that dependency, the
    // same wall the button's `PositionFraction` fields hit below (see the note there). So the service
    // parses these names through `QuickToggle.of` at its boundary, exactly as it does the pill's stats.
    // Empty means "never set" — the service falls back to `QuickToggle.DEFAULT_SET`, matching how
    // `QuickSheetPins.normalise` collapses an empty list to the filtered defaults.
    val quickPins: List<String> = emptyList(),
    // Whether the quick sheet closes itself after a spell with no touches (spec §4). Default on, because
    // the sheet is a transient thing summoned over a game and a user who opens it mid-match and then goes
    // back to playing should not have to dismiss it. Off is for the user who wants it to stay put while
    // they change three things in a row; the sheet then closes only when they close it, tap outside it, or
    // something else takes it down.
    val quickAutoClose: Boolean = true,
    // The user's one-tap panel macros (§14), serialized. A macro is `{id, name, actions}` — free user text
    // and an ordered list — which the `"|"`-joined-names convention this store uses for `quickPins` cannot
    // hold, so the macro list is carried here as the single JSON string `MacroCodec` produces. It is opaque
    // to this layer for the same reason `quickPins` holds names and not `QuickToggle`: the codec, the enum
    // it resolves and the sanitiser it runs all live in `com.gamecore.core.overlay`, which depends on this
    // model and not the reverse. So this stays a `String`, `normalised()` leaves it untouched, and the
    // service and the editor decode it through `MacroCodec` at their boundary — which is where the names are
    // resolved, the strangers dropped and the caps applied, exactly as the service does for `quickPins`.
    // Empty means "none set". Riding inside `OverlayConfig` is deliberate: §20's config backup serializes
    // this config, so the macros travel with it for free.
    val macrosJson: String = "",
) {
    fun normalised(): OverlayConfig = copy(
        stats = stats.distinct().take(MAX_STATS),
        updateIntervalMillis = updateIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS),
        textSizeSp = textSizeSp.coerceIn(TEXT_SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
        // Deduped and capped like `stats`. The names are not validated here — this layer cannot see
        // `QuickToggle` to know which are real — so an unknown name survives normalisation and is dropped
        // later by `QuickToggle.of` at the service boundary, just as `readStats` drops unknown stat names.
        quickPins = quickPins.distinct().take(MAX_QUICK_PINS),
    )

    companion object {
        /**
         * The bounds, named, because two places need them.
         *
         * The clamp above is the one that matters — a config assembled anywhere is valid after it — but
         * the settings screen's sliders have to span exactly the same span, and a slider whose range was
         * a literal typed a second time is a slider whose top end silently clamps to something else.
         */
        val TEXT_SIZE_RANGE = 8..20
        val OPACITY_RANGE = 20..100
        val CORNER_RANGE = 0..32

        const val DEFAULT_INTERVAL_MILLIS = 1_000L

        /**
         * Half a second is the floor, and it is a real one rather than a round number: every
         * tick is a `/proc` read, a binder call and a recomposition, and below this the pill
         * costs the game more frames than the numbers on it are worth.
         */
        const val MIN_INTERVAL_MILLIS = 500L
        const val MAX_INTERVAL_MILLIS = 10_000L
        const val MAX_STATS = 8

        /**
         * The quick sheet's pin cap. Mirrors `com.gamecore.core.overlay.QuickSheetPins.MAX_PINS`, which is
         * the authority — that layer sees `QuickToggle` and does the real availability-aware normalise. This
         * copy exists only so a config assembled here is never larger than that cap; it cannot import the
         * overlay layer to share the constant (the same layering reason `quickPins` holds names, not enums).
         * Keep the two in sync: 6.
         */
        const val MAX_QUICK_PINS = 6
    }
}

/**
 * How the stats pill lays its readings out (spec §3).
 *
 * Two shapes, one list of stats. [COMPACT] is the single glanceable line — values with units joined by
 * a middot, "62°C · 74% · 120 Hz", plus the thermal dot the button already carries, for a user who wants
 * a temperature check without a panel over the game. [DETAILED] is the labelled card of cells the pill
 * has always drawn, for a user watching several numbers at once.
 *
 * This is the *shape*, orthogonal to [OverlayConfig.isVertical] (which is the *axis* of the detailed
 * card — a row or a column). A compact line is always one line, so it ignores the axis. The default is
 * [DETAILED] on purpose: it is byte-for-byte the pill every existing user already has, so an install that
 * updates over the old version and never opens this setting sees no change at all.
 */
enum class PillDisplayMode(val label: String) {
    COMPACT("Compact"),
    DETAILED("Detailed"),
    ;

    companion object {
        /** Parse a stored name back to a mode, falling back to [DETAILED] for an unknown or absent value. */
        fun of(name: String?): PillDisplayMode = entries.firstOrNull { it.name == name } ?: DETAILED
    }
}

/**
 * How the control panel arranges itself when the floating button opens it.
 *
 * A global preference rather than a per-profile one, and that is a deliberate line: everything a game
 * profile owns is a *device* setting with a restore point — a refresh rate, a brightness, a colour
 * preset — and this is not one of those. It is where the user's hands are. Somebody who reaches for the
 * left and right edges of a landscape phone reaches for them in every game, and a layout that moved
 * when the profile changed would put the controls somewhere their thumbs are not, which is worse than
 * either layout is on its own. It also has nothing to restore, so a per-profile version would be a
 * per-profile setting the restore machinery could not see.
 *
 * [CENTERED] is first because it is the default and has to stay the default: it is what every existing
 * install already has, and a redesign that reshapes a panel people have learned is not an improvement
 * they asked for. [SPLIT_EDGES] is offered, not preferred.
 */
enum class PanelLayoutStyle(val label: String, val description: String) {

    CENTERED(
        label = "Centered",
        description = "One panel above or below the button. Its width is yours to set.",
    ),

    SPLIT_EDGES(
        label = "Split edges",
        description = "Readouts on one screen edge, controls on the other, with the game visible " +
            "between them. Uses the full height of the screen, so the width setting does not apply.",
    ),
    ;

    /**
     * The other layout, for the panel's own Layout tile.
     *
     * Here rather than in the overlay service because it is a fact about this set and not about a window,
     * and because it is the part of that tile a test can hold: the service's half is a preference write
     * and a window rebuild, neither of which runs on a JVM.
     *
     * A `when` with no `else`, which is the point of writing it out for two values. A third layout would
     * fail to compile here, and that is the right place to be stopped — a two-state tap has no meaning
     * over three layouts, so the tile would have to become a row of chips, and this line is what makes
     * somebody decide that instead of inheriting a cycle that skips a layout the user cannot reach.
     */
    fun other(): PanelLayoutStyle = when (this) {
        CENTERED -> SPLIT_EDGES
        SPLIT_EDGES -> CENTERED
    }
}

/**
 * The three named button sizes from spec §2, each a point on the existing `sizeDp` scale.
 *
 * Presets, not a replacement for the size slider: the chip is a one-tap way to the sizes most people
 * want, and the slider stays for anyone who wants a value between them. Because the size is still stored
 * as one `sizeDp`, a slider left at 50 dp is a real, kept size that simply matches no preset — [of]
 * returns null there and no chip lights up, which is the truth rather than rounding the user's choice to
 * the nearest name behind their back. The three dp values are the range's floor, its shipped default and
 * its ceiling, so the presets and the slider can never disagree about what is allowed.
 */
enum class ButtonSizePreset(val label: String, val sizeDp: Int) {
    SMALL("Small", FloatingButtonConfig.MIN_SIZE_DP),
    MEDIUM("Medium", FloatingButtonConfig.DEFAULT_SIZE_DP),
    LARGE("Large", FloatingButtonConfig.MAX_SIZE_DP),
    ;

    companion object {
        /** The preset a stored size *exactly* is, or null when the slider sits between two of them. */
        fun of(sizeDp: Int): ButtonSizePreset? = entries.firstOrNull { it.sizeDp == sizeDp }
    }
}

/**
 * The floating button's configuration and remembered position.
 *
 * [snapToEdge] is on by default because a button that sits under the user's thumb in the
 * middle of a game is a button that gets pressed by accident. The position is stored raw and
 * the snap applied at layout time, so switching the setting off puts the button back where it
 * was dragged rather than where it was snapped to.
 *
 * The control panel's width lives here too, and that is a deliberate choice rather than a
 * convenience: the panel is this button's expanded form — the button opens it and the window is
 * positioned from the button's centre — while [OverlayConfig] above is scoped to the stats pill,
 * which is a separate window the user places separately.
 */
data class FloatingButtonConfig(
    val show: Boolean = true,
    val x: Int = 0,
    val y: Int = 400,
    val sizeDp: Int = DEFAULT_SIZE_DP,
    val opacityPercent: Int = 80,
    val snapToEdge: Boolean = true,
    /** Fades to this while a game is in the foreground and the panel is closed. */
    val idleOpacityPercent: Int = 40,
    val hapticFeedback: Boolean = true,
    /**
     * Whether a double tap on the button opens the full panel, spec §4's "optional setting".
     *
     * Off, so a single tap opens the quick sheet with no added latency — see
     * [com.gamecore.core.overlay.FloatingGameButton], where a null double-tap handler makes every tap
     * fire the instant the finger lifts. Turning this on wires the second handler: the first tap of a
     * pair still fires immediately (the quick sheet opens), and a second within the double-tap window
     * swaps it for the panel. The user reaches the panel from the sheet's "More" button either way; this
     * only adds the shortcut for those who want it, and defaults off because an armed double tap makes a
     * stray second tap open a surface the user did not ask for.
     */
    val doubleTapForPanel: Boolean = false,
    /**
     * How wide the control panel opens, in dp. One preference for every game.
     *
     * A *requested* width, not a promise: the clamp below cannot know how wide the screen is, so it
     * only keeps the figure inside [PANEL_WIDTH_RANGE], and the overlay service clamps it a second
     * time against the display the panel actually opens on. That second clamp is why the range's top
     * end is worth reaching for on a phone — the panel opens over a game, and a game is usually
     * landscape, where there is room for all of it.
     *
     * Height is not settable and deliberately so: the panel is measured against the gap between the
     * button and the nearest screen edge and scrolls inside whatever that leaves, so a stored height
     * would be a number the anchoring overrules.
     */
    val panelWidthDp: Int = DEFAULT_PANEL_WIDTH_DP,
    /**
     * Which shape the panel opens in. See [PanelLayoutStyle] for why this is not per-profile.
     *
     * Sits beside [panelWidthDp] because the two are read together and one bounds the other:
     * [PanelLayoutStyle.SPLIT_EDGES] derives its plate width from the screen, so the stored width is
     * carried untouched rather than cleared. Switching back to [PanelLayoutStyle.CENTERED] then finds
     * the width the user set, which is the only behaviour that does not punish trying the other layout.
     */
    val panelLayout: PanelLayoutStyle = PanelLayoutStyle.CENTERED,
    /**
     * Whether the panel draws a row of the user's own apps under the action grid.
     *
     * Off, and independent of [panelLayout] rather than a third value of it. Independent because the
     * two are answers to different questions — the layout is the panel's *shape*, this is one more
     * thing in it — and a user who prefers the split layout has no reason to give up quick launching
     * to keep it. [PanelLayoutStyle.other] says why a third shape would be the wrong thing to add.
     *
     * Off by default because the row costs a strip of the game's screen and names apps GameCore has no
     * business guessing at. With no [quickAppPackages] chosen it draws nothing even when this is on,
     * so the honest first state is an empty row that is also an invisible one.
     */
    val showQuickApps: Boolean = false,
    /**
     * The apps in that row, in the order the user put them in.
     *
     * A [List] and not a [Set] because the order is the setting — these are positions under a thumb,
     * and a row that reshuffled itself between sessions would be a row you cannot learn. Empty until
     * the user picks something: there is no default set of apps here, and inventing one would put
     * three packages GameCore chose over somebody's game.
     *
     * Package names only. The label and the icon are resolved from `PackageManager` at draw time
     * rather than stored, so an app that is renamed, updated or uninstalled cannot leave this list
     * describing something that is no longer true — see
     * [com.gamecore.domain.overlay.QuickAppLauncher].
     */
    val quickAppPackages: List<String> = emptyList(),
    /**
     * The button's remembered position as a fraction of the usable display, stored separately for portrait
     * and landscape (spec §2). Null in an orientation the button has not been placed in, and in that case
     * the raw [x]/[y] pixels are used — so an install from before these fields existed, and a button never
     * dragged in a given orientation, behave exactly as they did.
     *
     * Fractions rather than pixels because a pixel set on a 1080-wide portrait screen is meaningless on a
     * 2400-wide landscape one: the fraction re-resolves against whatever display the button is actually
     * placed on ([com.gamecore.core.overlay.OverlayFrame.fromFraction]). Two pairs rather than one because
     * the usable box is a different shape in each orientation and a single fraction would drift between them.
     *
     * Stored as four flat floats rather than a value type because [com.gamecore.core.overlay.PositionFraction]
     * lives in the overlay layer, which depends on this model and not the other way round; the service
     * converts between the two.
     */
    val portraitXFraction: Float? = null,
    val portraitYFraction: Float? = null,
    val landscapeXFraction: Float? = null,
    val landscapeYFraction: Float? = null,
) {
    fun normalised(): FloatingButtonConfig = copy(
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        idleOpacityPercent = idleOpacityPercent.coerceIn(IDLE_OPACITY_RANGE),
        panelWidthDp = panelWidthDp.coerceIn(PANEL_WIDTH_RANGE),
        // A fraction read back out of storage is not trusted any more than a package name is; a value
        // outside 0..1 would place the button off the safe box on the next resolve.
        portraitXFraction = portraitXFraction?.coerceIn(0f, 1f),
        portraitYFraction = portraitYFraction?.coerceIn(0f, 1f),
        landscapeXFraction = landscapeXFraction?.coerceIn(0f, 1f),
        landscapeYFraction = landscapeYFraction?.coerceIn(0f, 1f),
        // Held to the same rule as AppSettings.neverKillPackages, and for the same reason: a value
        // read back out of the settings file is not trusted because it came out of storage. Every
        // entry has to look like a package name before it reaches an Intent or the user's eyes.
        quickAppPackages = quickAppPackages
            .mapNotNull { TextSanitizer.validatePackageName(it) }
            .distinct()
            .take(MAX_QUICK_APPS),
    )

    /**
     * The stored fraction pair for one orientation, or null if the button has not been placed there yet.
     *
     * `portrait` selects which of the two pairs to read; the caller knows the orientation from the live
     * display. Returns null unless *both* components are present, because half a coordinate cannot place a
     * window — a partially-written pair (which storage should never produce) falls back to the pixels
     * rather than resolving to a corner the user never chose.
     */
    fun positionFraction(portrait: Boolean): Pair<Float, Float>? {
        val fx = if (portrait) portraitXFraction else landscapeXFraction
        val fy = if (portrait) portraitYFraction else landscapeYFraction
        return if (fx != null && fy != null) fx to fy else null
    }

    /** This config with the fraction pair for [portrait] replaced, the other orientation left untouched. */
    fun withPositionFraction(portrait: Boolean, xFraction: Float, yFraction: Float): FloatingButtonConfig =
        if (portrait) {
            copy(portraitXFraction = xFraction, portraitYFraction = yFraction)
        } else {
            copy(landscapeXFraction = xFraction, landscapeYFraction = yFraction)
        }

    /**
     * True when any orientation has a remembered fraction — the button has been placed or pinned at least
     * once. Lets "reset position" stay offered after a corner pin even when the pixels happen to still read
     * as the default, since a pin the user wants gone lives in the fractions, not the pixels.
     */
    fun hasStoredPositionFraction(): Boolean =
        portraitXFraction != null || portraitYFraction != null ||
            landscapeXFraction != null || landscapeYFraction != null

    /** This config with every remembered fraction cleared, for "reset position". The pixels are the caller's. */
    fun clearedPositionFractions(): FloatingButtonConfig = copy(
        portraitXFraction = null,
        portraitYFraction = null,
        landscapeXFraction = null,
        landscapeYFraction = null,
    )

    companion object {
        const val MIN_SIZE_DP = 36
        const val MAX_SIZE_DP = 72

        /** The size it ships at and what the Medium preset is — the middle of the three named sizes. */
        const val DEFAULT_SIZE_DP = 48

        val SIZE_RANGE = MIN_SIZE_DP..MAX_SIZE_DP

        /** A button below 30% is hard to find; below 10% while idle it is invisible, not subtle. */
        val OPACITY_RANGE = 30..100
        val IDLE_OPACITY_RANGE = 10..100

        /**
         * What the panel has always been, and now also what "Reset to default size" means.
         *
         * A stored width rather than a measured one, because the window has to be positioned before
         * its content is measured: the panel is anchored to the same screen edge as the button that
         * opened it, and the x for that is `screenWidth - width`. Knowing the width up front is what
         * makes that exact — the alternative is adding the window at x = 0 and moving it after the
         * first layout, which is a visible jump. Letting the user set the figure changes who chooses
         * it, not when it is known.
         */
        const val DEFAULT_PANEL_WIDTH_DP = 268

        /**
         * Two action tiles per row, their gap and the panel's padding: 2 × 57 + 6 + 24.
         *
         * The floor is the grid rather than the header, because the grid is the widest thing the
         * panel draws and one tile per row is a list, not a grid. `actionsPerRow` in
         * `com.gamecore.core.overlay` does this arithmetic properly against its own constants and a
         * test holds the two to the same answer at this width — this figure is the model's copy of a
         * layout fact, and a copy that drifts is a panel whose narrowest setting clips a tile.
         */
        const val MIN_PANEL_WIDTH_DP = 144

        /**
         * Past a phone's portrait width, so that in portrait it is the screen — not this number —
         * that decides how wide the panel can be, which is the honest limit to be bound by. Above
         * that it is a ceiling on a landscape phone and a tablet, where a panel that kept growing
         * would be a row of buttons with a hand's width of empty plate beside it.
         */
        const val MAX_PANEL_WIDTH_DP = 480

        val PANEL_WIDTH_RANGE = MIN_PANEL_WIDTH_DP..MAX_PANEL_WIDTH_DP

        /**
         * How many apps the quick-launch row will hold.
         *
         * Six, because the row is one row. The panel is between [MIN_PANEL_WIDTH_DP] and
         * [MAX_PANEL_WIDTH_DP] wide and the icons share it, so six at the narrowest setting is about
         * 20 dp of icon — small, but still a target, and the user chose that width. A seventh would
         * either wrap to a second row, which is no longer "a row of apps under the tiles", or shrink
         * every icon past recognising. It is also a limit worth having against a hand-edited file:
         * the row is drawn over somebody's game, and a list of forty would cover it.
         */
        const val MAX_QUICK_APPS = 6
    }
}

/**
 * Which overlay windows are up, for the dashboard's status row and for the service to
 * reconcile against.
 *
 * A single flag would not do: the overlay service can be running with the button up and the
 * pill down, and a dashboard that showed "overlay: on" would not tell the user why they cannot
 * see their stats.
 */
data class OverlayStatus(
    val serviceRunning: Boolean = false,
    val buttonVisible: Boolean = false,
    val pillVisible: Boolean = false,
    val crosshairVisible: Boolean = false,
    val hudVisible: Boolean = false,
    val hasPermission: Boolean = false,
) {
    val anythingVisible: Boolean
        get() = buttonVisible || pillVisible || crosshairVisible || hudVisible

    val summary: String
        get() = when {
            !hasPermission -> "Overlay permission not granted"
            !serviceRunning -> "Off"
            !anythingVisible -> "Running, nothing shown"
            else -> listOfNotNull(
                "Button".takeIf { buttonVisible },
                "Stats".takeIf { pillVisible },
                "Crosshair".takeIf { crosshairVisible },
                "HUD".takeIf { hudVisible },
            ).joinToString(", ")
        }

    companion object {
        val OFF = OverlayStatus()
    }
}

/**
 * Which overlay windows *should* be up, and with what content.
 *
 * The counterpart to [OverlayStatus]: this is the request, that is the result, and they are separate
 * types because they genuinely disagree — a request for a crosshair on a device where the user has
 * revoked the overlay permission produces a status with nothing visible, and the dashboard has to be
 * able to say so rather than showing a crosshair that is not there.
 *
 * Held by `OverlayController` and rendered by `GamingOverlayService`. A game profile writes it when a
 * game launches and the settings screens write it when the user toggles something by hand, which is
 * why the ids are here: a profile names a crosshair and a HUD layout by id, and the service resolves
 * them. Null means "the one the user last picked", not "none" — that distinction is [crosshair] and
 * [hud].
 */
data class OverlayRequest(
    val button: Boolean = false,
    val pill: Boolean = false,
    val crosshair: Boolean = false,
    val hud: Boolean = false,
    /**
     * The pinned magnifier of §13. A plain on/off like [crosshair] and [hud], and deliberately without an
     * id beside it: unlike a crosshair preset or a HUD layout there is nothing saved to name — the loupe's
     * factor and corner are fixed in this build — so the request carries only whether it should be up. The
     * frame it magnifies is not part of the request either; it comes from the capture path the service owns.
     */
    val magnifier: Boolean = false,
    val crosshairPresetId: Long? = null,
    val hudLayoutId: Long? = null,
    /** The game the request came from, shown in the control panel's header. Empty when manual. */
    val gameLabel: String = "",
    /** True while a game profile is driving this, so clearing it can restore the manual state. */
    val fromProfile: Boolean = false,
) {
    val anythingVisible: Boolean get() = button || pill || crosshair || hud || magnifier

    /**
     * Switches the crosshair on or off, and settles which preset it draws.
     *
     * Three sources for the id, in the order they win: the one the caller named, the one already in this
     * request — so the crosshair editor can toggle its own preview without restating its id — and
     * [remembered], the preset the user last picked. That third one is what [crosshairPresetId]'s "the one
     * the user last picked" means in practice, and it is why this is a function rather than a `copy`: a
     * request assembled without it asks for *a* crosshair rather than *the user's* crosshair, and the
     * renderer cannot tell those two apart. It draws the lowest-numbered saved preset for both, which is
     * every design looking like the first one in the list.
     *
     * Null survives only when there has never been a pick at all, which is the single case the renderer's
     * own fallback is there for.
     */
    fun withCrosshair(visible: Boolean, presetId: Long?, remembered: Long?): OverlayRequest =
        copy(crosshair = visible, crosshairPresetId = presetId ?: crosshairPresetId ?: remembered)

    /** The HUD's equivalent, for the same reason: a layout the user arranged is not "any layout". */
    fun withHud(visible: Boolean, layoutId: Long?, remembered: Long?): OverlayRequest =
        copy(hud = visible, hudLayoutId = layoutId ?: hudLayoutId ?: remembered)

    /**
     * Switches the magnifier on or off. A plain `copy`, not a resolver like [withCrosshair] and [withHud]:
     * there is no id to settle — the loupe has no saved preset — so the only thing a toggle changes is the
     * flag. Kept as a named helper anyway so every visibility change on this request reads the same way at
     * its call site, and so a future factor/corner id has one obvious place to be resolved.
     */
    fun withMagnifier(visible: Boolean): OverlayRequest = copy(magnifier = visible)

    companion object {
        val NONE = OverlayRequest()
    }
}
