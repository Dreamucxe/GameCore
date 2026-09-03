package com.gamecore.core.system

import com.gamecore.core.common.Observed
import com.gamecore.core.common.RestrictionReason
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorField
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.GammaMode
import com.gamecore.core.shizuku.WritableSetting

/**
 * The map from what the user asked for to what this device can actually do.
 *
 * This is the honest core of the colour feature and the reason the sliders are allowed
 * to exist at all. Android gives an app with ADB-level authority five colour sinks, and
 * not one of them is a colour matrix:
 *
 *  * **Night display** — a warm shift with a colour temperature. It cannot cool: the
 *    platform clamps the temperature to `config_nightDisplayColorTemperatureMin/Max`,
 *    and the maximum is warmer than daylight on every build.
 *  * **Display colour mode** — natural, boosted or saturated, on devices that have more
 *    than one. Three steps, not a percentage.
 *  * **The daltonizer** — greyscale and the three colour-vision corrections, applied in
 *    the compositor. The one part of this that is exactly what it claims to be.
 *  * **Reduce bright colours** — output dimming below the panel's own minimum, API 31+.
 *  * **Colour inversion** — on or off.
 *
 * A per-channel gain, a gamma curve, a contrast or a hue rotation has no sink whatsoever.
 * `SurfaceControl.setDisplayColorTransform` and `ColorDisplayManager`'s matrix API are
 * hidden platform surfaces that WRITE_SECURE_SETTINGS does not open and that a uid-2000
 * shell cannot call, and the vendor `persist.sys.sf.*` properties would need `setprop`,
 * which this app has no path to at any privilege level.
 *
 * So [plan] returns two lists. [ColorPlan.writes] is what will be written, read back and
 * confirmed. [ColorPlan.limits] is every field the user set that this device has no way
 * to honour, each carrying an [Observed.Restricted] with the specific reason — which is
 * §24's "never claim an unsupported API works" turned into a value the UI can render
 * beside the slider that caused it.
 *
 * Pure, and deliberately free of Android types: [plan] takes the API level as an
 * argument rather than reading `Build.VERSION`, so every projection decision is unit
 * tested on the JVM.
 */
object ColorProjection {

    /**
     * Every key this feature is able to touch.
     *
     * The controller needs the whole list, not just the engaged part of it: turning a
     * correction off means restoring each sink that a previous correction engaged, and
     * the only way to know which those were is to check them all.
     */
    val ALL_SINKS: List<WritableSetting> = listOf(
        WritableSetting.NIGHT_DISPLAY_ACTIVATED,
        WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE,
        WritableSetting.DISPLAY_COLOR_MODE,
        WritableSetting.DALTONIZER_ENABLED,
        WritableSetting.DALTONIZER_MODE,
        WritableSetting.REDUCE_BRIGHT_COLORS_ACTIVATED,
        WritableSetting.REDUCE_BRIGHT_COLORS_LEVEL,
        WritableSetting.COLOR_INVERSION_ENABLED,
    )

    /**
     * Splits one correction into the writes this device will honour and the fields it
     * will not.
     *
     * Nothing here is speculative. A sink appears in [ColorPlan.writes] only when the
     * user has asked for something it can actually express, so a correction that only
     * moves gamma produces no writes at all and four limits — which is the truthful
     * answer and, in §24's terms, the difference between explaining a restriction and
     * pretending an API exists.
     */
    fun plan(correction: ColorCorrection, apiLevel: Int): ColorPlan {
        val wanted = correction.normalised()
        val writes = mutableListOf<ColorWrite>()
        val limits = mutableListOf<ColorLimit>()
        projectWhitePoint(wanted, writes, limits)
        projectSaturation(wanted, writes, limits)
        projectVisionFilter(wanted, writes)
        projectDimming(wanted, apiLevel, writes, limits)
        projectInversion(wanted, writes)
        projectUnreachable(wanted, limits)
        return ColorPlan(
            writes = writes.toList(),
            limits = limits.sortedBy { it.field.ordinal },
        )
    }

