package com.gamecore.core.model

/**
 * The floating dock's configuration and remembered position (feature 1).
 *
 * The dock is the floating button's sibling: a small draggable handle that snaps to an edge and, on a
 * tap, expands into a compact control panel. It is a separate window the user places separately, so it
 * keeps its own config rather than sharing [FloatingButtonConfig] — the two can be up at once, in
 * different corners and at different sizes.
 *
 * This class is the button's [FloatingButtonConfig] template with the fields the dock actually has:
 *
 *  - Position is stored as a fraction of the usable screen, one pair per orientation, exactly as the
 *    button's is and for the same reason — a pixel set on a 1080-wide portrait screen is meaningless on
 *    a 2400-wide landscape one, so the fraction re-resolves against whatever display the dock is placed
 *    on ([com.gamecore.core.overlay.OverlayFrame.fromFraction]). [x]/[y] are the pre-fraction pixel
 *    fallback, used only in an orientation the dock has not been placed in yet.
 *  - [opacity] is a 0f..1f alpha rather than the button's integer percent, because the dock composable
 *    applies it straight through `Modifier.alpha` and a float keeps the value in one space end to end.
 *  - [snapToEdge] defaults on, like the button: a handle parked in the middle of a game is a handle
 *    pressed by accident.
 *  - [actionOrder] and [hiddenActions] are the one piece of genuinely new state (features 4–5): which
 *    controls the panel offers and in what order. They are held here, globally, rather than on a game
 *    profile, because the dock is one window the user arranges once — the same reason size and position
 *    are here — so there is no per-game variant of it and no database migration behind it.
 *
 * Deliberately Android-free: it is a pure value the overlay layer, the service and the settings screen
 * all share, so it holds no `Color`, `Dp` or `Context`. The fractions are four flat floats rather than a
 * [com.gamecore.core.overlay.PositionFraction] for the same layering reason the button's are — that type
 * lives in the overlay layer, which depends on this model and not the reverse; the service converts.
 */
