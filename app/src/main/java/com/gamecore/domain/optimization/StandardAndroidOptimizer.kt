package com.gamecore.domain.optimization

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.RefreshRateMechanism
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.system.AudioControls
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.RefreshRateController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What GameCore can change with permissions the user can grant in Settings.
 *
 * Five of the thirteen actions live here, and all five are things the platform genuinely lets an
 * ordinary app do: three `Settings.System` keys behind WRITE_SETTINGS, the media stream through
 * `AudioManager`, and Do Not Disturb through the notification-policy API. Nothing in this tier needs
 * ADB-level authority, which is why §13's "the app must work without Shizuku" is a real claim rather
 * than a disclaimer — a device with no Shizuku at all still gets these.
 *
 * The refresh rate is here too, but only when [RefreshRateController.mechanism] says the settings pair
 * is writable without the shell. That happens on builds that classify the keys as ordinary system
 * settings, and on those devices the standard tier can pin a rate properly. Everywhere else this tier
 * reports [CapabilityStatus.REQUIRES_SHIZUKU] and the manager moves on to [ShizukuOptimizer].
 *
 * Restore points are recorded by [OptimizationManager] before it calls this, so nothing here has to
 * remember anything. That split exists because the same write can arrive through either tier and the
 * previous value has to be captured exactly once, before the first attempt.
 */
@Singleton
class StandardAndroidOptimizer @Inject constructor(
    private val displayControls: DisplayControls,
    private val displayReader: DisplayReader,
    private val audio: AudioControls,
    private val refreshRate: RefreshRateController,
    private val permissions: PermissionChecker,
) : Optimizer {

    override val accessLevel: AccessLevel = AccessLevel.NORMAL

    override suspend fun statusFor(action: OptimizationAction): CapabilityStatus = when (action) {
        OptimizationAction.SET_BRIGHTNESS,
        OptimizationAction.LOCK_ROTATION,
        OptimizationAction.EXTEND_SCREEN_TIMEOUT,
        -> if (permissions.hasWriteSettings()) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.REQUIRES_PERMISSION
        }

        // Asked of the device rather than assumed: a build with no media stream reports no volume,
        // and there is no permission that would change that.
        OptimizationAction.SET_MEDIA_VOLUME ->
            if (audio.mediaVolumePercent().valueOrNull != null) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.UNSUPPORTED
            }

        OptimizationAction.ENABLE_DO_NOT_DISTURB ->
            if (permissions.hasNotificationPolicyAccess()) {
                CapabilityStatus.AVAILABLE
            } else {
                CapabilityStatus.REQUIRES_PERMISSION
            }

        OptimizationAction.PIN_PEAK_REFRESH_RATE,
        OptimizationAction.PIN_LOWEST_REFRESH_RATE,
        OptimizationAction.RELEASE_REFRESH_RATE,
        -> refreshRateStatus()

        // Global settings. `settings put global` is not reachable with WRITE_SETTINGS on any build.
        OptimizationAction.DISABLE_ANIMATIONS,
        OptimizationAction.ENABLE_BATTERY_SAVER,
        OptimizationAction.DISABLE_BATTERY_SAVER,
        -> CapabilityStatus.REQUIRES_SHIZUKU

        // Not this tier's, and not the other's either: see OptimizationAction.isEngineAction. The
        // profile's colour step goes straight to ColorCorrectionController, which owns the
        // projection and its own restore rows. Unsupported is the truth *about this tier*.
        OptimizationAction.APPLY_COLOR_CORRECTION -> CapabilityStatus.UNSUPPORTED

        // Nor this one, and it could not be this tier's in any case: `wm size` is a window-manager
        // command and there is no permission that hands it to an ordinary app. DisplaySizeController
        // owns it, and reports REQUIRES_SHIZUKU itself when the shell is not there.
        OptimizationAction.SET_DISPLAY_SIZE -> CapabilityStatus.UNSUPPORTED

        // Nor this one, and this tier could never be the one: changing another process's core
        // assignment is `sched_setaffinity` on a pid that is not ours, which no permission an app can
        // hold reaches. CpuAffinityController owns it, and needs a readable core layout on top of the
        // shell before it will offer a preset at all.
        OptimizationAction.SET_CPU_AFFINITY -> CapabilityStatus.UNSUPPORTED
    }

    override suspend fun apply(request: OptimizationRequest): OptimizationResult {
        val action = request.action
        displayControlResult(request, displayControls, displayReader)?.let { return it }
        refreshRateResult(request, refreshRate, displayReader)?.let { return it }
        return when (action) {
            OptimizationAction.SET_MEDIA_VOLUME -> {
                val percent = request.percent ?: return action.missingValue("volume level")
                audio.setMediaVolumePercent(percent)
                    .toResult(action, "Media volume set to $percent%.")
            }

            OptimizationAction.ENABLE_DO_NOT_DISTURB ->
                audio.setDoNotDisturb(true).toResult(action, "Do Not Disturb is on.")

            OptimizationAction.DISABLE_ANIMATIONS,
            OptimizationAction.ENABLE_BATTERY_SAVER,
            OptimizationAction.DISABLE_BATTERY_SAVER,
            -> action.blocked(
                status = CapabilityStatus.REQUIRES_SHIZUKU,
                detail = "This changes a global setting, which needs the elevated shell. " +
                    "No permission GameCore can ask you for unlocks it.",
            )

            OptimizationAction.APPLY_COLOR_CORRECTION -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "A colour preset is applied by GameCore's colour controller, not by the " +
                    "optimization tiers — it writes a different number of keys depending on what " +
                    "the preset asks for, and each one is recorded for restore individually.",
            )

            OptimizationAction.SET_DISPLAY_SIZE -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "A display size is set by GameCore's display-size controller, not by the " +
                    "optimization tiers — it runs a window-manager command, reads the size back, " +
                    "and keeps its own record of what the display was before.",
            )

            OptimizationAction.SET_CPU_AFFINITY -> action.blocked(
                status = CapabilityStatus.UNSUPPORTED,
                detail = "Which cores a game runs on is set by GameCore's affinity controller, not " +
                    "by the optimization tiers — it has to find the game's process first, and it " +
                    "needs the elevated shell either way.",
            )

            // Handled by the two helpers above; unreachable, and left as a branch rather than an
            // `else` so adding an action to the enum breaks the build here instead of silently
            // falling into a wrong answer.
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
     * Whether a rate can be pinned without the shell.
     *
     * A single-mode panel is [CapabilityStatus.UNSUPPORTED] — §31's 60 Hz-only device, where there is
     * nothing to switch to and no button should be offered. A multi-rate panel with no usable write
     * path is [CapabilityStatus.REQUIRES_SHIZUKU] rather than unsupported: the keys exist on the
     * device and are writable through the shell, so the honest answer comes with a way forward.
     */
    private suspend fun refreshRateStatus(): CapabilityStatus {
        if (!displayReader.hasSelectableRates()) return CapabilityStatus.UNSUPPORTED
        return when (refreshRate.mechanism()) {
            RefreshRateMechanism.SYSTEM_SETTINGS -> CapabilityStatus.AVAILABLE
            RefreshRateMechanism.SHIZUKU_SETTINGS,
            RefreshRateMechanism.NONE,
            // Only ever affects GameCore's own window, so it cannot pin a rate for a game.
            RefreshRateMechanism.WINDOW_PREFERENCE,
            -> CapabilityStatus.REQUIRES_SHIZUKU
        }
    }
}
