package com.gamecore.core.overlay

/**
 * The controls that can be pinned to the quick sheet's toggle grid (spec §4).
 *
 * A deliberately small slice of the full panel: the quick sheet is the tap-and-back surface, so it holds
 * only the controls a user reaches for mid-game without wanting the whole panel. The grid is "up to 6
 * pinned toggles", and although the spec calls them toggles the set genuinely mixes two shapes — a real
 * on/off toggle (Stats, Crosshair, HUD, Silence, Torch, Rotation) and a one-shot action (Screenshot,
 * Record, Refresh). Both belong here because both are things you fire and drop back into the game; the
 * grid cell just renders a momentary press for an action and a filled state for a toggle. What matters to
 * *this* file is only which ids exist and which are pinnable, never what a press does — that stays in the
 * service, exactly as it is today (spec §0: "NO change to what any toggle or control actually does").
 *
 * [isAlwaysAvailable] follows [com.gamecore.core.model.HudStat] to the letter: it is the honest hint that
 * a control needs *nothing* to work. The three overlay toggles are always available because drawing an
 * overlay window depends on nothing but the overlay permission the whole surface already holds. Everything
 * else is conditional and says so — Silence needs notification-policy access, Screenshot/Record need the
 * capture path, Torch needs a flash unit, Rotation/Refresh go through the elevated (Shizuku) shell. This
 * flag is documentation and a sane default, **not** the gate: real device capability is decided by the
 * caller and handed to [QuickSheetPins] as a predicate, because only the service can ask Shizuku, the
 * PackageManager or a permission whether a thing works here and now, and none of that runs on a JVM.
 */