    /**
     * Red against blue becomes the night display's colour temperature.
     *
     * The night display is a white-point control, so only the *difference* between the
     * red and blue gains has anywhere to go. Raising or lowering both together is a
     * change in level, which is reported as a limit against whichever channel the user
     * moved — and reported even when part of the same request does apply, because a
     * partially honoured slider that says nothing is the same lie as an unhonoured one.
     *
     * The cool direction is unreachable at any privilege level GameCore uses.
     * `ColorDisplayService` clamps the temperature to the values in
     * `config_nightDisplayColorTemperatureMin/Max`, and the maximum is warmer than
     * daylight on every build — writing 8000 K gets 6500 K or less, silently.
     */
    private fun projectWhitePoint(
        correction: ColorCorrection,
        writes: MutableList<ColorWrite>,
        limits: MutableList<ColorLimit>,
    ) {
        val level = correction.redGain + correction.blueGain
        if (level != 0) {
            for (field in listOf(ColorField.RED_GAIN, ColorField.BLUE_GAIN)) {
                if (correction.valueOf(field) == 0) continue
                limits += ColorLimit(
                    field = field,
                    reason = platform(
                        "Only the difference between red and blue reaches the display, as " +
                            "the night display's white point. Moving a channel's overall " +
                            "level has no setting behind it.",
                    ),
                )
            }
        }

        val warmth = (correction.redGain - correction.blueGain) / 2
        when {
            warmth >= MIN_WARMTH -> {
                val kelvin = (NEUTRAL_KELVIN - warmth * (NEUTRAL_KELVIN - WARMEST_KELVIN) / 100)
                    .coerceIn(WARMEST_KELVIN, NEUTRAL_KELVIN)
                writes += ColorWrite(
                    setting = WritableSetting.NIGHT_DISPLAY_ACTIVATED,
                    value = "1",
                    because = "the night display is the only white-point control Android " +
                        "exposes to an app",
                )
                writes += ColorWrite(
                    setting = WritableSetting.NIGHT_DISPLAY_COLOR_TEMPERATURE,
                    value = kelvin.toString(),
                    because = "$warmth% warmer is ${kelvin}K on the night display's scale",
                )
            }

            warmth <= -MIN_WARMTH -> limits += ColorLimit(
                field = if (correction.blueGain > 0) ColorField.BLUE_GAIN else ColorField.RED_GAIN,
                reason = platform(
                    "The night display can only warm the screen. Android clamps its " +
                        "temperature to this device's own range, and the coolest end of " +
                        "that range is still warmer than daylight, so there is no cool " +
                        "direction to write.",
                ),
            )
        }
    }

    /**
     * Saturation lands on two different sinks, neither of them continuous.
     *
     * Full desaturation is the daltonizer's monochromacy mode, which is genuinely
     * greyscale. Increases land on `display_color_mode`, which has three steps on the
     * devices that have any — so +30% and +45% both become "boosted", and the write
     * carries that in its own [ColorWrite.because] rather than pretending to a
     * percentage. Between grey and natural there is nothing at all.
     *
     * A device with a single colour mode ignores the write. That is not detected here:
     * `config_availableColorModes` is not readable, so the honest answer comes from
     * [SettingsWriter]'s read-back, which reports `NotHonoured` for a value the platform
     * accepted and discarded.
     */
    private fun projectSaturation(
        correction: ColorCorrection,
        writes: MutableList<ColorWrite>,
        limits: MutableList<ColorLimit>,
    ) {
        val saturation = correction.saturation
        when {
            saturation <= GREYSCALE_AT -> if (correction.visionFilter != ColorVisionFilter.NONE) {
                limits += ColorLimit(
                    field = ColorField.SATURATION,
                    reason = platform(
                        "The compositor applies one colour-vision correction at a time and " +
                            "${correction.visionFilter.label} is using it. Greyscale needs " +
                            "the same slot.",
                    ),
                )
            } else {
                writes += daltonizerWrites(
                    mode = MODE_MONOCHROMACY,
                    because = "greyscale is the daltonizer's monochromacy mode",
                )
            }

            saturation < -DEAD_ZONE -> limits += ColorLimit(
                field = ColorField.SATURATION,
                reason = platform(
                    "Android has greyscale at −100% and boosted colour above natural, and " +
                        "nothing in between: partial desaturation would need a colour " +
                        "matrix, which no app-reachable API sets.",
                ),
            )

            saturation >= SATURATED_AT -> writes += ColorWrite(
                setting = WritableSetting.DISPLAY_COLOR_MODE,
                value = MODE_SATURATED.toString(),
                because = "the saturated colour mode is this device's nearest step to " +
                    "+$saturation%",
            )

            saturation > DEAD_ZONE -> writes += ColorWrite(
                setting = WritableSetting.DISPLAY_COLOR_MODE,
                value = MODE_BOOSTED.toString(),
                because = "the boosted colour mode is this device's nearest step to " +
                    "+$saturation%",
            )
        }
    }

