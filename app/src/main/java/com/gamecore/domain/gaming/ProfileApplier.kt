package com.gamecore.domain.gaming

import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ProfileApplication
import com.gamecore.core.model.RestoreReport
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.system.DisplayReader
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.optimization.OptimizationRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns one [GameProfile] into the ordered list of changes it means, and carries them out.
 *
 * The translation is the whole job. A profile is a set of *wishes* — 90 Hz, 40% brightness, landscape,
 * Do Not Disturb on — and this class is where each wish becomes an [OptimizationRequest] or an
 * [OptimizationResult.Skipped] saying why it became nothing. Every profile-relevant action appears in
 * the result either way, so the report screen lists eleven rows with a status each rather than only
 * the ones that happened to run: "not requested" is information, and a row missing from a list is
 * not.
 *
 * **Null means leave it alone**, and that is honoured literally. A profile with
 * `brightnessPercent = null` produces a skip, not a write of some default — the alternative is an app
 * that dims a screen the user never asked it to touch.
 *
 * **The mode contributes, the fields decide.** [PerformanceMode.PERFORMANCE] adds a peak-rate pin,
 * battery saver off and animations off; [PerformanceMode.BATTERY_SAVER] adds a lowest-rate pin and
 * battery saver on; the other two add nothing, which is exactly what their own explanations say. An
 * explicit [GameProfile.targetRefreshRate] outranks whatever the mode would have pinned, because the
 * user naming a rate is more specific than the user naming a mood.
 *
 * **Nothing is applied concurrently.** The requests go through [OptimizationManager] one at a time in
 * list order, both because two settings writes racing through one provider is how a device ends up
 * with `min_refresh_rate` above `peak_refresh_rate`, and because the order is what the user watches
 * happen: the rate settles, then the screen dims.
 *
 * Restore is not this class's decision — [OptimizationManager] records every previous value before it
 * writes, so [restore] is a delegation and not a second copy of the undo logic.
 */
