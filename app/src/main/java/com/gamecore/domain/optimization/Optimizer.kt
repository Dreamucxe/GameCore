package com.gamecore.domain.optimization

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.Formatters
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import com.gamecore.core.model.RefreshRateOutcome
import com.gamecore.core.model.ScreenOrientationLock
import com.gamecore.core.shizuku.WritableSetting
import com.gamecore.core.system.ControlOutcome
import com.gamecore.core.system.DisplayControls
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.RefreshRateController
import com.gamecore.core.system.ScreenRotation
import com.gamecore.core.system.SettingsWriteOutcome

/**
 * One optimization to attempt, with the value it needs.
 *
 * Separate from [OptimizationAction] because the action is *what*, and this is *what to*. A profile
 * that sets brightness to 40% and one that sets it to 90% are the same action with different
 * arguments, and putting the argument in the enum would mean an enum entry per level.
 *
 * The value fields are nullable and unrelated to each other; only the one an action needs is read.
 * An action whose value is missing is [OptimizationResult.Skipped], never applied with a default —
 * writing an invented brightness because the caller forgot to say which is exactly the kind of
 * "helpful" behaviour that leaves a user's screen at a level they never chose.
 */
data class OptimizationRequest(
    val action: OptimizationAction,
    val percent: Int? = null,
    val rateHz: Float? = null,
    val timeoutMillis: Long? = null,
    val orientation: ScreenOrientationLock? = null,
) {
    companion object {
        fun of(action: OptimizationAction) = OptimizationRequest(action)

        fun brightness(percent: Int) =
            OptimizationRequest(OptimizationAction.SET_BRIGHTNESS, percent = percent)

        fun mediaVolume(percent: Int) =
            OptimizationRequest(OptimizationAction.SET_MEDIA_VOLUME, percent = percent)

        fun screenTimeout(millis: Long) =
            OptimizationRequest(OptimizationAction.EXTEND_SCREEN_TIMEOUT, timeoutMillis = millis)

        fun rotation(orientation: ScreenOrientationLock) =
            OptimizationRequest(OptimizationAction.LOCK_ROTATION, orientation = orientation)

        /** [rateHz] is only needed to pin a specific rate; the peak/lowest actions find their own. */
        fun refreshRate(action: OptimizationAction, rateHz: Float? = null) =
            OptimizationRequest(action, rateHz = rateHz)
    }
}

/**
 * One tier of the optimization engine: things it can do, and doing them.
 *
 * Two implementations, mirroring the tiered reader pattern the metrics layer already uses:
 * [StandardAndroidOptimizer] for what an app with granted permissions can write, and
 * [ShizukuOptimizer] for what needs the elevated shell. [OptimizationManager] picks between them per
 * action rather than per device, because a phone can perfectly well hold WRITE_SETTINGS while Shizuku
 * is not running, and half the list is still available in that state.
 *
 * [statusFor] asks the device rather than consulting a table. §29 is explicit about this and the
 * reason is in [OptimizationAction.requiredAccess]'s own documentation: `screen_brightness` is
 * writable with WRITE_SETTINGS on stock Android and refused on some OEM builds, so the only way to
 * know is to check the mechanism that would carry out the write.
 */
interface Optimizer {

    /** The access this tier operates with, for the UI's explanation of *why* something is blocked. */
    val accessLevel: AccessLevel

    /**
     * Whether this tier can carry out [action] right now.
     *
     * [CapabilityStatus.UNSUPPORTED] means the device cannot do it at all and no button should be
     * offered; the other two negative states each have their own remedy screen.
     */
    suspend fun statusFor(action: OptimizationAction): CapabilityStatus

    /**
     * Attempts one request.
     *
     * Never throws for an expected failure — a missing permission, a rejected value, a device that
     * ignored the write all come back as the corresponding [OptimizationResult]. The caller records
     * the restore point *before* calling this.
     */
    suspend fun apply(request: OptimizationRequest): OptimizationResult
}

/**
 * Turns a control-layer outcome into an engine result, preserving the applied/unverified split.
 *
 * The mapping is one-to-one and deliberately lossless in one direction: nothing here can turn
 * "written but not read back" into "applied". [ControlOutcome.Applied] already means the control
 * layer read the value back, which is why it maps to [OptimizationResult.Applied].
 */