    /**
     * The colour-vision corrections, which are the one part of this feature that is
     * exactly what it says it is: the compositor applies the same transform Android's
     * own accessibility screen applies, at the same fidelity.
     *
     * Reached only when greyscale has not already claimed the slot — [projectSaturation]
     * runs first and reports the collision as a limit on saturation, so the two paths
     * can never both write [WritableSetting.DALTONIZER_MODE].
     */
    private fun projectVisionFilter(
        correction: ColorCorrection,
        writes: MutableList<ColorWrite>,
    ) {
        if (correction.saturation <= GREYSCALE_AT) return
        val mode = daltonizerMode(correction.visionFilter) ?: return
        writes += daltonizerWrites(
            mode = mode,
            because = "${correction.visionFilter.label} is a daltonizer mode, applied by " +
                "the compositor",
        )
    }

    /**
     * A negative brightness offset becomes reduce-bright-colours, which dims below the
     * panel's own minimum. There is no positive direction: nothing available to an app
     * drives the panel above its maximum, and claiming otherwise with a write that does
     * nothing is exactly what this projection exists to prevent.
     */
    private fun projectDimming(
        correction: ColorCorrection,
        apiLevel: Int,
        writes: MutableList<ColorWrite>,
        limits: MutableList<ColorLimit>,
    ) {
        val offset = correction.brightnessOffset
        when {
            offset == 0 -> Unit

            offset < 0 && apiLevel >= REDUCE_BRIGHT_COLORS_API -> {
                val level = (-offset).coerceIn(0, 100)
                writes += ColorWrite(
                    setting = WritableSetting.REDUCE_BRIGHT_COLORS_ACTIVATED,
                    value = "1",
                    because = "extra dimming below the panel's own minimum",
                )
                writes += ColorWrite(
                    setting = WritableSetting.REDUCE_BRIGHT_COLORS_LEVEL,
                    value = level.toString(),
                    because = "$level% of the platform's dimming range",
                )
            }

            offset < 0 -> limits += ColorLimit(
                field = ColorField.BRIGHTNESS_OFFSET,
                reason = newerApi(
                    "Reduce bright colours, the only dimming below the panel's minimum, " +
                        "arrived in Android 12.",
                ),
            )

            else -> limits += ColorLimit(
                field = ColorField.BRIGHTNESS_OFFSET,
                reason = platform(
                    "Nothing reachable from an app drives the panel above its own maximum. " +
                        "The brightness slider already covers that whole range.",
                ),
            )
        }
    }

    private fun projectInversion(
        correction: ColorCorrection,
        writes: MutableList<ColorWrite>,
    ) {
        if (!correction.invertColors) return
        writes += ColorWrite(
            setting = WritableSetting.COLOR_INVERSION_ENABLED,
            value = "1",
            because = "colour inversion is a compositor transform Android exposes as a " +
                "secure setting",
        )
    }

