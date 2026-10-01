package com.gamecore.core.overlay

import com.gamecore.core.model.DockActionId

/**
 * Where a press on one dock control actually goes, as a closed set (§3.7.1, feature 5).
 *
 * [QuickToggle.toOverlayAction] answers the same question for the quick sheet with a single
 * [OverlayAction], and it can, because every pin on that sheet is a full-panel tile wearing a shorter
 * label. The dock's fourteen controls are not all that shape. Eight of them are a panel action under
 * another name; five — the full-panel chip, the trigger-point placer and the three app screens — have no
 * [OverlayAction] at all; and Refresh has one whose tap does something the dock must specifically *not*
 * ask for (see [NextRefreshRate]). Returning a nullable [OverlayAction] and letting the service sort out
 * the nulls would put the interesting half of this mapping back in the service, where no JUnit test can
 * reach it. So the destination itself is the value, and all five kinds of destination are named here.
 *
 * Sealed rather than an enum because two of the branches carry a payload — which action, which screen —
 * and because that makes the service's `when` over the result exhaustive with no `else`, the discipline
 * [OverlayAction]'s own KDoc keeps and for the same reason: a route added here and not handled over there
 * fails to compile rather than becoming a chip that swallows the tap.
 *
 * What a route deliberately does **not** carry is any means of performing itself. No `Intent`, no window
 * token, no lambda — this file is pure Kotlin in `core.overlay`, so the mapping is pinned by plain JUnit on
 * a JVM, and the one thing that can open a window or leave the game stays the only thing that does.
 */
sealed interface DockRoute {

    /**
     * Fire the control panel's own [action], through the service's `onAction` and nothing else.
     *
     * The ordinary case, and the rule behind it is spec §0's — "the surface never decides what a control
     * does". A dock cell labelled Crosshair must do exactly what the full panel's Crosshair tile does, so
     * that the preference is written once and every other surface follows in lockstep. A dock that reached
     * past `onAction` to flip an overlay directly would be the bug this branch rules out: the overlay up,
     * the stored preference untouched, and the full panel's tile still drawn as off.
     */
    data class Overlay(val action: OverlayAction) : DockRoute

    /**
     * Close the dock panel, then open the floating button's full control panel.
     *
     * Its own route because there is no [OverlayAction] for it, and there should not be: the full panel is
     * a *window*, while [OverlayAction] is the vocabulary of things that happen *inside* one. Inventing an
     * entry for it would mean an action whose handler reached for a window from inside the panel it was
     * opening.
     *
     * Two things then have to happen, in order, and only the service can do either. The dock panel closes
     * first and the control panel opens second, because both are touchable windows watching for an outside
     * tap: leaving the first up would have the two fight over the same tap, and the dock panel would close
     * the control panel the user had just asked for.
     */
    data object FullPanel : DockRoute

    /**
     * Step the display to the next rate it supports, through the service's own rate-setting path.
     *
     * Deliberately **not** `Overlay(OverlayAction.REFRESH_RATE)`, and this is the trap the file was written
     * to close. [OverlayAction.REFRESH_RATE] is a toggle whose tap sets no rate at all: it flips
     * [OverlayPanelState.refreshRatesExpanded], revealing a row of rate chips *inside the full control
     * panel*. Routed from the dock, that tap would flip an expansion flag on a panel the dock is not
     * showing — the handler would run, state would change, and the user would be looking at a chip that
     * visibly does nothing. So the dock does the thing the chip row exists to let the user do, in one tap.
     *
     * The same trap is waiting for [OverlayAction.COLOR] and [OverlayAction.ASPECT] if either is ever added
     * to the dock's vocabulary. Both are grid toggles whose tap opens a second surface belonging to the
     * full panel rather than setting the state their plate reports — Aspect flips
     * [OverlayPanelState.aspectsExpanded] on a panel that is not open, and Colour closes that same
     * unopened panel on its way to the colour editor. Each would need a route of its own that *does the
     * thing*, exactly as this one does, and not the route that opens the row.
     */
    data object NextRefreshRate : DockRoute