data class DockConfig(
    val show: Boolean = false,
    /**
     * Every dock action in the order the panel draws them, hidden ones included (features 4–5).
     *
     * The whole vocabulary, not just the shown slice, because the order has to survive hiding and
     * re-showing a control: a user who hides Screenshot, rearranges four things and shows it again expects
     * it back where it was, not appended to the end. Held as the enum rather than as strings — unknown
     * names drop at the storage boundary through [DockActionId.of], exactly as the quick sheet's pins do,
     * so nothing past that boundary ever has to reason about junk ids.
     *
     * Normalised through [DockActions.normaliseOrder] on every read and write, so a stored list that is
     * missing an id this build added, or carries one twice, still resolves to a complete order.
     */
    val actionOrder: List<DockActionId> = DockActionId.DEFAULT_ORDER,
    /**
     * The actions the user switched off, so the panel skips them (features 4–5).
     *
     * A hidden *set* alongside the full [actionOrder] rather than a single "shown" list, because the two
     * answer different questions and only one of them is a position: the order is where a control sits,
     * the set is whether it is on. Storing only the shown list would make a hide destroy the position, and
     * would also make "this build added a new action" ambiguous — absent from a shown list could mean
     * either "the user hid it" or "it did not exist when they last saved".
     *
     * Defaults to [DockActionId.DEFAULT_HIDDEN], which is everything outside the five controls the dock
     * shipped with, so a user upgrading into this release opens exactly the panel they already had.
     */
    val hiddenActions: Set<DockActionId> = DockActionId.DEFAULT_HIDDEN,
    val sizeDp: Int = DEFAULT_SIZE_DP,
    /** The dock's alpha at rest, 0f..1f. The composable shows it fully opaque while dragged or expanded. */
    val opacity: Float = DEFAULT_OPACITY,
    val snapToEdge: Boolean = true,
    /** A short buzz the instant a touch becomes a drag, matching the floating button. */
    val hapticFeedback: Boolean = true,
    /**
     * How wide the expanded [com.gamecore.core.overlay.DockPanel] window opens, in dp. A *requested*
     * width: this clamp only holds it inside [PANEL_WIDTH_RANGE], and the service clamps it a second time
     * against the display the panel actually opens on, exactly as [FloatingButtonConfig.panelWidthDp] is.
     */
    val panelWidthDp: Int = DEFAULT_PANEL_WIDTH_DP,
    /** The pre-fraction pixel fallback, used only in an orientation the dock has never been placed in. */
    val x: Int = DEFAULT_X,
    val y: Int = DEFAULT_Y,
    /**
     * The dock's remembered position as a fraction of the usable display, stored separately for portrait
     * and landscape. Null in an orientation it has not been placed in — the raw [x]/[y] pixels are used
     * there — and never trusted outside 0f..1f, since a value read back from storage could otherwise
     * place the dock off the safe box on the next resolve (see [normalised]).
     */
    val portraitXFraction: Float? = null,
    val portraitYFraction: Float? = null,
    val landscapeXFraction: Float? = null,
    val landscapeYFraction: Float? = null,
) {
    /**
     * The config clamped so it is safe to draw wherever it was assembled — a slider, a stored file, a
     * drag. The size and the panel width come back inside their ranges, opacity into
     * [MIN_OPACITY]..[MAX_OPACITY], and every *present* fraction into 0f..1f while a null one is left null
     * rather than snapped to an edge the user never chose.
     *
     * [actionOrder] comes back complete and duplicate-free (feature 6's "a corrupted setting falls back to
     * defaults", which here means falling back for the parts that are broken rather than discarding the
     * parts that are fine), and [hiddenActions] is held to ids this build still defines — a name that was
     * removed since the file was written is dropped rather than carried as a hide nothing can undo.
     */
    fun normalised(): DockConfig = copy(
        actionOrder = DockActions.normaliseOrder(actionOrder),
        hiddenActions = hiddenActions.intersect(DockActionId.entries.toSet()),
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        opacity = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY),
        panelWidthDp = panelWidthDp.coerceIn(PANEL_WIDTH_RANGE),
        portraitXFraction = portraitXFraction?.coerceIn(0f, 1f),
        portraitYFraction = portraitYFraction?.coerceIn(0f, 1f),
        landscapeXFraction = landscapeXFraction?.coerceIn(0f, 1f),
        landscapeYFraction = landscapeYFraction?.coerceIn(0f, 1f),
    )

    /**
     * The stored fraction pair for one orientation, or null if the dock has not been placed there yet.
     *
     * Returns null unless *both* components are present: half a coordinate cannot place a window, so a
     * partially-written pair falls back to the pixels rather than resolving to a corner the user never
     * picked. Mirrors [FloatingButtonConfig.positionFraction].
     */
    fun positionFraction(portrait: Boolean): Pair<Float, Float>? {
        val fx = if (portrait) portraitXFraction else landscapeXFraction
        val fy = if (portrait) portraitYFraction else landscapeYFraction
        return if (fx != null && fy != null) fx to fy else null
    }

    /** This config with the fraction pair for [portrait] replaced, the other orientation left untouched. */
    fun withPositionFraction(portrait: Boolean, xFraction: Float, yFraction: Float): DockConfig =
        if (portrait) {
            copy(portraitXFraction = xFraction, portraitYFraction = yFraction)
        } else {
            copy(landscapeXFraction = xFraction, landscapeYFraction = yFraction)
        }

    /** True when any orientation has a remembered fraction — the dock has been placed or pinned at least once. */
    fun hasStoredPositionFraction(): Boolean =
        portraitXFraction != null || portraitYFraction != null ||
            landscapeXFraction != null || landscapeYFraction != null

    /** This config with every remembered fraction cleared, for "reset position". The pixels are the caller's. */
    fun clearedPositionFractions(): DockConfig = copy(
        portraitXFraction = null,
        portraitYFraction = null,
        landscapeXFraction = null,
        landscapeYFraction = null,
    )

    /**
     * The actions the panel actually draws, in order (features 4–5).
     *
     * [actionOrder] minus [hiddenActions], capped at [DockActions.MAX_VISIBLE]. Availability is **not**
     * applied here: an action this device cannot perform still appears, drawn disabled with a reason, which
     * is the opposite of the quick sheet's rule and deliberate — the dock is where the user arranges their
     * controls, and silently dropping one would make the customisation screen and the panel disagree about
     * what is on it. Deciding what a device can do needs a service, so it stays out of a pure model.
     */
    fun visibleActions(): List<DockActionId> = DockActions.visible(actionOrder, hiddenActions)

    /**
     * This config with the action list back at its shipped order and visibility — "Reset Quick Actions"
     * (feature 6).
     *
     * Touches the two action fields and nothing else: size, opacity, snap, haptics, panel width and every
     * remembered position survive, because a user resetting which buttons are on the dock has not asked to
     * have the dock moved or resized.
     */
    fun withDefaultActions(): DockConfig = copy(
        actionOrder = DockActionId.DEFAULT_ORDER,
        hiddenActions = DockActionId.DEFAULT_HIDDEN,
    )

    /**
     * This config with the dock's own appearance and position back at their shipped values — "Reset Dock"
     * (feature 6).
     *
     * The mirror of [withDefaultActions]: it touches size, opacity, snap, haptics, panel width and the
     * remembered position, and leaves [actionOrder] and [hiddenActions] exactly as the user arranged them.
     *
     * [show] is deliberately **not** reset. A dock that vanished the instant the user pressed "Reset dock"
     * — while looking at it — reads as a crash rather than as a reset, and whether the dock is up at all is
     * a different decision from what it looks like.
     */
    fun withDefaultAppearance(): DockConfig = copy(
        sizeDp = DEFAULT_SIZE_DP,
        opacity = DEFAULT_OPACITY,
        snapToEdge = true,
        hapticFeedback = true,
        panelWidthDp = DEFAULT_PANEL_WIDTH_DP,
        x = DEFAULT_X,
        y = DEFAULT_Y,
    ).clearedPositionFractions()

    companion object {
        const val MIN_SIZE_DP = 36
        const val MAX_SIZE_DP = 72

        /** The size the dock ships at — a handle a thumb can find without covering the game. */
        const val DEFAULT_SIZE_DP = 44

        val SIZE_RANGE = MIN_SIZE_DP..MAX_SIZE_DP

        /** Below 30% a parked handle is hard to find; 1f is fully opaque. */
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1f
        const val DEFAULT_OPACITY = 0.9f

        const val MIN_PANEL_WIDTH_DP = 144
        const val MAX_PANEL_WIDTH_DP = 480

        /** Narrow: the dock panel is a compact strip, not the button's full control panel. */
        const val DEFAULT_PANEL_WIDTH_DP = 240

        val PANEL_WIDTH_RANGE = MIN_PANEL_WIDTH_DP..MAX_PANEL_WIDTH_DP

        /** The pre-fraction pixel fallback: the left edge, a little above the middle. */
        const val DEFAULT_X = 0
        const val DEFAULT_Y = 300

        /** The shipped config, and the value the store falls back to before the user touches the dock. */
        val DEFAULT = DockConfig()
    }
}

