package com.gamecore.ui.games

import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.GameCardState
import com.gamecore.core.model.ProfileChip
import com.gamecore.core.model.ProfileClaim
import com.gamecore.core.model.gameCardState
import com.gamecore.core.model.gamesSubtitle
import com.gamecore.core.model.profileClaim
import com.gamecore.domain.gaming.ApplyDiagnostic
import com.gamecore.ui.components.PendingLaunch

/**
 * One detection decision as the Games screen prints it.
 *
 * [label] is resolved from the installed app at the moment the decision arrives, so a package the user
 * has never configured is still recognisable by name. [shouldTrack] and [reason] come from
 * [com.gamecore.domain.gaming.DetectionPolicy] unchanged — the screen never re-derives why a game was
 * or was not watched.
 */
data class DetectionDecisionView(
    val packageName: String,
    val label: String,
    val shouldTrack: Boolean,
    val reason: String,
    val atMillis: Long,
)

/** An app the user has silenced, with the launcher label when the app is still installed. */
data class ExcludedAppRow(
    val packageName: String,
    val label: String,
)

/**
 * The Games screen: the user's profiles, and whether the app can tell when one of them starts.
 *
 * The detection fields are as prominent here as the list is, because a screen full of carefully
 * configured profiles is worthless if nothing is watching for the games they belong to. §24A.2: the
 * detector's own [com.gamecore.core.model.DetectionAvailability] is resolved into a sentence and a
 * remedy before it reaches the screen, so the composable has no branch on a sealed platform type.
 */
data class GamesUiState(
    val profiles: List<GameRow> = emptyList(),
    /** False until the first emission, so "no profiles yet" is not shown over a list still loading. */
    val isLoaded: Boolean = false,
    val detectionAvailable: Boolean = false,
    val detectionNote: String? = null,
    val detectionRemedy: DetectionRemedy? = null,
    val autoApply: Boolean = true,
    /**
     * The user's §3 compact-density choice, so the gaps between cards tighten with the rest of the app.
     *
     * Carried in the state rather than read from a composition local because it comes from the same
     * settings flow the auto-apply switch does, and a screen that read one setting from the ViewModel and
     * another from a local would have two different ideas of when to recompose.
     */
    val isCompact: Boolean = false,
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
    /** The most recent foreground package the detector judged, or null before its first reading. */
    val lastDecision: DetectionDecisionView? = null,
    /** The most recent real application, automatic or manual, or null before the first one. */
    val lastApply: ApplyDiagnostic? = null,
    /** Apps the user has excluded from detection, ready for the removable list. */
    val excludedApps: List<ExcludedAppRow> = emptyList(),
    /** The profile the user pinned for a badge and top-of-list ordering. Null means none. */
    val pinnedPackage: String? = null,
) {
    val isEmpty: Boolean get() = isLoaded && profiles.isEmpty()

    val enabledCount: Int get() = profiles.count { it.isEnabled }

    /** The line under the title. The rule itself is pure and tested — see [gamesSubtitle]. */
    val subtitle: String get() = gamesSubtitle(isLoaded, profiles.size, enabledCount)

    /**
     * True when profiles exist, auto-apply is on, and nothing is watching for the games.
     *
     * The one combination worth a warning: the user has done the work and the feature is silently
     * inert. Profiles with auto-apply off are a deliberate manual arrangement, not a problem.
     */
    val isConfiguredButBlind: Boolean
        get() = profiles.isNotEmpty() && autoApply && !detectionAvailable
}

/**
 * One card in the list: what it says, and nothing the card does not draw.
 *
 * [isInstalled] is false for a profile whose game has been uninstalled. The card stays — the user's
 * configuration is theirs, and deleting it on their behalf because a game was removed for an update is
 * the kind of helpfulness nobody asks for twice — but it is drawn as dormant and says why.
 *
 * [chips] and [changesNothing] are carried **separately and on purpose**. They are two different
 * measurements of the same profile and they disagree on a fresh one: [com.gamecore.core.model.profileChips]
 * answers "what will the user see happen?" (an overlay counts) while
 * [com.gamecore.core.model.GameProfile.changesNothing] answers "would applying this write anything?" (an
 * overlay does not). The card needs both to be honest, so the row carries both rather than letting the
 * composable infer the second from the length of the first — see [ProfileClaim].
 */
data class GameRow(
    val packageName: String,
    val label: String,
    val isEnabled: Boolean,
    val isInstalled: Boolean,
    /** The profile's effects, from the same generator the Home hero uses. */
    val chips: List<ProfileChip>,
    /** Read straight off the profile. Never derived from [chips] being empty. */
    val changesNothing: Boolean,
    val isPlaying: Boolean,
    /** A pin is a badge and an ordering choice, never a claim that this game is running. */
    val isPinned: Boolean = false,
) {
    /** The word the card prints beside the dimming, so state is never conveyed by alpha alone. */
    val state: GameCardState get() = gameCardState(isEnabled, isInstalled, isPlaying)

    /** How much this profile may honestly claim to do. */
    val claim: ProfileClaim get() = profileClaim(chips.size, changesNothing)
}