@Singleton
class ProfileApplier @Inject constructor(
    private val optimizations: OptimizationManager,
    private val displayReader: DisplayReader,
    private val shizuku: ShizukuManager,
) {

    /**
     * Applies [profile], one change at a time, and reports on all of them.
     *
     * [ProfileApplication.results] has one entry per profile-relevant change whether or not it was
     * attempted, so the caller can render the whole picture without knowing which fields were set.
     */
    suspend fun apply(
        profile: GameProfile,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProfileApplication {
        val rates = displayReader.supportedRates().valueOrNull.orEmpty()
        val steps = plan(profile, rates, shellLive = shizuku.isConnected)
        val results = mutableListOf<OptimizationResult>()
        for (step in steps) {
            results += when (step) {
                is Step.Attempt -> optimizations.apply(step.request, profile.packageName)
                is Step.Skip -> step.result
            }
        }
        return ProfileApplication(
            packageName = profile.packageName,
            profileLabel = profile.label,
            results = results,
            overlayRequested = profile.showFloatingButton ||
                profile.showPerformancePill ||
                profile.showCrosshair ||
                profile.hudLayoutId != null,
            sessionTracking = profile.trackSession,
            appliedAtMillis = nowMillis,
        )
    }

    /**
     * Puts back every setting any profile changed this session.
     *
     * A straight delegation. [OptimizationManager] captured each previous value before writing it, so
     * there is nothing profile-shaped left to undo here — a profile-aware restore would need a second
     * copy of that record and would get it wrong the first time two profiles touched one key.
     */
    suspend fun restore(): RestoreReport = optimizations.restoreAll()

    // ---------------------------------------------------------------------------- planning

    /** One line of the plan: something to try, or something deliberately not tried. */
    internal sealed interface Step {
        data class Attempt(val request: OptimizationRequest) : Step
        data class Skip(val result: OptimizationResult.Skipped) : Step
    }

    /**
     * The ordered plan for [profile], with no device writes and no suspension.
     *
     * Pure so §31 can assert the translation directly: that `BALANCED` with every field null plans
     * eight skips, that `PERFORMANCE` plans a peak pin even when the profile names no rate, that an
     * explicit rate outranks the mode's, and that a profile with the shell switched off skips the two
     * global-settings changes instead of failing them.
     *
     * [shellLive] and [supportedRates] are parameters rather than reads for the same reason — a test
     * can describe a 60 Hz-only phone with no Shizuku without needing one.
     */
    internal fun plan(
        profile: GameProfile,
        supportedRates: List<Float>,
        shellLive: Boolean,
    ): List<Step> = listOf(
        refreshStep(profile, supportedRates),
        brightnessStep(profile),
        rotationStep(profile),
        timeoutStep(profile),
        volumeStep(profile),
        doNotDisturbStep(profile),
        animationStep(profile, shellLive),
        batterySaverStep(profile, shellLive),
    )

    /**
     * The refresh-rate line: the profile's own rate if it names one, otherwise the mode's.
     *
     * The two pin actions run the same write once a rate is named — [OptimizationRequest.rateHz] is
     * the target and `pinMinimum` is always true for a profile — so choosing between them here only
     * decides which label the report shows. Nearest of the panel's two extremes is picked for that
     * reason: on a 60/120 panel a profile asking for 120 reads as "Pin highest refresh rate", which is
     * what the user did, and an intermediate 90 reads as the nearer of the two with the actual rate in
     * the detail line underneath.
     */
    private fun refreshStep(profile: GameProfile, supportedRates: List<Float>): Step {
        val explicit = profile.targetRefreshRate
        if (explicit != null) {
            return Step.Attempt(
                OptimizationRequest.refreshRate(pinActionFor(explicit, supportedRates), explicit),
            )
        }
        val fromMode = when (profile.performanceMode) {
            PerformanceMode.PERFORMANCE -> OptimizationAction.PIN_PEAK_REFRESH_RATE
            PerformanceMode.BATTERY_SAVER -> OptimizationAction.PIN_LOWEST_REFRESH_RATE
            // Both add nothing of their own, exactly as their explanations say.
            PerformanceMode.BALANCED, PerformanceMode.CUSTOM -> null
        }
        return fromMode?.let { Step.Attempt(OptimizationRequest.refreshRate(it)) }
            ?: skip(
                OptimizationAction.PIN_PEAK_REFRESH_RATE,
                "This profile leaves the refresh rate to the platform.",
            )
    }

    private fun pinActionFor(rateHz: Float, supportedRates: List<Float>): OptimizationAction {
        val lowest = supportedRates.minOrNull() ?: return OptimizationAction.PIN_PEAK_REFRESH_RATE
        val highest = supportedRates.maxOrNull() ?: return OptimizationAction.PIN_PEAK_REFRESH_RATE
        val nearerTheFloor = kotlin.math.abs(rateHz - lowest) < kotlin.math.abs(rateHz - highest)
        return if (nearerTheFloor) {
            OptimizationAction.PIN_LOWEST_REFRESH_RATE
        } else {
            OptimizationAction.PIN_PEAK_REFRESH_RATE
        }
    }

    private fun brightnessStep(profile: GameProfile): Step =
        profile.brightnessPercent
            ?.let { Step.Attempt(OptimizationRequest.brightness(it)) }
            ?: skip(OptimizationAction.SET_BRIGHTNESS, "This profile leaves brightness alone.")

    private fun rotationStep(profile: GameProfile): Step =
        profile.rotationLock
            ?.let { Step.Attempt(OptimizationRequest.rotation(it)) }
            ?: skip(OptimizationAction.LOCK_ROTATION, "This profile leaves auto-rotate alone.")

    private fun timeoutStep(profile: GameProfile): Step =
        profile.screenTimeoutMillis
            ?.let { Step.Attempt(OptimizationRequest.screenTimeout(it)) }
            ?: skip(
                OptimizationAction.EXTEND_SCREEN_TIMEOUT,
                "This profile leaves the screen timeout alone.",
            )

    private fun volumeStep(profile: GameProfile): Step =
        profile.mediaVolumePercent
            ?.let { Step.Attempt(OptimizationRequest.mediaVolume(it)) }
            ?: skip(OptimizationAction.SET_MEDIA_VOLUME, "This profile leaves the volume alone.")

    /**
     * Do Not Disturb is a boolean rather than a nullable, so "off" means "do not switch it on".
     *
     * It does not mean "switch it off". A profile turning DND off for a user who keeps it on
     * permanently would be changing a setting they never mentioned, and the restore at session end
     * puts the *previous* mode back either way.
     */
    private fun doNotDisturbStep(profile: GameProfile): Step =
        if (profile.enableDoNotDisturb) {
            Step.Attempt(OptimizationRequest.of(OptimizationAction.ENABLE_DO_NOT_DISTURB))
        } else {
            skip(
                OptimizationAction.ENABLE_DO_NOT_DISTURB,
                "This profile does not silence notifications.",
            )
        }

    // ------------------------------------------------------------------ global settings

    /**
     * Window animations, which only [PerformanceMode.PERFORMANCE] asks for.
     *
     * Gated on [GameProfile.useShizukuOptimizations] because `settings put global` is the only route
     * to the three animation scales and no permission an app can request reaches that namespace. A
     * user who left the shell switched off has said they do not want GameCore writing there, and the
     * honest response to that is a skip with the reason — not a [OptimizationResult.Blocked] offering
     * to set up Shizuku for something they have already declined.
     */
    private fun animationStep(profile: GameProfile, shellLive: Boolean): Step {
        if (profile.performanceMode != PerformanceMode.PERFORMANCE) {
            return skip(
                OptimizationAction.DISABLE_ANIMATIONS,
                "Only the Performance mode switches animations off.",
            )
        }
        return shellGated(profile, shellLive, OptimizationAction.DISABLE_ANIMATIONS)
    }

    /**
     * Battery saver, in whichever direction the mode wants it.
     *
     * [OptimizationAction.DISABLE_BATTERY_SAVER] is not a per-field profile action — the enum calls it
     * an undo step — but Performance mode's own explanation promises battery saver off, and it means
     * it: on most builds the saver caps the refresh rate, so pinning a peak rate while leaving it on
     * would be two settings fighting and GameCore reporting success for both.
     */
    private fun batterySaverStep(profile: GameProfile, shellLive: Boolean): Step {
        val action = when (profile.performanceMode) {
            PerformanceMode.PERFORMANCE -> OptimizationAction.DISABLE_BATTERY_SAVER
            PerformanceMode.BATTERY_SAVER -> OptimizationAction.ENABLE_BATTERY_SAVER
            PerformanceMode.BALANCED, PerformanceMode.CUSTOM -> return skip(
                OptimizationAction.ENABLE_BATTERY_SAVER,
                "This profile leaves Android's battery saver where it is.",
            )
        }
        return shellGated(profile, shellLive, action)
    }

    /**
     * The two-question gate every global-settings change passes through.
     *
     * Both answers are a skip rather than a failure, and they say different things because the user's
     * next move differs: the first is a profile setting they can turn on, the second is a service that
     * is not running. Neither is the device's fault, which is why neither is
     * [OptimizationResult.Failed].
     */
    private fun shellGated(
        profile: GameProfile,
        shellLive: Boolean,
        action: OptimizationAction,
    ): Step = when {
        !profile.useShizukuOptimizations -> skip(
            action,
            "This profile has the elevated-shell optimizations switched off, and this change " +
                "needs them — it writes a global setting no app permission reaches.",
        )

        !shellLive -> skip(
            action,
            "Shizuku is not connected, so the global setting this needs was left alone. Nothing " +
                "else in the profile was affected.",
        )

        else -> Step.Attempt(OptimizationRequest.of(action))
    }

    private fun skip(action: OptimizationAction, reason: String): Step =
        Step.Skip(OptimizationResult.Skipped(action, reason))
}
