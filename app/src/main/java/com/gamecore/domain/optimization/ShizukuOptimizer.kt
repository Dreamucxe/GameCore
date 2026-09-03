package com.gamecore.domain.optimization

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.shizuku.ShizukuManager
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.RefreshRateController
import com.gamecore.core.system.SettingsWriteOutcome
import com.gamecore.core.system.SettingsWriter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What GameCore can change once the elevated shell is available.
 *
 * Three things live only here — the animation scales and the two battery-saver directions — because
 * they are `settings put global` writes and no permission an app can request reaches that namespace.
 * The rest of this tier is a fallback: brightness, orientation, screen timeout and the refresh-rate
 * trio are the same writes [StandardAndroidOptimizer] performs, claimed here for the device where the
 * permission-only path is missing or refused. Both tiers hand them to the same
 * [DisplayControls]/[RefreshRateController] pair through [displayControlResult] and
 * [refreshRateResult], which resolve their own mechanism per key, so nothing in this file re-derives
 * how a write gets made.
 *
 * Two actions are deliberately not claimed. Media volume goes through `AudioManager` and Do Not
 * Disturb through the notification-policy API; the shell has no equivalent that works across builds,
 * and `SelfGrantablePermission` covers only usage access and WRITE_SETTINGS. They return
 * [CapabilityStatus.UNSUPPORTED] here, which is the truth *about this tier* — [OptimizationManager]
 * ranks tiers per action, so a user on a Shizuku device still sees them offered by the standard tier
 * rather than being told their phone cannot mute itself.
 *
 * Every write goes through [SettingsWriter], [RefreshRateController] or [DisplayControls], never
 * `ElevatedShell.execute`. §25 puts the shell behind the system layer and this class is on the domain
 * side of that line: it decides *what* to write, and the layer below owns the command, the value
 * grammar in [WritableSetting], and reading the result back.
 */