internal fun ControlOutcome.toResult(
    action: OptimizationAction,
    appliedDetail: String? = null,
): OptimizationResult = when (this) {
    is ControlOutcome.Applied -> OptimizationResult.Applied(
        action = action,
        detail = appliedDetail ?: detail.ifEmpty { action.label },
    )
    is ControlOutcome.Unsupported -> OptimizationResult.Blocked(
        action = action,
        status = CapabilityStatus.UNSUPPORTED,
        detail = detail,
    )
    is ControlOutcome.RequiresAccess -> OptimizationResult.Blocked(
        action = action,
        status = if (needsShizuku) {
            CapabilityStatus.REQUIRES_SHIZUKU
        } else {
            CapabilityStatus.REQUIRES_PERMISSION
        },
        detail = detail,
    )
    is ControlOutcome.Failed -> OptimizationResult.Failed(action, detail)
}

/**
 * Turns a settings write into an engine result, without going through [ControlOutcome].
 *
 * [ControlOutcome.from] exists and is not used here, deliberately. It has four cases where
 * [SettingsWriteOutcome] has six, so it folds [SettingsWriteOutcome.AppliedUnverified] into
 * `Applied` with the reason in the detail string. That is a reasonable compromise for the control
 * layer, whose callers only need to know whether to re-read; it is not one this layer can make,
 * because [OptimizationResult.Unverified] is the case §24B's MediaTek paragraph exists to keep
 * distinct from success. Mapping straight from the write outcome keeps all six.
 *
 * [SettingsWriteOutcome.Rejected] becomes [OptimizationResult.Failed] rather than
 * [OptimizationResult.Blocked]: a value that failed its own [WritableSetting.accepts] check is a
 * GameCore bug, not something the device refused, and dressing it as a capability limit would blame
 * the phone for the app's mistake.
 */
internal fun SettingsWriteOutcome.toResult(
    action: OptimizationAction,
    appliedDetail: String? = null,
): OptimizationResult = when (this) {
    is SettingsWriteOutcome.Applied -> OptimizationResult.Applied(
        action = action,
        detail = appliedDetail ?: "${action.label}: now $value.",
    )
    is SettingsWriteOutcome.AppliedUnverified -> OptimizationResult.Unverified(
        action = action,
        detail = "${action.label} was written as $value, but could not be read back: $reason",
    )
    is SettingsWriteOutcome.NotHonoured -> OptimizationResult.NotHonoured(
        action = action,
        detail = "The device accepted $requested and stayed at $actual.",
    )
    is SettingsWriteOutcome.RequiresAccess -> OptimizationResult.Blocked(
        action = action,
        status = if (needsShizuku) {
            CapabilityStatus.REQUIRES_SHIZUKU
        } else {
            CapabilityStatus.REQUIRES_PERMISSION
        },
        detail = detail,
    )
    is SettingsWriteOutcome.Rejected -> OptimizationResult.Failed(action, detail)
    is SettingsWriteOutcome.Failed -> OptimizationResult.Failed(action, detail)
}

/**
 * The same for a refresh-rate change, where "not honoured" is a first-class case.
 *
 * [RefreshRateOutcome.NotHonoured] is the MediaTek behaviour §24B names: the write is accepted and
 * the panel stays where it was. It becomes [OptimizationResult.NotHonoured] — a problem shown in
 * red — and never anything softer, because the whole point of reading the rate back is to be able to
 * say this.
 */
internal fun RefreshRateOutcome.toResult(action: OptimizationAction): OptimizationResult =
    when (this) {
        is RefreshRateOutcome.Applied -> OptimizationResult.Applied(action, message)
        is RefreshRateOutcome.AppliedUnverified -> OptimizationResult.Unverified(action, message)
        is RefreshRateOutcome.NotHonoured -> OptimizationResult.NotHonoured(action, message)
        is RefreshRateOutcome.RateUnsupported -> OptimizationResult.Blocked(
            action = action,
            status = CapabilityStatus.UNSUPPORTED,
            detail = message,
        )
        is RefreshRateOutcome.RequiresAccess -> OptimizationResult.Blocked(
            action = action,
            status = if (needsShizuku) {
                CapabilityStatus.REQUIRES_SHIZUKU
            } else {
                CapabilityStatus.REQUIRES_PERMISSION
            },
            detail = message,
        )
        is RefreshRateOutcome.Failed -> OptimizationResult.Failed(action, message)
    }

/** The blocked result an optimizer returns for an action that is not its tier's business. */
internal fun OptimizationAction.blocked(
    status: CapabilityStatus,
    detail: String,
): OptimizationResult = OptimizationResult.Blocked(this, status, detail)

/** The skip for a request that arrived without the value it needs. */
internal fun OptimizationAction.missingValue(what: String): OptimizationResult =
    OptimizationResult.Skipped(this, "No $what was given, so nothing was changed.")

/** True when the panel advertises more than one rate, so there is something to pin at all. */
internal suspend fun DisplayReader.hasSelectableRates(): Boolean =
    supportedRates().valueOrNull.orEmpty().size >= 2