    /**
     * Open the volume-trigger placement overlay over the running game (feature 4's in-game placement).
     *
     * Its own route for [FullPanel]'s reason, running the other way: it does not fire an existing action,
     * it puts a *new window* on screen — a placement layer the user taps to say where the volume button
     * should land. Nothing in the control panel's vocabulary adds a window, so folding this into an
     * [OverlayAction] would give the panel's `when` one branch that grows a surface while every other
     * branch changes the state of the surface it was called from.
     */
    data object TriggerPoint : DockRoute

    /**
     * Leave the game for one of GameCore's own screens — the three ids carrying
     * [DockActionId.opensAppScreen].
     *
     * The screen is named as a [DockScreen] and not as the token an `Intent` carries; [DockScreen]'s own
     * KDoc has the reasoning, and it is the reason this branch can be a unit test at all.
     */
    data class Screen(val screen: DockScreen) : DockRoute
}

/**
 * The screens of GameCore's own that a dock chip can swap the game out for (§3.7.1, feature 5).
 *
 * An enum of three rather than the destination token an `Intent` actually carries, and that is the whole
 * point of it. `core.overlay` is pure and Android-free, so plain JUnit can pin "the Extract chip opens the
 * extraction screen" with no instrumentation, no `Context` and no intent; carrying the token here instead
 * would drag `MainActivity`'s `EXTRA_DESTINATION` — and the closed set that extra is validated against —
 * down into a package that has no business knowing what an intent is.
 *
 * It also buys a *second* compile-time gate rather than spending the first one. [DockActionId.route] is
 * exhaustive over the fourteen ids, and the service's mapping from this enum to a destination token is
 * exhaustive over these three, so an id added there is a compile error here and a screen added here is a
 * compile error there. Handing a raw string across that boundary would turn both of those errors into the
 * same silent runtime shrug — an unrecognised token lands on Home, which is a chip that opens the wrong
 * screen rather than one that fails to build. Two gates, neither of which can quietly go stale.
 */
enum class DockScreen {
    /** The live RAM and CPU figures. */
    SYSTEM_STATS,

    /** The screen-extraction tools. */
    SCREEN_EXTRACTION,

    /** The touch-sampling-rate monitor. */
    TOUCH_SAMPLING,
}

/**
 * The route a press on one dock control takes (§3.7.1, feature 5) — the one place that correspondence is
 * written down.
 *
 * [DockActionId] knows only which controls exist, what to call them, which grid they sit in and whether one
 * leaves the game; what a press *does* is deliberately not on the enum, which is what lets it live in
 * `core.model` with no Android and no [OverlayAction] in sight. This function is the other half of that
 * split, and keeping it here — beside [OverlayAction], one package below the service — is what makes the
 * mapping something a unit test pins rather than something discovered by tapping fourteen chips on a phone.
 *
 * The `when` is exhaustive with **no `else`**, and that compile-time gate is the reason the file exists. A
 * [DockActionId] added without a branch here does not compile, which rules out precisely the failure
 * [QuickToggle.toOverlayAction] rules out for the quick sheet and §32 forbids everywhere: a control the
 * user placed, arranged and switched on, drawn in the panel with a label and a plate, that swallows the
 * tap. An `else` — even one that threw — would let that control compile and draw; the honest version of
 * "this control has no behaviour yet" is a build failure, not a dead chip over a match.
 *
 * Two branches absorb a rename rather than being wired through an alias table. Stats is drawn from
 * [OverlayAction.PILL] and Wheels from the singular [OverlayAction.WHEEL] — the dock's labels are the
 * user-facing words, the actions are the older internal ones — exactly as [QuickToggle.STATS] absorbs the
 * first of those two for the sheet. Absorbing it in the mapping is what keeps *both* stored vocabularies
 * stable: renaming either enum entry to match the other would rewrite either a stored dock arrangement or
 * a stored macro, since both persist by name.
 */