@Singleton
class ShizukuOptimizer @Inject constructor(
    private val settings: SettingsWriter,
    private val refreshRate: RefreshRateController,
    private val displayReader: DisplayReader,
    private val displayControls: DisplayControls,
    private val shizuku: ShizukuManager,
) : Optimizer {

    override val accessLevel: AccessLevel = AccessLevel.SHIZUKU

    override suspend fun statusFor(action: OptimizationAction): CapabilityStatus = when (action) {
        OptimizationAction.SET_MEDIA_VOLUME,
        OptimizationAction.ENABLE_DO_NOT_DISTURB,
        // Colour is a shell-level write and this tier still does not claim it: it is carried out by
        // ColorCorrectionController, which writes one to eight keys depending on the preset and
        // records each for restore as it goes. See OptimizationAction.isEngineAction.
        OptimizationAction.APPLY_COLOR_CORRECTION,
        // Nor the display size, and for a plainer reason: `wm size` is not a `settings` write at all,
        // so it does not go through SettingsWriter and there is no key for the manager to capture.
        // DisplaySizeController owns the command, the read-back and its own restore row.
        OptimizationAction.SET_DISPLAY_SIZE,
        -> CapabilityStatus.UNSUPPORTED

        // A 60 Hz-only panel is unsupported no matter who is asking; the shell cannot add a mode.
        OptimizationAction.PIN_PEAK_REFRESH_RATE,
        OptimizationAction.PIN_LOWEST_REFRESH_RATE,
        OptimizationAction.RELEASE_REFRESH_RATE,
        -> if (displayReader.hasSelectableRates()) shellStatus() else CapabilityStatus.UNSUPPORTED

        OptimizationAction.DISABLE_ANIMATIONS,
        OptimizationAction.ENABLE_BATTERY_SAVER,
        OptimizationAction.DISABLE_BATTERY_SAVER,
        OptimizationAction.SET_BRIGHTNESS,
        OptimizationAction.LOCK_ROTATION,
        OptimizationAction.EXTEND_SCREEN_TIMEOUT,
        -> shellStatus()
    }

    override suspend fun apply(request: OptimizationRequest): OptimizationResult {
        val action = request.action
        displayControlResult(request, displayControls, displayReader)?.let { return it }
        refreshRateResult(request, refreshRate, displayReader)?.let { return it }
        return when (action) {
            OptimizationAction.DISABLE_ANIMATIONS -> disableAnimations()

            OptimizationAction.ENABLE_BATTERY_SAVER -> batterySaver(on = true)

            OptimizationAction.DISABLE_BATTERY_SAVER -> batterySaver(on = false)

            OptimizationAction.SET_MEDIA_VOLUME -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "The elevated shell has no reliable way to set the media stream. " +
                    "GameCore uses the ordinary audio API for this instead.",
            )

            OptimizationAction.ENABLE_DO_NOT_DISTURB -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "Do Not Disturb is set through the notification-policy API, not through " +
                    "the shell. GameCore asks for policy access instead.",
            )

            OptimizationAction.APPLY_COLOR_CORRECTION -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "A colour preset is applied by GameCore's colour controller, which writes " +
                    "as many of the display's colour keys as the preset needs and records each one " +
                    "for restore. Routing it through here would capture all eight and hand back " +
                    "keys GameCore never touched.",
            )

            OptimizationAction.SET_DISPLAY_SIZE -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "A display size is set by GameCore's display-size controller, which runs " +
                    "the window-manager command, reads the size back, and keeps the record of what " +
                    "the display was before — an override that outlives a reboot needs an owner " +
                    "that does all three.",
            )

            // Routed by the two helpers above. Enumerated rather than folded into an `else` so that
            // adding an action to the enum fails the build here instead of silently returning this.
            OptimizationAction.SET_BRIGHTNESS,
            OptimizationAction.LOCK_ROTATION,
            OptimizationAction.EXTEND_SCREEN_TIMEOUT,
            OptimizationAction.PIN_PEAK_REFRESH_RATE,
            OptimizationAction.PIN_LOWEST_REFRESH_RATE,
            OptimizationAction.RELEASE_REFRESH_RATE,
            -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "GameCore could not route this change.",
            )
        }
    }

    /**
     * Sets all three animation scales to zero.
     *
     * Three separate writes with one answer, and the answer is the worst of the three. A device that
     * accepted two and ignored the third has not switched animations off — the transition the user
     * still sees is drawn by the scale that did not take — so reporting "applied" because the majority
     * succeeded would be exactly the overclaim §24 forbids. [worst] picks the outcome to report, and
     * the count in the applied message says how many keys were involved so the number is not a
     * mystery.
     */
    private suspend fun disableAnimations(): OptimizationResult {
        val action = OptimizationAction.DISABLE_ANIMATIONS
        val outcomes = ANIMATION_SCALES.map { settings.write(it, ANIMATION_OFF) }
        return worst(outcomes).toResult(
            action = action,
            appliedDetail = "Window animations are off (${outcomes.size} scales set to zero).",
        )
    }

    private suspend fun batterySaver(on: Boolean): OptimizationResult {
        val action = if (on) {
            OptimizationAction.ENABLE_BATTERY_SAVER
        } else {
            OptimizationAction.DISABLE_BATTERY_SAVER
        }
        val outcome = settings.write(WritableSetting.LOW_POWER, if (on) "1" else "0")
        return outcome.toResult(
            action = action,
            appliedDetail = if (on) {
                "Android's battery saver is on."
            } else {
                "Android's battery saver is off."
            },
        )
    }

    /**
     * Whether the shell is live.
     *
     * [CapabilityStatus.REQUIRES_SHIZUKU] rather than `UNSUPPORTED` when it is not: the write would
     * work, the authority is missing, and the Shizuku screen is one tap away. That is the distinction
     * [CapabilityStatus] exists to carry.
     */
    private fun shellStatus(): CapabilityStatus =
        if (shizuku.isConnected) CapabilityStatus.AVAILABLE else CapabilityStatus.REQUIRES_SHIZUKU

    private companion object {
        val ANIMATION_SCALES = listOf(
            WritableSetting.WINDOW_ANIMATION_SCALE,
            WritableSetting.TRANSITION_ANIMATION_SCALE,
            WritableSetting.ANIMATOR_DURATION_SCALE,
        )

        /** Written as a float because that is the form the platform stores and reads back. */
        const val ANIMATION_OFF = "0.0"
    }
}

/**
 * The outcome to report for a group of writes that together make one change.
 *
 * Ranked by how much the user needs to know, worst first: an access failure means nothing happened
 * and there is a button to fix it; a hard failure means something went wrong; a value the device
 * ignored is the loud honest case; an unverified write is a maybe. Only an all-[Applied] group
 * reports applied.
 *
 * A group is never averaged and never counted. "Two of three" is not a state the user can act on,
 * and no [OptimizationResult] case means it.
 */
internal fun worst(outcomes: List<SettingsWriteOutcome>): SettingsWriteOutcome =
    outcomes.maxByOrNull { severity(it) }
        ?: SettingsWriteOutcome.Failed("Nothing was written.")

private fun severity(outcome: SettingsWriteOutcome): Int = when (outcome) {
    is SettingsWriteOutcome.Applied -> 0
    is SettingsWriteOutcome.AppliedUnverified -> 1
    is SettingsWriteOutcome.NotHonoured -> 2
    is SettingsWriteOutcome.Failed -> 3
    is SettingsWriteOutcome.Rejected -> 4
    is SettingsWriteOutcome.RequiresAccess -> 5
}