    /**
     * The four controls with no sink at all.
     *
     * Green gain, gamma, contrast and hue rotation each need a 4×5 colour matrix handed
     * to `SurfaceControl.setDisplayColorTransform` or to `ColorDisplayManager`'s
     * matrix API. Both are hidden platform surfaces: WRITE_SECURE_SETTINGS does not open
     * them, they are unreachable from a uid-2000 shell, and the vendor properties that
     * some SoCs expose would need `setprop`, which GameCore has no path to.
     *
     * They remain in the model, in presets, in profiles and in the session log, because
     * a saved preset should survive a move to a device or a ROM that can honour more of
     * it. What they do not do is silently no-op: each one the user has moved comes back
     * here as a limit the UI renders beside the slider that caused it.
     */
    private fun projectUnreachable(
        correction: ColorCorrection,
        limits: MutableList<ColorLimit>,
    ) {
        if (correction.greenGain != 0) {
            limits += ColorLimit(
                field = ColorField.GREEN_GAIN,
                reason = platform(
                    "The night display's warmth moves red against blue and is the whole of " +
                        "the white-point control Android exposes. Green has no setting of " +
                        "its own.",
                ),
            )
        }
        for (field in correction.gammaFields) {
            if (correction.valueOf(field) == 0) continue
            limits += ColorLimit(
                field = field,
                reason = platform(
                    "Gamma is a compositor transform, set through a colour matrix that no " +
                        "app-reachable API accepts — not with WRITE_SECURE_SETTINGS and not " +
                        "from a shell.",
                ),
            )
        }
        if (correction.contrast != 0) {
            limits += ColorLimit(
                field = ColorField.CONTRAST,
                reason = platform(
                    "Contrast needs the same colour matrix as gamma. Android has no secure " +
                        "setting for it.",
                ),
            )
        }
        if (correction.hueDegrees != 0) {
            limits += ColorLimit(
                field = ColorField.HUE,
                reason = platform(
                    "A hue rotation is a colour matrix. The only rotation Android exposes " +
                        "is full inversion, which is on or off.",
                ),
            )
        }
    }

    /**
     * Whether one field has anywhere to go on this device, asked before the user touches it.
     *
     * [plan] deliberately says nothing about a field sitting at neutral — a correction that changes
     * nothing has no limits, which is the truthful answer for an *apply* and the useless one for a
     * control that has to decide whether to draw itself live or dimmed. This asks the other question by
     * projecting a probe: the same rules, one field moved, everything else neutral.
     *
     * Both ends of the range are probed, and a limit is only returned when *both* are refused. That
     * matters for the fields Android honours in one direction only — a brightness offset downward is
     * the reduce-bright-colours dimmer and upward is nothing, and a control disabled because the up
     * direction is unreachable would take the working half of the slider with it. When one direction
     * works the slider stays live, and dragging into the dead half produces a limit from the real
     * [plan] that the caller renders beside it.
     *
     * [GammaMode.PER_CHANNEL] for the probe, so the per-channel gamma fields answer for themselves
     * rather than being skipped by [ColorCorrection.gammaFields] — the combined slider is projected by
     * the same branch either way.
     */
    fun limitFor(field: ColorField, apiLevel: Int): ColorLimit? {
        val probe = ColorCorrection.NEUTRAL.copy(gammaMode = GammaMode.PER_CHANNEL)
        val extremes = listOf(field.range.last, field.range.first).filter { it != 0 }
        var refusal: ColorLimit? = null
        for (value in extremes) {
            val limit = plan(probe.with(field, value), apiLevel).limitFor(field)
            if (limit == null) return null
            refusal = refusal ?: limit
        }
        return refusal
    }

    /**
     * The platform's own encoding of the daltonizer modes, kept here rather than on
     * [ColorVisionFilter] so the model layer carries no platform integers.
     *
     * The values are AOSP's `AccessibilityManager.DALTONIZER_*`: −1 disabled,
     * 0 monochromacy, 11 protanomaly, 12 deuteranomaly, 13 tritanomaly. Public because
     * the projection tests assert them.
     */
    fun daltonizerMode(filter: ColorVisionFilter): Int? = when (filter) {
        ColorVisionFilter.NONE -> null
        ColorVisionFilter.MONOCHROMACY -> MODE_MONOCHROMACY
        ColorVisionFilter.PROTANOPIA -> MODE_PROTANOMALY
        ColorVisionFilter.DEUTERANOPIA -> MODE_DEUTERANOMALY
        ColorVisionFilter.TRITANOPIA -> MODE_TRITANOMALY
    }

