package com.gamecore.ui.home

import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.HomeStatus
import com.gamecore.core.model.OverlayStatus
import com.gamecore.core.model.ProfileChip
import com.gamecore.core.model.ProfileClaim
import com.gamecore.core.model.ShizukuState
import com.gamecore.core.model.WatchState
import com.gamecore.core.model.homeStatuses
import com.gamecore.core.model.homeSubtitle
import com.gamecore.core.model.profileClaim
import com.gamecore.core.model.watchState
import com.gamecore.domain.gaming.GamingState
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.PENDING
import com.gamecore.ui.components.PendingLaunch
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readoutOf

/**
 * What the Home screen shows besides the live figures.
 *
 * Deliberately small and already resolved: counts, states and sentences. The screen renders it without
 * asking a second question of anything, which is what keeps Home from being the place where eleven
 * subsystems are queried on every recomposition.
 *
 * §4's redesign added the hero to this. The screen now leads with one game rather than with a grid, so
 * the profile list itself reaches the UI — but as [HomeProfile], not as
 * [com.gamecore.core.model.GameProfile]: the hero draws a name, a package, a row of chips and a switch
 * state, and handing a composable the whole profile would hand it a crosshair preset id and a CPU
 * affinity mask it has no business reading (§24A.2).
 *
 * Every derived property below delegates to a pure function in `core/model` — [homeSubtitle],
 * [watchState], [homeStatuses], [profileClaim]. None of the words on this screen is decided in a
 * composable, which is what makes them testable without a device and what stops the same fact being
 * phrased two different ways in two places.
 */
data class HomeUiState(
    /** Every saved profile, in repository order. The hero is one of these, not a separate object. */
    val profiles: List<HomeProfile> = emptyList(),
    /** False until the profile repository has emitted once, so "none yet" is not shown over a load. */
    val isLoaded: Boolean = false,
    /**
     * The rule's answer to "which profile should be featured" — [com.gamecore.core.model.selectHero]'s
     * output, computed in the ViewModel because the rule needs the recorded session history.
     */
    val autoHeroPackage: String? = null,
    /**
     * The profile the user picked by tapping a pager dot, or null while the rule decides.
     *
     * Kept apart from [autoHeroPackage] so the two cannot fight. If the user's pick were written over
     * the rule's field, the next repository emission would recompute the rule and silently move the hero
     * out from under their thumb; if the rule were written over theirs, the same in reverse. Separate
     * fields make [hero]'s precedence explicit: the user's choice wins while it names a profile that
     * still exists, and the rule takes over again the moment it does not.
     */
    val selectedPackage: String? = null,
    val shizuku: ShizukuState = ShizukuState.NOT_INSTALLED,
    val overlay: OverlayStatus = OverlayStatus.OFF,
    val gaming: GamingState = GamingState.IDLE,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN,
    /** The stored "apply a profile when its game starts" setting, for the §4.1 pill. */
    val autoApply: Boolean = true,
    /** Whether anything on this device can actually see a game reach the foreground. */
    val detectionAvailable: Boolean = false,
    /** The §3 compact-density choice, so the gaps between cards tighten with the rest of the app. */
    val isCompact: Boolean = false,
    val repairedSessions: Int = 0,
    val outstandingRestores: Int = 0,
    val isRestoring: Boolean = false,
    /** The package whose profile is being applied by hand right now, or null. */
    val busyPackage: String? = null,
    /**
     * A launch the §C4 network check flagged, waiting on the user's answer.
     *
     * Non-null only while the warning sheet is up. The check never prevents a launch — this is a held
     * decision with three ways out, not a refusal — see
     * [com.gamecore.ui.components.PreLaunchWarningDialog].
     */
    val pendingLaunch: PendingLaunch? = null,
    val message: String? = null,
) {

    val profileCount: Int get() = profiles.size

    val hasOverlayPermission: Boolean get() = overlay.hasPermission

    /** True when the launch found something the user has to be told rather than merely know. */
    val hasStartupFindings: Boolean get() = repairedSessions > 0 || outstandingRestores > 0

    /** True only once the list has actually loaded and is actually empty — §4.2's "Add a game" case. */
    val isEmpty: Boolean get() = isLoaded && profiles.isEmpty()

    /**
     * The featured profile, or null when there is nothing to feature.
     *
     * Resolution order: the user's pick, then the rule's, then the first profile. That last fallback
     * covers one real path — [com.gamecore.core.model.selectHero] answers `Empty` when no profile is
     * enabled and none has ever been played, and a user with three switched-off profiles should see one
     * of them rather than the "no games yet" empty state, which would be a false statement about their
     * device.
     */
    val hero: HomeProfile?
        get() = profiles.firstOrNull { it.packageName == selectedPackage }
            ?: profiles.firstOrNull { it.packageName == autoHeroPackage }
            ?: profiles.firstOrNull()

    /**
     * Where the hero sits in [profiles], for the pager dots — or −1 when there is no hero.
     *
     * §4.2 requires the dot count to equal the real profile count, so the dots are drawn straight from
     * [profiles] and this is only which one is filled in. Nothing is padded and nothing is capped: a dot
     * with no profile behind it would be the screen inventing a game.
     */
    val heroIndex: Int
        get() = hero?.let { current ->
            profiles.indexOfFirst { it.packageName == current.packageName }
        } ?: -1

    /** The line under the title. The rule is pure and tested — see [homeSubtitle]. */
    val subtitle: String
        get() = homeSubtitle(
            isLoaded = isLoaded,
            profileCount = profileCount,
            playingLabel = gaming.gameLabel.takeIf { gaming.isTracking },
        )

    /** What the §4.1 "Automatic" pill says, and whether it is asking for attention. */
    val watch: WatchState
        get() = watchState(autoApply = autoApply, detectionAvailable = detectionAvailable)

    /** The four §4.5 tiles, already carrying their real values and their attention flags. */
    val statuses: List<HomeStatus>
        get() = homeStatuses(
            shizuku = shizuku,
            overlay = overlay,
            usableControlCount = capabilities.usableControlCount,
            controlCount = capabilities.controlCount,
            profileCount = profileCount,
        )
}

