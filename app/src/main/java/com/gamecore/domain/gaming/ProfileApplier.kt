package com.gamecore.domain.gaming

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.Observed
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.ChangeOrigin
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.CpuAffinityOutcome
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.DisplaySizeOutcome
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ProfileApplication
import com.gamecore.core.model.ResolutionScale
import com.gamecore.core.model.RestoreReport
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.SettingsWriteOutcome
import com.gamecore.data.repository.ColorPresetRepository
import com.gamecore.domain.color.ColorApplyResult
import com.gamecore.domain.color.ColorCorrectionController
import com.gamecore.domain.cpu.CpuAffinityController
import com.gamecore.domain.display.DisplaySizeController
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
 * the result either way, so the report screen lists ten rows with a status each rather than only
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
 * writes, so [restore] is a delegation and not a second copy of the undo logic. Three steps do not travel
 * through [OptimizationManager] and all three record their own restore rows in the same table: colour,
 * because the keys it writes depend on what the preset asks for; display size, because it writes no
 * settings key at all; and core affinity, because what it writes is not device state but one running
 * process. [restore] still puts all three back without knowing any of them exists.
 */
@Singleton
class ProfileApplier @Inject constructor(
    private val optimizations: OptimizationManager,
    private val displayReader: DisplayReader,
    private val shizuku: ShizukuManager,
    private val colorPresets: ColorPresetRepository,
    private val color: ColorCorrectionController,
    private val displaySize: DisplaySizeController,
    private val cpuAffinity: CpuAffinityController,
) {

    /**
     * Applies [profile], one change at a time, and reports on all of them.
     *
     * [ProfileApplication.results] has one entry per profile-relevant change whether or not it was
     * attempted, so the caller can render the whole picture without knowing which fields were set.
     *
     * [origin] is [ChangeOrigin.AUTOMATIC] when a game coming to the foreground caused this, and the
     * user's otherwise. It decides what happens to a setting the user has changed by hand since the
     * profile last wrote it: an automatic apply leaves it alone, and the user pressing "apply now" —
     * which is also how they re-arm a setting they took over — does not.
     */
    suspend fun apply(
        profile: GameProfile,
        nowMillis: Long = System.currentTimeMillis(),
        origin: ChangeOrigin = ChangeOrigin.USER,
    ): ProfileApplication {
        val rates = displayReader.supportedRates().valueOrNull.orEmpty()
        val steps = plan(profile, rates, shellLive = shizuku.isConnected)
        val results = mutableListOf<OptimizationResult>()
        for (step in steps) {
            results += when (step) {
                is Step.Attempt -> optimizations.apply(step.request, profile.packageName, origin)
                is Step.Colour -> applyColour(step.presetId, profile, origin)
                is Step.Stretch -> applyDisplaySize(DisplayTarget.Stretch(step.size), profile)
                is Step.Scale -> applyDisplaySize(DisplayTarget.Scale(step.scale), profile)
                is Step.Affinity -> applyAffinity(step.preset, profile)
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

        /**
         * The colour preset to load and apply, by id.
         *
         * An id rather than a resolved [ColorPreset] because [plan] is pure and reads nothing; the
         * row is fetched in [apply] where suspending is allowed. A separate variant rather than an
         * [Attempt] because this step does not go through [OptimizationManager] — see the class
         * documentation, and `OptimizationAction.isEngineAction`.
         */
        data class Colour(val presetId: Long) : Step

        /**
         * The size to stretch the display to.
         *
         * Carries the size itself rather than an id, because unlike a colour preset it is stored on the
         * profile and needs no row fetched to be acted on. A separate variant for the same reason
         * [Colour] is one: [com.gamecore.domain.display.DisplaySizeController] owns this write, its
         * read-back and its restore row — see `OptimizationAction.isEngineAction`.
         */
        data class Stretch(val size: DisplaySize) : Step

        /**
         * The resolution scale to run the display at.
         *
         * The second variant that bypasses [OptimizationManager] and the sibling of [Stretch], which it
         * shares every guard with. A separate variant rather than a resolved [DisplaySize] because the
         * *percentage* is the instruction: [DisplaySizeController] computes the size from the panel's own
         * physical resolution, and resolving it here would compute it from whatever size the display is
         * currently carrying — which, on a display GameCore already overrode, is its own leftover rather
         * than the panel's.
         *
         * [ResolutionScale.FULL] travels as a [Scale] rather than being turned into a skip or into a size
         * equal to the panel. It is a real reset-to-native request — the same thing
         * [com.gamecore.core.model.AspectPreset.NATIVE] is — and the controller routes it to
         * `wm size reset`, which is the only thing that removes an override rather than replacing it.
         */
        data class Scale(val scale: ResolutionScale) : Step

        /**
         * The core-affinity preset to pin the game's process to.
         *
         * The third variant that bypasses [OptimizationManager], and the only step in the plan whose
         * subject is the game itself rather than the device: it needs a process to exist, which is why
         * [plan] puts it last. Carries the preset and not a mask — the mask is computed from this
         * device's own core layout inside
         * [com.gamecore.domain.cpu.CpuAffinityController], which is also the only thing that knows
         * whether the layout can express the preset at all.
         */
        data class Affinity(val preset: CpuAffinityPreset) : Step
    }

    /**
     * The ordered plan for [profile], with no device writes and no suspension.
     *
     * Pure so §31 can assert the translation directly: that `BALANCED` with every field null plans
     * eleven skips, that `PERFORMANCE` plans a peak pin even when the profile names no rate, that an
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
        displaySizeStep(profile),
        brightnessStep(profile),
        colourStep(profile),
        rotationStep(profile),
        timeoutStep(profile),
        volumeStep(profile),
        doNotDisturbStep(profile),
        animationStep(profile, shellLive),
        batterySaverStep(profile, shellLive),
        affinityStep(profile),
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

    /**
     * The display size, second in the plan because the panel's own two properties come before anything
     * drawn on it: the rate settles, the display resizes, and only then is there a stable screen to dim.
     *
     * Not gated on [GameProfile.useShizukuOptimizations], unlike the two global-settings steps below. That
     * gate is for changes a *mode* adds silently — a user who picks Performance did not ask for animations
     * to be switched off by name, so a skip is the courteous reading of a shell they left switched off. A
     * display size is named by the user field by field, like a refresh rate or a colour preset and unlike
     * either of those; the honest answer when the shell is missing is
     * [OptimizationResult.Blocked] with the way to fix it, which is what the controller returns.
     *
     * One plan entry for two profile fields, because they are one `wm size` write.
     * [DisplayTarget.of] decides which — the resolution override, when a profile somehow holds both — and
     * the field that loses produces a skip naming it rather than disappearing from the report. Nothing
     * downstream has to know two fields exist, and no second step can be added that would silently
     * overwrite this one.
     */
    private fun displaySizeStep(profile: GameProfile): Step = when (val target = DisplayTarget.of(profile)) {
        is DisplayTarget.Stretch -> Step.Stretch(target.size)
        is DisplayTarget.Scale -> Step.Scale(target.scale)
        null -> skip(
            OptimizationAction.SET_DISPLAY_SIZE,
            "This profile leaves the display's own size alone.",
        )
    }

    private fun brightnessStep(profile: GameProfile): Step =
        profile.brightnessPercent
            ?.let { Step.Attempt(OptimizationRequest.brightness(it)) }
            ?: skip(OptimizationAction.SET_BRIGHTNESS, "This profile leaves brightness alone.")

    /**
     * The colour preset, placed straight after brightness because that is the order the user sees it
     * in: the panel dims, then the white point moves.
     *
     * Null is honoured as literally here as everywhere else. A profile with no preset produces a skip,
     * not a reset — resetting would mean GameCore taking the screen's colour away from whatever set it,
     * which on a great many phones is Android's own night-display schedule.
     */
    private fun colourStep(profile: GameProfile): Step =
        profile.colorPresetId
            ?.let { Step.Colour(it) }
            ?: skip(
                OptimizationAction.APPLY_COLOR_CORRECTION,
                "This profile leaves the screen's colour alone.",
            )

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

    // ------------------------------------------------------------------------- cpu affinity

    /**
     * The core-affinity line, and the last line in the plan.
     *
     * Last on purpose, and for a reason no other step has: every other change is made to the device and
     * would work with no game running at all, while this one is made to the game's own process. A game
     * that has just been brought to the foreground is still starting, so the later this runs the more
     * likely the process exists to be found — and if it does not, the controller says so rather than
     * writing anything.
     *
     * Not gated on [GameProfile.useShizukuOptimizations], for the same reason the display size is not: a
     * preset here is a field the user filled in by name, not something a performance mode added on their
     * behalf, so the honest answer when the shell is missing is [OptimizationResult.Blocked] with the way
     * to fix it. Null is the default and means what it says everywhere else in this class — leave the
     * scheduler alone.
     */
    private fun affinityStep(profile: GameProfile): Step =
        profile.cpuAffinity
            ?.let { Step.Affinity(it) }
            ?: skip(
                OptimizationAction.SET_CPU_AFFINITY,
                "This profile leaves it to Android to decide which cores the game runs on.",
            )

    /**
     * Hands the profile's preset to [CpuAffinityController] and reports what came back.
     *
     * The third step outside [OptimizationManager], and the one furthest from a settings write: there is
     * no key, and there is no device state either. The controller finds the game's process, records the
     * assignment it was already on in the same restore table [restore] drains, writes, and reads every
     * thread back before it will call anything applied.
     *
     * Two mappings are worth their own note. [CpuAffinityOutcome.NothingToRestore] cannot arrive from an
     * apply — it is what a restore of a game that has exited returns — and is enumerated rather than
     * folded into an `else` so a change to the outcome type surfaces here. And
     * [CpuAffinityOutcome.ProcessNotFound] is [OptimizationResult.Skipped] rather than a failure: the
     * game was still loading, nothing was written, and there is nothing for the user to fix. Reporting
     * it in red would make an ordinary race look like a broken feature.
     */
    private suspend fun applyAffinity(
        preset: CpuAffinityPreset,
        profile: GameProfile,
    ): OptimizationResult {
        val action = OptimizationAction.SET_CPU_AFFINITY
        return when (val outcome = cpuAffinity.apply(preset, profile.packageName)) {
            is CpuAffinityOutcome.Applied,
            is CpuAffinityOutcome.Restored,
            is CpuAffinityOutcome.NothingToRestore,
            -> OptimizationResult.Applied(action, outcome.message)

            // As with the display size: this outcome's own sentence already says the change could not be
            // confirmed, and Unverified appends "(not confirmed)" to whatever it is handed.
            is CpuAffinityOutcome.AppliedUnverified -> OptimizationResult.Unverified(
                action = action,
                detail = "Asked for ${outcome.preset?.label ?: preset.label} — ${outcome.reason}",
            )

            is CpuAffinityOutcome.NotHonoured -> OptimizationResult.NotHonoured(action, outcome.message)

            is CpuAffinityOutcome.ProcessNotFound -> OptimizationResult.Skipped(action, outcome.message)

            is CpuAffinityOutcome.PresetUnsupported -> OptimizationResult.Failed(action, outcome.message)

            is CpuAffinityOutcome.RequiresAccess -> OptimizationResult.Blocked(
                action = action,
                status = CapabilityStatus.REQUIRES_SHIZUKU,
                detail = outcome.message,
            )

            is CpuAffinityOutcome.Failed -> OptimizationResult.Failed(action, outcome.message)
        }
    }

    // ------------------------------------------------------------------------------ colour

    /**
     * Loads the profile's preset and hands it to [ColorCorrectionController].
     *
     * The one step that does not go through [OptimizationManager]. A preset writes between one and
     * eight of the display's colour keys depending on what it asks for, and the controller records
     * each one immediately before writing it; the manager's capture-then-write shape would have to
     * record all eight up front, leaving restore rows for keys this preset never touched — and the
     * revert at game exit would then hand back a colour-vision filter the user set themselves.
     *
     * A missing row is [OptimizationResult.Failed] rather than a skip. Deleting a preset detaches it
     * from every profile in the same call, so an id that resolves to nothing means something went
     * wrong rather than that the user changed their mind, and the report should say so.
     *
     * [origin] goes through to the controller for the same reason it goes to the manager: an automatic
     * apply must not write over a colour the user set from the shade during the last session, and an
     * apply the user asked for must not be second-guessed.
     */
    private suspend fun applyColour(
        presetId: Long,
        profile: GameProfile,
        origin: ChangeOrigin,
    ): OptimizationResult {
        val action = OptimizationAction.APPLY_COLOR_CORRECTION
        val preset = colorPresets.preset(presetId) ?: return OptimizationResult.Failed(
            action = action,
            detail = "This profile points at a colour preset that no longer exists, so the screen " +
                "was left as it was. Choose a preset again in the profile's colour field.",
        )
        return colourResult(
            action = action,
            preset = preset,
            result = color.apply(preset.correction, profile.packageName, origin),
        )
    }
    /**
     * One [ColorApplyResult] as one row of the profile report.
     *
     * Ordered by how loud the problem is rather than by how much of it succeeded, because a colour
     * preset is one change to the user even though it is several writes underneath. A device that
     * refused one of its keys has not applied the preset, and reporting "applied" because five of six
     * went through is the overclaim §24 exists to prevent.
     *
     * The plan's limits never turn a success into a failure. A phone with no per-channel gamma control
     * is not failing — it is a phone, and the preset's gamma had nowhere to go. That goes in the detail
     * so a user whose screen looks less changed than the sliders suggested is told why.
     */
    private fun colourResult(
        action: OptimizationAction,
        preset: ColorPreset,
        result: ColorApplyResult,
    ): OptimizationResult {
        val name = TextSanitizer.sanitizeForNotification(preset.name)
            .ifBlank { ColorPreset.DEFAULT_NAME }
        when (val access = result.access) {
            is Observed.Restricted -> return OptimizationResult.Blocked(
                action = action,
                status = if (access.unlockedBy == AccessLevel.SHIZUKU) {
                    CapabilityStatus.REQUIRES_SHIZUKU
                } else {
                    CapabilityStatus.REQUIRES_PERMISSION
                },
                detail = access.detail,
            )

            is Observed.Failed -> return OptimizationResult.Failed(action, access.detail)
            is Observed.Value -> Unit
        }
        val outcomes = result.results.map { it.outcome }
        val blocked = outcomes.filterIsInstance<SettingsWriteOutcome.RequiresAccess>().firstOrNull()
        return when {
            result.plan.writes.isEmpty() && result.plan.limits.isEmpty() ->
                OptimizationResult.Skipped(action, "$name changes nothing, so nothing was written.")

            result.plan.writes.isEmpty() -> OptimizationResult.NotHonoured(action, result.message)

            // Every sink the preset asks for is one the user has since set themselves. Nothing was
            // written and nothing failed, which is what a skip is: the device is in the state the most
            // recent instruction asked for, and that instruction was the user's.
            result.results.isEmpty() && result.keptByUser.isNotEmpty() ->
                OptimizationResult.Skipped(action, result.message)

            outcomes.any {
                it is SettingsWriteOutcome.Failed || it is SettingsWriteOutcome.Rejected
            } -> OptimizationResult.Failed(action, result.message)

            outcomes.any { it is SettingsWriteOutcome.NotHonoured } ->
                OptimizationResult.NotHonoured(action, result.message)

            blocked != null -> OptimizationResult.Blocked(
                action = action,
                status = if (blocked.needsShizuku) {
                    CapabilityStatus.REQUIRES_SHIZUKU
                } else {
                    CapabilityStatus.REQUIRES_PERMISSION
                },
                detail = result.message,
            )

            outcomes.any { it is SettingsWriteOutcome.AppliedUnverified } ->
                OptimizationResult.Unverified(action, colourDetail(name, result))

            else -> OptimizationResult.Applied(action, colourDetail(name, result))
        }
    }

    /** The applied line: the preset, how many keys it reached, what it left, and what it could not express. */
    private fun colourDetail(name: String, result: ColorApplyResult): String = buildString {
        append(name)
        append(": ")
        append(result.appliedCount)
        append(if (result.appliedCount == 1) " display setting" else " display settings")
        append(" changed")
        if (result.keptByUser.isNotEmpty()) {
            append(", ")
            append(result.keptByUser.size)
            append(" left as you set them")
        }
        if (result.plan.limits.isNotEmpty()) {
            append(", ")
            append(result.plan.limits.size)
            append(" of its values have no equivalent on this device")
        }
        append('.')
    }

    // ------------------------------------------------------------------------ display size

    /**
     * Hands the profile's display target to [DisplaySizeController] and reports what came back.
     *
     * The second step that does not go through [OptimizationManager], and the reason is simpler than
     * colour's: `wm size` is a window-manager command, not a `settings` write, so the manager's
     * capture-then-write shape has no key to capture. The controller records the size the display was
     * running under the restore table's non-setting namespace, which is the same table [restore] drains —
     * so this feature adds no second way to undo anything.
     *
     * The mapping is a translation and never a promotion. [DisplaySizeOutcome.isSuccess] is true for two
     * cases only, both of them read back from the device, and every other case lands somewhere the user
     * sees as unfinished: an override the display declined is [OptimizationResult.NotHonoured], a size
     * this panel will not take is [OptimizationResult.Failed] because the profile is asking for something
     * it will never get, and a missing shell is [OptimizationResult.Blocked] with the sentence that says
     * why and what unlocks it.
     *
     * A scale and a stretch share every arm below, because they share every outcome: the controller
     * resolves the percentage to a size before it writes, so by the time anything can go wrong the two
     * are the same request with different arithmetic behind them. Only the wording differs, and only
     * where the difference is the point — §B5's honest note that a smaller logical display does not
     * oblige a game to render fewer pixels belongs beside a *scale*, and saying it beside a stretched
     * aspect ratio would be answering a question the user did not ask.
     */
    private suspend fun applyDisplaySize(target: DisplayTarget, profile: GameProfile): OptimizationResult {
        val action = OptimizationAction.SET_DISPLAY_SIZE
        val outcome = when (target) {
            is DisplayTarget.Stretch -> displaySize.apply(target.size, profile.packageName)
            is DisplayTarget.Scale -> displaySize.apply(target.scale, profile.packageName)
        }
        return when (outcome) {
            // Restored cannot arrive from an apply — it is what a reset is confirmed by — and is mapped
            // rather than lumped into an `else` so that a change to the outcome type surfaces here.
            is DisplaySizeOutcome.Applied,
            is DisplaySizeOutcome.Restored,
            -> OptimizationResult.Applied(action, scaleDetail(target, outcome.message))

            // The one case whose own sentence is not reused. OptimizationResult.Unverified appends
            // "(not confirmed)" to whatever detail it is given, and this outcome's message already says
            // as much in words; twice over it reads like a stutter.
            is DisplaySizeOutcome.AppliedUnverified -> OptimizationResult.Unverified(
                action = action,
                detail = target.scaleNote() +
                    "Asked for ${outcome.requested?.label ?: "the display's native size"} — " +
                    outcome.reason,
            )

            is DisplaySizeOutcome.NotHonoured -> OptimizationResult.NotHonoured(
                action = action,
                detail = scaleDetail(target, outcome.message),
            )

            is DisplaySizeOutcome.SizeUnsupported -> OptimizationResult.Failed(action, outcome.message)

            is DisplaySizeOutcome.RequiresAccess -> OptimizationResult.Blocked(
                action = action,
                status = CapabilityStatus.REQUIRES_SHIZUKU,
                detail = outcome.message,
            )

            is DisplaySizeOutcome.Failed -> OptimizationResult.Failed(action, outcome.message)
        }
    }

    /**
     * The controller's sentence, with §B5's caveat in front of it when the request was a scale.
     *
     * The caveat is not a hedge on the result — the size *was* read back, and the sentence it precedes says
     * what it is. It answers the question a user asks next: the display reports a smaller size, and the
     * game's own frame may not have changed, because a logical display is a surface the compositor scales
     * and not an instruction to the game's renderer. Leaving that out would let the report imply a
     * performance win this feature cannot promise.
     */
    private fun scaleDetail(target: DisplayTarget, message: String): String =
        target.scaleNote() + message

    private fun DisplayTarget.scaleNote(): String = when (this) {
        is DisplayTarget.Scale ->
            "${scale.label} is the display size the system reports, not a render resolution — " +
                "a game that keeps its own render target will not draw fewer pixels. "
        is DisplayTarget.Stretch -> ""
    }
}
