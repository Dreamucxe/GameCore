package com.gamecore.ui.games

import com.gamecore.core.common.Formatters
import com.gamecore.core.model.DetectionRemedy
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ScreenOrientationLock

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
    val busyPackage: String? = null,
    val message: String? = null,
) {
    val isEmpty: Boolean get() = isLoaded && profiles.isEmpty()

    val enabledCount: Int get() = profiles.count { it.isEnabled }

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
 * One row of the list: what it says, and nothing the row does not draw.
 *
 * [isInstalled] is false for a profile whose game has been uninstalled. The row stays — the user's
 * configuration is theirs, and deleting it on their behalf because a game was removed for an update is
 * the kind of helpfulness nobody asks for twice — but it is drawn as dormant and says why.
 */
data class GameRow(
    val packageName: String,
    val label: String,
    val isEnabled: Boolean,
    val isInstalled: Boolean,
    /** The profile's effect in one line: "120 Hz · Brightness 60% · Do not disturb". */
    val summary: String,
    val isPlaying: Boolean,
)

/**
 * What a profile will actually do, as a line of text.
 *
 * Pure, so the list's honesty is testable without a device: a profile that writes nothing produces the
 * "changes nothing" sentence, and there is no arrangement of the UI that turns it into a promise. Only
 * settings that are really written appear — a null field is absent from the line rather than shown as a
 * default, because "Brightness: default" reads as something the profile does.
 */
internal fun profileSummary(profile: GameProfile): String {
    val parts = buildList {
        profile.targetRefreshRate?.let { add(Formatters.hertz(it)) }
        profile.brightnessPercent?.let { add("Brightness $it%") }
        profile.mediaVolumePercent?.let { add("Volume $it%") }
        profile.rotationLock?.let { if (it != ScreenOrientationLock.CURRENT) add(it.label) }
        profile.screenTimeoutMillis?.let { add("Screen off after ${Formatters.durationCoarse(it)}") }
        if (profile.enableDoNotDisturb) add("Do not disturb")
        if (profile.performanceMode != PerformanceMode.BALANCED) add(profile.performanceMode.label)
        if (profile.useShizukuOptimizations) add("Shizuku")
        if (profile.showPerformancePill) add("Stats pill")
        if (profile.showFloatingButton) add("Floating button")
        if (profile.showCrosshair) add("Crosshair")
        if (profile.trackSession) add("Records a session")
    }
    return if (parts.isEmpty()) "Changes nothing yet" else parts.joinToString(" · ")
}