/**
 * One saved profile, as the §4.2 hero card draws it.
 *
 * The same shape as the Games screen's row, deliberately: one profile must not describe itself two ways
 * on two screens. [chips] comes from the shared [com.gamecore.core.model.profileChips] generator, and
 * [changesNothing] is read straight off the profile rather than inferred from [chips] being empty — the
 * two are different measurements and they disagree on a fresh profile, which raises the floating button
 * (one chip) while writing no device setting at all.
 *
 * [isInstalled] is false for a profile whose game has been uninstalled. The profile is kept, and the
 * card says so rather than offering a Play button that cannot work.
 */
data class HomeProfile(
    val packageName: String,
    val label: String,
    val isEnabled: Boolean,
    val isInstalled: Boolean,
    /** The profile's effects, from the same generator the Games cards use. */
    val chips: List<ProfileChip>,
    /** Read straight off the profile. Never derived from [chips] being empty. */
    val changesNothing: Boolean,
) {
    /** How much this profile may honestly claim to do. */
    val claim: ProfileClaim get() = profileClaim(chips.size, changesNothing)
}

/**
 * The live figures, already formatted, split the way §4 lays them out.
 *
 * §24A.2: the screen consumes strings, so the conversion from
 * [com.gamecore.core.model.PerformanceSnapshot] happens in the ViewModel and the snapshot itself never
 * reaches a composable. [metrics] and [device] are lists rather than named fields because the screen
 * lays each set out as a grid and has no reason to address one of them individually — and a list keeps
 * the pending set below identical in shape to a real one, so the grid does not reflow when the first
 * sample lands.
 *
 * The two are split because §4 puts them in different places: [metrics] are the five live tiles (§4.3)
 * and [device] is the collapsible Device card (§4.4), which holds the facts that do not change while the
 * user is looking at them.
 *
 * [memoryTrend] and [temperatureTrend] are the §4.3 sparklines. **Real samples only**, taken from the
 * monitor's existing stream while Home is on screen and thrown away when it is not — no new sampler, no
 * timer behind a hidden screen, nothing written to the database. A list with fewer than two entries
 * draws nothing rather than a flat line pretending to be a trend.
 */
data class HomeReadouts(
    /** CPU, RAM, Battery, Temperature, Refresh rate — §4.3, in that order. */
    val metrics: List<Readout>,
    /** Display and Storage — §4.4's collapsible card, in that order. */
    val device: List<Readout>,
    /** Memory used, as a fraction, oldest→newest. */
    val memoryTrend: List<Float> = emptyList(),
    /** Primary temperature in tenths of a degree, oldest→newest. */
    val temperatureTrend: List<Float> = emptyList(),
    /** Set only when the platform is actually throttling. Drawn as a banner, not as a tile. */
    val thermalNote: String? = null,
    val hasSample: Boolean = false,
) {
    companion object {
        /**
         * The grid before the first sample: the right labels, with `…` where the figures will be.
         *
         * Not an empty list and not a spinner. The tiles are the same size and in the same order as they
         * will be a second later, so the screen settles once rather than growing into place.
         *
         * The CPU tile is the exception §4.3 names: it never shows `…`. CPU use is a *rate*, so the
         * first sample cannot produce one — the tile would sit on an ellipsis for a whole interval with
         * nothing to explain it. It gets the em dash and the real reason instead, which is the treatment
         * every other unavailable reading in this app already gets.
         */
        val AWAITING = HomeReadouts(
            metrics = HomeLabels.METRICS.map { label ->
                if (label == HomeLabels.CPU) {
                    readoutOf(
                        label = label,
                        value = ABSENT,
                        detail = HomeLabels.MEASURING_CPU,
                        tone = Tone.Muted,
                    )
                } else {
                    readoutOf(label = label, value = PENDING, tone = Tone.Muted)
                }
            },
            device = HomeLabels.DEVICE.map { readoutOf(label = it, value = PENDING, tone = Tone.Muted) },
        )
    }
}

/** Home's tile labels, in the order §4.3 and §4.4 list them. */
internal object HomeLabels {
    const val CPU = "CPU"
    const val MEMORY = "RAM"
    const val BATTERY = "Battery"
    const val TEMPERATURE = "Temperature"
    const val REFRESH = "Refresh rate"
    const val DISPLAY = "Display"
    const val STORAGE = "Storage"

    /**
     * Why the CPU tile has no figure yet, in the user's terms.
     *
     * A reason rather than an ellipsis, per §4.3. It names the cause — a rate needs two readings before
     * it exists — so someone reading it knows the tile is working rather than stuck.
     */
    const val MEASURING_CPU = "Measuring. CPU use is a rate, so it needs a second reading."

    /** The five live tiles (§4.3). */
    val METRICS = listOf(CPU, MEMORY, BATTERY, TEMPERATURE, REFRESH)

    /** The two that belong in the Device card (§4.4). */
    val DEVICE = listOf(DISPLAY, STORAGE)
}