    /**
     * Both daltonizer keys, always together and always in this order.
     *
     * The mode alone does nothing until the enable flag is set, and the flag alone
     * applies whatever mode was last left behind — which on a device the user has used
     * the accessibility screen on is not necessarily the one being asked for.
     */
    private fun daltonizerWrites(mode: Int, because: String): List<ColorWrite> = listOf(
        ColorWrite(
            setting = WritableSetting.DALTONIZER_MODE,
            value = mode.toString(),
            because = because,
        ),
        ColorWrite(
            setting = WritableSetting.DALTONIZER_ENABLED,
            value = "1",
            because = "the mode is inert until the daltonizer is switched on",
        ),
    )

    private fun platform(detail: String): Observed.Restricted = Observed.Restricted(
        reason = RestrictionReason.PLATFORM_RESTRICTED,
        unlockedBy = null,
        detail = detail,
    )

    private fun newerApi(detail: String): Observed.Restricted = Observed.Restricted(
        reason = RestrictionReason.NOT_SUPPORTED_ON_API_LEVEL,
        unlockedBy = null,
        detail = detail,
    )

    /** The night display's neutral point, and the warmest value this maps to. */
    const val NEUTRAL_KELVIN = 4_000
    const val WARMEST_KELVIN = 2_300

    /**
     * Below this the temperature change would be under 100K — invisible, and not worth
     * switching the night display on for.
     */
    const val MIN_WARMTH = 5

    /** Saturation at or below this is greyscale; within ±[DEAD_ZONE] of 0 is natural. */
    const val GREYSCALE_AT = -95
    const val DEAD_ZONE = 20
    const val SATURATED_AT = 60

    const val MODE_NATURAL = 0
    const val MODE_BOOSTED = 1
    const val MODE_SATURATED = 2

    const val MODE_MONOCHROMACY = 0
    const val MODE_PROTANOMALY = 11
    const val MODE_DEUTERANOMALY = 12
    const val MODE_TRITANOMALY = 13

    /** Reduce bright colours arrived in Android 12. */
    const val REDUCE_BRIGHT_COLORS_API = 31
}

/**
 * What one correction becomes on this device: the writes, and the honest remainder.
 *
 * Both halves matter to the UI. The writes drive the apply, and the limits are rendered
 * beside the sliders that produced them, so a user who drags contrast sees why nothing
 * happened instead of concluding the app is broken.
 */
data class ColorPlan(
    val writes: List<ColorWrite>,
    val limits: List<ColorLimit>,
) {
    /** The sinks this correction engages. What is not here gets restored, not zeroed. */
    val sinks: List<WritableSetting> get() = writes.map { it.setting }

    /** True when nothing the user asked for can be expressed on this device at all. */
    val engagesNothing: Boolean get() = writes.isEmpty()

    val isFullyHonoured: Boolean get() = limits.isEmpty()

    fun valueFor(setting: WritableSetting): String? =
        writes.firstOrNull { it.setting == setting }?.value

    fun limitFor(field: ColorField): ColorLimit? = limits.firstOrNull { it.field == field }

    /** The fields to mark as unavailable in the editor, in field order. */
    val unreachableFields: List<ColorField> get() = limits.map { it.field }.distinct()
}

/** One confirmed-by-read-back write, with the reason it is the right one. */
data class ColorWrite(
    val setting: WritableSetting,
    val value: String,
    /** Why this sink expresses what was asked. Shown in the applied-state detail. */
    val because: String,
)

/**
 * One field the user set that this device cannot honour.
 *
 * Carries a real [Observed.Restricted] rather than a string so that the colour feature
 * reports absence in the same shape as every other reading in the app, and so the UI's
 * existing restriction rendering applies unchanged.
 */
data class ColorLimit(
    val field: ColorField,
    val reason: Observed.Restricted,
) {
    val message: String
        get() = reason.detail.ifBlank { "Not available on this device." }
}