/**
 * Whether a [DockActionId] holds a state or fires once, which is all the panel needs to know to draw it.
 *
 * The dock panel has two grids — filled/outlined toggle cells and momentary action chips — and this is
 * what sorts an id into one of them. It is a property of the control, not of the user's arrangement, so it
 * lives on the enum entry rather than in the stored order.
 */
enum class DockActionKind {
    /** An on/off overlay or mode: the cell is filled with a ✓ when on, outlined when off. */
    TOGGLE,

    /** A one-shot command: a momentary chip with no state to show. */
    ACTION,
}

/**
 * Every control the dock panel can offer, and the stable identity each one is stored under (features 4–5).
 *
 * The dock's answer to [com.gamecore.core.overlay.QuickToggle], and written to follow it closely on
 * purpose: the two are the same kind of thing — a small, user-arranged slice of GameCore's controls drawn
 * over a game — and a user who has learned one should not have to learn a second set of rules. What this
 * file knows is only *which controls exist*, what to call them, which grid they belong in, and whether one
 * needs anything beyond the overlay permission. What a press actually **does** is deliberately not here:
 * that stays in the overlay service, which is the only thing that can reach an
 * [com.gamecore.core.overlay.OverlayAction], start a capture or launch a screen. Keeping the mapping out
 * of the model is what lets this enum live in `core.model` with no Android or Compose dependency at all,
 * and what makes the ordering rules in [DockActions] unit-testable on a plain JVM.
 *
 * Identity is the entry **name**, never its ordinal: the stored order is a list of names, so inserting a
 * new control here — or removing one — cannot silently reshuffle a dock the user arranged. That is also
 * what makes feature 6's "a corrupted setting falls back to defaults" true without any extra code; an
 * unrecognised name drops at [of] and [DockActions.normaliseOrder] puts the order back together around
 * the gap. The `-keepclassmembers enum com.gamecore.**` ProGuard rule is what keeps the names intact in a
 * release build.
 *
 * [isAlwaysAvailable] follows [com.gamecore.core.overlay.QuickToggle.isAlwaysAvailable] to the letter: it
 * is the honest hint that a control needs *nothing* to work, and it is documentation and a sane default,
 * **not** the gate. Real capability — is Shizuku up, is there a capture path, has the user switched the
 * feature off in Settings — is decided by the service and surfaced as a disabled cell with a reason.
 */