enum class QuickToggle(
    val label: String,
    val isAlwaysAvailable: Boolean,
) {
    // Overlays — each is a window this app draws, so it depends on nothing beyond the overlay permission.
    STATS("Stats", isAlwaysAvailable = true),
    CROSSHAIR("Crosshair", isAlwaysAvailable = true),
    HUD("HUD", isAlwaysAvailable = true),

    // Capture — the capture path and the flash unit are device facts the caller must confirm.
    SCREENSHOT("Screenshot", isAlwaysAvailable = false),
    RECORD("Record", isAlwaysAvailable = false),
    TORCH("Torch", isAlwaysAvailable = false),

    // Session / display — DND is a permission; rotation lock and refresh rate are elevated-shell controls.
    SILENCE("Silence", isAlwaysAvailable = false),
    ROTATION("Rotation", isAlwaysAvailable = false),
    REFRESH("Refresh", isAlwaysAvailable = false),

    // Magnifier — an overlay window like the three above, but its pixels come from the MediaProjection frame
    // feed, so it is conditional on the capture path exactly as Screenshot and Record are.
    MAGNIFIER("Magnifier", isAlwaysAvailable = false),
    ;

    companion object {
        /**
         * The six shipped defaults, in the spec §4 order: Silence, Crosshair, Stats, HUD, Screenshot,
         * Refresh. A `List`, not the enum's own order, because the order is the setting — these are grid
         * positions under a thumb, and a default that reshuffled itself when the enum grew a member would
         * move a control the user has learned. Kept even though three of the six are conditional: the
         * normaliser filters this list by availability, so a device with no Shizuku and no DND access still
         * gets an honest, smaller default rather than an empty grid.
         */
        val DEFAULT_SET: List<QuickToggle> = listOf(SILENCE, CROSSHAIR, STATS, HUD, SCREENSHOT, REFRESH)

        /**
         * Parse a stored id back to a toggle, or null for one this build does not know.
         *
         * Null rather than a fallback (unlike [com.gamecore.core.model.PillDisplayMode.of]) because a
         * pinned list is a set of choices, not a single mode: an id written by a newer build, or one for a
         * control since removed, must simply drop out of the list rather than resolve to some stand-in the
         * user never pinned. Doing the drop here, at the storage boundary, is what lets [normalise] work in
         * terms of real [QuickToggle] values and never have to reason about junk ids.
         */
        fun of(name: String?): QuickToggle? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The pure rules behind the quick sheet's pinned-toggle grid (spec §4, tested per spec §10).
 *
 * Every rule here is a decision that can be made without a device: how many pins fit, how duplicates and
 * unknowns collapse, what an empty list means, and how the pin editor's add/remove/reorder behave. Device
 * capability — "does this phone have a flash unit", "is Shizuku up", "is DND permission granted" — is the
 * one thing this object refuses to decide, taking it as an `isAvailable` predicate instead. That split is
 * what keeps the rules unit-testable with plain JUnit: a test supplies its own predicate and never needs a
 * `Context`, a `PackageManager` or a live Shizuku connection.
 */
object QuickSheetPins {

    /**
     * Six, matching the grid's shape (3 × 2) in spec §4. A seventh pin would either overflow the grid the
     * mockup fixes at two rows or shrink every cell past a thumb target, so the cap is a layout fact, not a
     * preference. It is also the wall a hand-edited settings file is held to — the grid is drawn over a
     * game, and a list of forty would bury it.
     */
    const val MAX_PINS: Int = 6

    /**
     * Turn a raw stored pin list into the one the grid actually draws.
     *
     * Applied in this order, and the order matters: drop what this device cannot do, then collapse
     * duplicates keeping the first occurrence (so the user's earliest placement wins and the order is
     * stable), then keep at most [MAX_PINS]. The cap comes last so it counts real, distinct, available pins
     * rather than being spent on a duplicate or an unavailable id that would never have shown.
     *
     * If nothing survives — an empty stored list, or every pin unavailable on this device — fall back to
     * [QuickToggle.DEFAULT_SET] filtered by the same predicate. That is the spec's "empty list falls back
     * to the defaults", and filtering the fallback too is what stops the defaults from re-introducing a
     * control the device cannot do. If even the defaults are all unavailable the result is honestly empty;
     * an empty grid the user can populate is better than a grid of controls that do nothing.
     *
     * Unknown ids are already gone by the time a list reaches here: they drop out at parse time via
     * [QuickToggle.of], which is why this works in [QuickToggle] and never has to see a raw string.
     */
    fun normalise(raw: List<QuickToggle>, isAvailable: (QuickToggle) -> Boolean): List<QuickToggle> {
        val kept = raw.asSequence()
            .filter(isAvailable)
            .distinct()
            .take(MAX_PINS)
            .toList()
        return kept.ifEmpty { QuickToggle.DEFAULT_SET.filter(isAvailable) }
    }

    /**
     * The stored names, resolved and normalised in one step.
     *
     * Two callers need exactly this and they must not disagree: the service builds the live grid from it,
     * and the settings screen draws the pin editor from it. If the editor showed the raw stored list while
     * the service showed the normalised one, the first tap on a reorder arrow would move a pin the user
     * was not looking at — or, with the fallback in play, appear to do nothing at all, because the six
     * defaults the sheet is showing are not in the stored list to be moved.
     *
     * The two differ only in their [isAvailable]: the service has a real device probe, the settings screen
     * has none and passes a predicate that accepts everything (see the note on that screen's card).
     */
    fun resolve(names: List<String>, isAvailable: (QuickToggle) -> Boolean): List<QuickToggle> =
        normalise(names.mapNotNull { QuickToggle.of(it) }, isAvailable)

    /**
     * Add a toggle to the pinned list for the pin editor.
     *
     * A no-op — the list back unchanged — in the three cases where adding would break a rule the grid
     * relies on: the list is already full ([MAX_PINS]), the toggle is already pinned (dedupe, so a second
     * tap cannot make two cells fight over one state), or the device cannot do it (no point pinning a
     * control that can only ever render disabled). Otherwise it goes on the end, because the end is where a
     * user who just added it expects to find it.
     */
    fun add(
        current: List<QuickToggle>,
        toggle: QuickToggle,
        isAvailable: (QuickToggle) -> Boolean,
    ): List<QuickToggle> = when {
        current.size >= MAX_PINS -> current
        toggle in current -> current
        !isAvailable(toggle) -> current
        else -> current + toggle
    }

    /** Remove a toggle from the pinned list. Absent toggle, list unchanged. */
    fun remove(current: List<QuickToggle>, toggle: QuickToggle): List<QuickToggle> =
        current - toggle

    /**
     * Move one pin along the grid. [delta] is -1 (towards the front) or +1 (towards the back); the ends are
     * walls, not a wrap.
     *
     * The exact semantics of `OverlayViewModel.moveStat`, on purpose: the two are the same gesture on two
     * lists, and a reorder that wrapped in one place and stopped in the other would be a bug the user feels
     * as inconsistency. A toggle that is not pinned, or a move that would fall off either end, returns the
     * list untouched.
     */
    fun move(current: List<QuickToggle>, toggle: QuickToggle, delta: Int): List<QuickToggle> {
        val list = current.toMutableList()
        val from = list.indexOf(toggle)
        val to = from + delta
        if (from < 0 || to !in list.indices) return current
        list.removeAt(from)
        list.add(to, toggle)
        return list
    }
}