fun DockActionId.route(): DockRoute = when (this) {
    DockActionId.CROSSHAIR -> DockRoute.Overlay(OverlayAction.CROSSHAIR)
    DockActionId.STATS -> DockRoute.Overlay(OverlayAction.PILL)
    DockActionId.WHEELS -> DockRoute.Overlay(OverlayAction.WHEEL)
    DockActionId.HUD -> DockRoute.Overlay(OverlayAction.HUD)
    DockActionId.MAGNIFIER -> DockRoute.Overlay(OverlayAction.MAGNIFIER)
    DockActionId.SCOUT -> DockRoute.Overlay(OverlayAction.SCOUT)
    DockActionId.HUNT -> DockRoute.Overlay(OverlayAction.HUNT)
    DockActionId.SCREENSHOT -> DockRoute.Overlay(OverlayAction.SCREENSHOT)
    DockActionId.FULL_PANEL -> DockRoute.FullPanel
    DockActionId.REFRESH_RATE -> DockRoute.NextRefreshRate
    DockActionId.TRIGGER_POINT -> DockRoute.TriggerPoint
    DockActionId.SYSTEM_STATS -> DockRoute.Screen(DockScreen.SYSTEM_STATS)
    DockActionId.SCREEN_EXTRACTION -> DockRoute.Screen(DockScreen.SCREEN_EXTRACTION)
    DockActionId.TOUCH_SAMPLING -> DockRoute.Screen(DockScreen.TOUCH_SAMPLING)
}

// --------------------------------------------------------------------------------------------- rates

/**
 * How close two rates have to be to count as the same mode, in Hz.
 *
 * `Display.Mode.getRefreshRate()` returns a measured float — 59.95998, 120.00001 — and what comes back from
 * `RefreshRateController` is the value GameCore asked for, which is whatever the platform reported last time
 * it was asked. Equality on those two is a coin toss, so the comparison is a tolerance, and the tolerance is
 * far below the smallest gap between real modes (the tightest in the wild is 48/50 Hz) and far above the
 * float noise.
 */
private const val RATE_MATCH_HZ = 0.5f

/**
 * The rate one press of the dock's Refresh control should move the display to (§3.7.1, feature 5).
 *
 * The full control panel offers the rates as a row of chips with "leave alone" first, so the user picks a
 * rate and un-picks it in one tap each. The dock has one chip and no room for a row, so the same vocabulary
 * is walked instead of displayed: lowest rate, next, next, and off the top back to not holding one at all.
 * That last step is the load-bearing one. A cycle that only ever moved *up* and wrapped to the bottom would
 * leave a user who pinned 120 Hz from the dock with no way to stop pinning it without opening the full panel,
 * and `min_refresh_rate` left pinned is a battery cost that outlives the session — so "leave alone" has to be
 * reachable from the dock that set it.
 *
 * [current] is what GameCore last confirmed it pinned, which is null after a service restart even on a
 * display that is still held; the cycle then starts from the bottom, because inferring a pin from the rate
 * the display happens to be running is exactly the guess the service's `pinnedRate` refuses to make. A
 * [current] that is no longer in [offered] — a mode list that changed under us — also restarts from the
 * bottom rather than reporting an index it cannot justify.
 *
 * Pure, and sorted here rather than trusting the caller, so one JUnit test covers the wrap, the restart and
 * the stale pin without a display.
 */
fun nextDockRate(current: Float?, offered: List<Float>): Float? {
    val rates = offered.sorted()
    if (rates.isEmpty()) return null
    val index = current?.let { held -> rates.indexOfFirst { kotlin.math.abs(it - held) < RATE_MATCH_HZ } } ?: -1
    return when {
        index < 0 -> rates.first()
        index == rates.lastIndex -> null
        else -> rates[index + 1]
    }
}
