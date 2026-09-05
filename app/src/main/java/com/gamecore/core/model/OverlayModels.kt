package com.gamecore.core.model

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
) {
    fun normalised(): OverlayConfig = copy(
        stats = stats.distinct().take(MAX_STATS),
        updateIntervalMillis = updateIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS),
        textSizeSp = textSizeSp.coerceIn(TEXT_SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
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
    val sizeDp: Int = 48,
    val opacityPercent: Int = 80,
    val snapToEdge: Boolean = true,
    /** Fades to this while a game is in the foreground and the panel is closed. */
    val idleOpacityPercent: Int = 40,
    val hapticFeedback: Boolean = true,
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
) {
    fun normalised(): FloatingButtonConfig = copy(
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        idleOpacityPercent = idleOpacityPercent.coerceIn(IDLE_OPACITY_RANGE),
        panelWidthDp = panelWidthDp.coerceIn(PANEL_WIDTH_RANGE),
    )

    companion object {
        const val MIN_SIZE_DP = 36
        const val MAX_SIZE_DP = 72

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
    val crosshairPresetId: Long? = null,
    val hudLayoutId: Long? = null,
    /** The game the request came from, shown in the control panel's header. Empty when manual. */
    val gameLabel: String = "",
    /** True while a game profile is driving this, so clearing it can restore the manual state. */
    val fromProfile: Boolean = false,
) {
    val anythingVisible: Boolean get() = button || pill || crosshair || hud

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

    companion object {
        val NONE = OverlayRequest()
    }
}