/**
 * The three writes that are the same operation whichever tier runs them.
 *
 * Brightness, orientation and screen timeout all go through [DisplayControls], which resolves a
 * mechanism per key through `SettingsWriter` and escalates to the elevated shell by itself when
 * WRITE_SETTINGS is absent. The write is therefore tier-independent: the standard tier claims these
 * while the app holds the permission, the Shizuku tier claims them when it does not, and both arrive
 * at exactly this code. One copy means the sentence the user reads cannot depend on which tier
 * happened to be picked.
 *
 * Returns null for every other action, so a tier can run this as a first pass and handle its own
 * business afterwards.
 */
internal suspend fun displayControlResult(
    request: OptimizationRequest,
    controls: DisplayControls,
    reader: DisplayReader,
): OptimizationResult? {
    val action = request.action
    return when (action) {
        OptimizationAction.SET_BRIGHTNESS -> {
            val percent = request.percent ?: return action.missingValue("brightness level")
            controls.setBrightnessPercent(percent)
                .toResult(action, "Brightness set to $percent%.")
        }

        OptimizationAction.LOCK_ROTATION -> {
            val wanted = request.orientation ?: return action.missingValue("orientation")
            controls.lockRotation(wanted.toRotation(reader.read()))
                .toResult(action, "Orientation locked: ${wanted.label.lowercase()}.")
        }

        OptimizationAction.EXTEND_SCREEN_TIMEOUT -> {
            val millis = request.timeoutMillis ?: return action.missingValue("timeout")
            controls.setScreenTimeout(millis)
                .toResult(action, "Screen timeout set to ${Formatters.durationCoarse(millis)}.")
        }

        else -> null
    }
}

/**
 * The three refresh-rate actions, likewise shared.
 *
 * [RefreshRateController] already picks between the plain settings write and the shell write per key,
 * so both tiers hand it the same request and differ only in whether they claim the action at all —
 * the standard tier when the settings pair is writable without the shell, the Shizuku tier otherwise.
 *
 * The target rate is read from the panel rather than taken from a constant, so a 90 Hz phone pins 90
 * and a 144 Hz phone pins 144 without either being told about the other. [OptimizationRequest.rateHz]
 * overrides it for the control panel's explicit rate buttons.
 */
internal suspend fun refreshRateResult(
    request: OptimizationRequest,
    controller: RefreshRateController,
    reader: DisplayReader,
): OptimizationResult? {
    val action = request.action
    val highest = when (action) {
        OptimizationAction.PIN_PEAK_REFRESH_RATE -> true
        OptimizationAction.PIN_LOWEST_REFRESH_RATE -> false
        OptimizationAction.RELEASE_REFRESH_RATE -> return controller.release().toResult(action)
        else -> return null
    }
    val rates = reader.supportedRates().valueOrNull
    val target = request.rateHz
        ?: (if (highest) rates?.maxOrNull() else rates?.minOrNull())
        ?: return action.blocked(
            status = CapabilityStatus.UNSUPPORTED,
            detail = "This display does not report any selectable refresh rates, so there is no " +
                "rate to pin.",
        )
    return controller.apply(target, pinMinimum = true).toResult(action)
}

/**
 * Maps the user's word for an orientation onto this device's `user_rotation` index.
 *
 * `ROTATION_0` is portrait on a phone and landscape on a tablet, so the constant cannot be hard-coded.
 * The current [reading] answers it: the natural orientation is portrait when the device is taller than
 * it is wide at an even rotation, or wider than tall at a quarter-turned one.
 *
 * Shared by both tiers because the mapping is a fact about the device, not about which mechanism does
 * the write.
 */
internal fun ScreenOrientationLock.toRotation(reading: DisplayReading): ScreenRotation {
    val current = when (reading.rotationDegrees) {
        90 -> ScreenRotation.QUARTER
        180 -> ScreenRotation.HALF
        270 -> ScreenRotation.THREE_QUARTER
        else -> ScreenRotation.NATURAL
    }
    if (this == ScreenOrientationLock.CURRENT) return current

    val quarterTurned = reading.rotationDegrees == 90 || reading.rotationDegrees == 270
    val tallerThanWide = reading.heightPixels > reading.widthPixels
    val naturallyPortrait = tallerThanWide != quarterTurned
    val portrait = if (naturallyPortrait) ScreenRotation.NATURAL else ScreenRotation.QUARTER
    val landscape = if (naturallyPortrait) ScreenRotation.QUARTER else ScreenRotation.NATURAL
    return if (this == ScreenOrientationLock.PORTRAIT) portrait else landscape
}