enum class DockActionId(
    val label: String,
    val kind: DockActionKind,
    val isAlwaysAvailable: Boolean,
    /**
     * True when pressing this leaves the game for one of GameCore's own screens.
     *
     * The panel says so before it happens. An action that swaps the game out from under the user is a
     * different promise from one that draws an overlay on top of it, and the dock is pressed mid-match.
     */
    val opensAppScreen: Boolean = false,
) {
    // ------------------------------------------------------------------ overlays this app draws
    // Each is a window GameCore puts on screen, so it depends on nothing beyond the overlay permission the
    // dock itself already holds — with the three capture-fed ones below as the exception that proves it.
    CROSSHAIR("Crosshair", DockActionKind.TOGGLE, isAlwaysAvailable = true),
    STATS("Stats", DockActionKind.TOGGLE, isAlwaysAvailable = true),
    WHEELS("Wheels", DockActionKind.TOGGLE, isAlwaysAvailable = true),
    HUD("HUD", DockActionKind.TOGGLE, isAlwaysAvailable = true),

    // Capture-fed overlays: their pixels come from the MediaProjection frame feed, which is a device fact
    // and a live permission, so none of the three can claim to be always available.
    MAGNIFIER("Magnifier", DockActionKind.TOGGLE, isAlwaysAvailable = false),
    SCOUT("Scout", DockActionKind.TOGGLE, isAlwaysAvailable = false),
    HUNT("Hunt", DockActionKind.TOGGLE, isAlwaysAvailable = false),

    // ------------------------------------------------------------------ one-shot commands
    /** Opens the floating button's full control panel — the dock's own escape hatch to everything else. */
    FULL_PANEL("Full panel", DockActionKind.ACTION, isAlwaysAvailable = true),

    SCREENSHOT("Screenshot", DockActionKind.ACTION, isAlwaysAvailable = false),

    /** Steps the display refresh rate, which goes through the elevated (Shizuku) shell. */
    REFRESH_RATE("Refresh", DockActionKind.ACTION, isAlwaysAvailable = false),

    /**
     * Places the volume-button trigger point without leaving the game (feature 4's in-game placement).
     *
     * Conditional on more than a permission: it needs the game in front to have a profile with a
     * [VolumeTriggerConfig] on it, so the dock can render it disabled with that as the reason rather than
     * dropping a user into a picker for a trigger they have not set up.
     */
    TRIGGER_POINT("Trigger", DockActionKind.ACTION, isAlwaysAvailable = false),

    // ------------------------------------------------------------------ GameCore's own screens
    // These swap the game out, so they carry opensAppScreen and the panel warns before it happens.
    SYSTEM_STATS("RAM / CPU", DockActionKind.ACTION, isAlwaysAvailable = true, opensAppScreen = true),
    SCREEN_EXTRACTION("Extract", DockActionKind.ACTION, isAlwaysAvailable = false, opensAppScreen = true),
    TOUCH_SAMPLING("Touch rate", DockActionKind.ACTION, isAlwaysAvailable = false, opensAppScreen = true),
    ;

    companion object {
        /**
         * The five controls the dock shipped with, in the order it drew them: the crosshair, stats and
         * wheels toggles, then the full-panel and screenshot chips.
         *
         * Hand-written rather than derived, for the reason [com.gamecore.core.overlay.QuickToggle
         * .DEFAULT_SET] is: these are grid positions under a thumb, and a default that reshuffled itself
         * when this enum grew a member would move a control the user has learned. It is also what makes
         * the upgrade honest — a user arriving from 3.7.0 with nothing stored opens exactly the panel they
         * already had, rather than one suddenly holding fourteen buttons.
         */
        val DEFAULT_VISIBLE: List<DockActionId> = listOf(CROSSHAIR, STATS, WHEELS, FULL_PANEL, SCREENSHOT)

        /**
         * The shipped order: the five defaults where they were, then everything else behind them.
         *
         * Derived for the tail and fixed for the head, so adding a control here can never leave
         * [DEFAULT_ORDER] incomplete and can never disturb the five positions that matter.
         */
        val DEFAULT_ORDER: List<DockActionId> =
            DEFAULT_VISIBLE + entries.filterNot { it in DEFAULT_VISIBLE }

        /** Everything outside [DEFAULT_VISIBLE] starts switched off. */
        val DEFAULT_HIDDEN: Set<DockActionId> = entries.filterNot { it in DEFAULT_VISIBLE }.toSet()

        /**
         * Parse a stored id back to an action, or null for one this build does not know.
         *
         * Null rather than a fallback — the same call [com.gamecore.core.overlay.QuickToggle.of] makes and
         * for the same reason: an arrangement is a set of choices, not a single mode, so a name written by
         * a newer build or belonging to a control since removed must drop out of the list rather than
         * resolve to some stand-in the user never chose. Doing the drop here, at the storage boundary, is
         * what lets everything above it work in real [DockActionId] values.
         */
        fun of(name: String?): DockActionId? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The pure rules behind the dock panel's arrangement (features 4–6), the sibling of
 * [com.gamecore.core.overlay.QuickSheetPins].
 *
 * Every rule here is a decision that can be made without a device — how many controls fit, how a
 * duplicate or a missing id collapses, what a reorder does at the ends — which is what keeps them
 * testable with plain JUnit and keeps them in one place rather than half in a ViewModel and half in the
 * service.
 *
 * It differs from [com.gamecore.core.overlay.QuickSheetPins] in one deliberate way: there is no
 * `isAvailable` predicate. The quick sheet drops a control the device cannot do; the dock shows it
 * disabled with a reason, because the dock is the surface where the user *arranges* their controls and a
 * silently dropped one would make the customisation screen and the live panel disagree about what is on
 * it. Capability therefore never enters these functions.
 */
object DockActions {

    /**
     * Eight shown at once.
     *
     * A layout fact rather than a preference, the same as [com.gamecore.core.overlay.QuickSheetPins
     * .MAX_PINS]: the panel is a two-column strip at most 280dp wide, so eight controls is four rows of
     * toggles and chips — about as much as can sit over a game without becoming the thing you are looking
     * at. It is also the wall a hand-edited file is held to, since the full vocabulary shown at once would
     * bury the match underneath it.
     */
    const val MAX_VISIBLE: Int = 8

    /**
     * A stored order turned into a complete one: every id exactly once, in the user's order, with anything
     * this build added appended behind what they arranged.
     *
     * Appended rather than inserted at a remembered position, because a control that did not exist when
     * the user last touched the dock has no remembered position — and putting it at the front would hand a
     * new feature a thumb position the user assigned to something else. Duplicates collapse to the first
     * occurrence so the earliest placement wins, which is the rule
     * [com.gamecore.core.overlay.QuickSheetPins.normalise] uses and makes the order stable under a repeat.
     */
    fun normaliseOrder(order: List<DockActionId>): List<DockActionId> {
        val kept = order.distinct()
        return kept + DockActionId.entries.filterNot { it in kept }
    }

    /**
     * The shown slice, in order: [normaliseOrder] minus [hidden], capped at [MAX_VISIBLE].
     *
     * The cap comes last so it is spent on real, distinct, shown controls rather than on a duplicate or
     * something the user switched off.
     */
    fun visible(order: List<DockActionId>, hidden: Set<DockActionId>): List<DockActionId> =
        normaliseOrder(order).filterNot { it in hidden }.take(MAX_VISIBLE)

    /** The shown controls of one kind, for the panel's two grids. */
    fun visibleOfKind(
        order: List<DockActionId>,
        hidden: Set<DockActionId>,
        kind: DockActionKind,
    ): List<DockActionId> = visible(order, hidden).filter { it.kind == kind }

    /**
     * Switch [id] on, returning the new hidden set.
     *
     * A no-op — the set back unchanged — when [MAX_VISIBLE] are already shown, so the cap is enforced where
     * the user can be told about it rather than by [visible] silently truncating their last tap. Switching
     * on something already on is also a no-op, which makes a double tap harmless.
     */
    fun show(
        order: List<DockActionId>,
        hidden: Set<DockActionId>,
        id: DockActionId,
    ): Set<DockActionId> = when {
        id !in hidden -> hidden
        visible(order, hidden).size >= MAX_VISIBLE -> hidden
        else -> hidden - id
    }

    /**
     * Switch [id] off, returning the new hidden set. The position in [order] is untouched on purpose — it
     * is what brings the control back where it was if the user switches it on again.
     */
    fun hide(hidden: Set<DockActionId>, id: DockActionId): Set<DockActionId> = hidden + id

    /**
     * Move a shown control one place along the panel. [delta] is -1 (towards the front) or +1 (towards the
     * back); the ends are walls, not a wrap — the semantics of
     * [com.gamecore.core.overlay.QuickSheetPins.move] and of `OverlayViewModel.moveStat`, so the same
     * gesture behaves the same way everywhere in the app.
     *
     * The swap is with the next *shown* control, not the next entry in [order], which is why [hidden] is a
     * parameter here and nowhere else in this object. That distinction is the whole point: [order] carries
     * hidden ids too, so a plain adjacent swap would trade places with something invisible and the arrow
     * would appear to do nothing. A hidden [id], or a move off either end, returns the order untouched.
     */
    fun move(
        order: List<DockActionId>,
        hidden: Set<DockActionId>,
        id: DockActionId,
        delta: Int,
    ): List<DockActionId> {
        val list = normaliseOrder(order)
        if (delta == 0 || id in hidden) return list
        val from = list.indexOf(id)
        if (from < 0) return list
        val step = if (delta > 0) 1 else -1
        // Walk past anything the user switched off, so the arrow moves the control one *visible* place.
        var to = from + step
        while (to in list.indices && list[to] in hidden) to += step
        if (to !in list.indices) return list
        return list.toMutableList().apply {
            removeAt(from)
            add(to, id)
        }
    }

    /**
     * The stored names, parsed and completed in one step — what the store calls on read.
     *
     * Unknown names drop at [DockActionId.of] and [normaliseOrder] closes the gap, so a file written by a
     * newer build, or one hand-edited into nonsense, still produces a complete order rather than a panel
     * with holes in it.
     */
    fun parseOrder(names: List<String>): List<DockActionId> =
        normaliseOrder(names.mapNotNull { DockActionId.of(it) })

    /**
     * The stored hidden names, parsed to a set. An absent key is the caller's business, not this
     * function's: an empty *stored* set and "the user has never arranged the dock" are different states,
     * and only the store can tell them apart.
     */
    fun parseHidden(names: List<String>): Set<DockActionId> =
        names.mapNotNull { DockActionId.of(it) }.toSet()
}
