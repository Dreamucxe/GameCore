package com.gamecore.ui.overlay

import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.OverlayConfig
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.model.OverlayWindowState
import com.gamecore.core.model.overlaySubtitle
import com.gamecore.core.model.overlayWindowState
import com.gamecore.core.model.pillStatsSubtitle
import com.gamecore.core.overlay.QuickSheetPins
import com.gamecore.core.overlay.QuickToggle

// Aliased because the two properties below carry the same names as the functions behind them, and a
// property reading `intervalTenths(...)` inside its own getter is a sentence nobody should have to
// parse twice to be sure it is not a recursive call.
import com.gamecore.core.model.intervalLabel as labelForInterval
import com.gamecore.core.model.intervalTenths as tenthsOfInterval

/**
 * §7's floating button and §8's performance pill, as configured and as they actually are.
 *
 * [button] and [pill] are held whole, and that is not §24A.2's passthrough: both are the user's own
 * settings objects, every field of which is something this screen sets. What is deliberately *not*
 * carried whole is the platform's answer — [isButtonVisible] and [isPillVisible] come from the overlay
 * service's report rather than from the request that produced it. On a device where the overlay
 * permission was revoked while a game was running those two disagree, and a screen that showed the
 * request would be claiming a window that is not on screen.
 *
 * Every derived line on this class is one call into `core/model/OverlayLogic.kt` and nothing more. That
 * is the §11 rule applied to a settings screen: the words the user reads — the subtitle, the chip beside
 * each card, the sentence describing the pill's style — are claims, and a claim assembled in a `get()`
 * here or in a composable can only be checked by looking at a phone.
 */
data class OverlayUiState(
    val isLoaded: Boolean = false,
    val hasPermission: Boolean = false,
    val button: FloatingButtonConfig = FloatingButtonConfig(),
    val pill: OverlayConfig = OverlayConfig(),
    val isServiceRunning: Boolean = false,
    val isButtonVisible: Boolean = false,
    val isPillVisible: Boolean = false,
    /**
     * The other two windows, which this screen does not configure but does report on.
     *
     * Carried because "hide everything" is on this screen: a user reaching for it is entitled to know
     * that it will also take down a crosshair set up two screens away, and a card that offered the
     * button only when its own two windows were up would leave a crosshair with no way down from here.
     */
    val isCrosshairVisible: Boolean = false,
    val isHudVisible: Boolean = false,
    val isDrivenByProfile: Boolean = false,
    val drivingGameLabel: String = "",
    val statusSummary: String = OverlayStatus.OFF.summary,
    /**
     * The user's §3 compact-density choice, so the gaps between cards tighten with the rest of the app.
     *
     * Carried in the state rather than read from a composition local for the reason
     * [com.gamecore.ui.games.GamesUiState] carries it: it comes from the same settings flow the rest of
     * this screen's values come from, and a screen that read one setting from the ViewModel and another
     * from a local would have two different ideas of when to recompose.
     */
    val isCompact: Boolean = false,
    val message: String? = null,
) {
    /**
     * Whether a switch on this screen will do anything.
     *
     * False while a game's profile is driving the overlay: the profile decides what is on screen for the
     * length of that session, so a toggle flicked here would be overridden and the honest thing is to
     * lock it and say who is in charge. [pillState] and [buttonState] are the *words* for the same fact,
     * which is what keeps §10's "never status by a greyed-out control alone" true on this screen.
     */
    val canToggle: Boolean get() = hasPermission && !isDrivenByProfile

    /** How many of the four overlay windows the service says are up. Drives the header line. */
    val visibleWindowCount: Int
        get() = listOf(isButtonVisible, isPillVisible, isCrosshairVisible, isHudVisible).count { it }

    /** The line under the title. The rule itself is pure and tested — see [overlaySubtitle]. */
    val subtitle: String get() = overlaySubtitle(isLoaded, hasPermission, visibleWindowCount)

    /** The pill's one-word state, for its chip and for why its switch will not move. */
    val pillState: OverlayWindowState
        get() = overlayWindowState(isPillVisible, hasPermission, isDrivenByProfile)

    /** The button's, from the same function, so the two cards cannot word the same situation differently. */
    val buttonState: OverlayWindowState
        get() = overlayWindowState(isButtonVisible, hasPermission, isDrivenByProfile)

    val addableStats: List<HudStat> get() = HudStat.entries.filterNot { it in pill.stats }

    val isPillFull: Boolean get() = pill.stats.size >= OverlayConfig.MAX_STATS

    val hasStats: Boolean get() = pill.stats.isNotEmpty()

    /** "4 of 8, in drawing order" — the stats card's own subtitle, derived rather than formatted twice. */
    val statsSubtitle: String get() = pillStatsSubtitle(pill.stats.size)

    val isAnythingShown: Boolean
        get() = isButtonVisible || isPillVisible || isCrosshairVisible || isHudVisible

    /** Tenths of a second: the slider steps in integers, and 100 ms is the smallest step worth having. */
    val intervalTenths: Int get() = tenthsOfInterval(pill.updateIntervalMillis)

    val intervalLabel: String get() = labelForInterval(pill.updateIntervalMillis)

    /**
     * The quick sheet's pinned toggles, resolved and normalised through the same function the service
     * resolves them with — so the editor is always drawing the grid the sheet will actually show, fallback
     * and all.
     *
     * The availability predicate accepts everything, and that is a stated limitation rather than an
     * oversight. Whether a toggle can do anything on this device is answered by the probe that runs inside
     * the overlay service, and a settings screen has no way to reach it. Guessing from
     * [QuickToggle.isAlwaysAvailable] would be worse than saying nothing: it marks most of the set
     * unavailable on every phone and then refuses to pin three of the defaults. The card says so in a
     * sentence instead, and the sheet itself dims what it finds it cannot do.
     */
    val pinnedToggles: List<QuickToggle> get() = QuickSheetPins.resolve(pill.quickPins) { true }

    /** "4 of 6 pinned" — the quick sheet card's subtitle. */
    val pinsSubtitle: String get() = "${pinnedToggles.size} of ${QuickSheetPins.MAX_PINS} pinned"
}
