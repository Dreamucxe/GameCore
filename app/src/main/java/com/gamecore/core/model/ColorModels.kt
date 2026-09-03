package com.gamecore.core.model

/**
 * What the user asked the screen's colour to be.
 *
 * Every field here is a value GameCore stores, shows, puts into a preset and can carry
 * on a game profile. None of them is, by itself, a claim that Android will honour it.
 *
 * The platform exposes a small set of narrow colour sinks to an app with ADB-level
 * authority — the night-display warmth, the display colour mode, the daltonizer,
 * reduce-bright-colours and colour inversion — and nothing whatsoever for a
 * per-channel gain, gamma, contrast or hue matrix. `SurfaceControl.setDisplayColor
 * Transform` and `ColorDisplayManager`'s matrix are hidden platform API that neither
 * WRITE_SECURE_SETTINGS nor a shell running as uid 2000 can reach.
 *
 * So what the user chose and what this device can do about it are kept in separate
 * files. This class is the choice; `ColorProjection` decides, per field and per
 * device, whether a sink exists for it and reports the specific reason where one does
 * not. §24's rule that GameCore never claims an unsupported API works is the whole
 * reason a gamma slider here does not become a gamma slider that quietly does nothing.
 *
 * Ranges are named constants for the reason [OverlayConfig] gives: a range retyped at
 * a call site is a control that clamps somewhere the model does not.
 */
data class ColorCorrection(
    /** Per-channel gain, as a percentage either side of the panel's own output. */
    val redGain: Int = 0,
    val greenGain: Int = 0,
    val blueGain: Int = 0,

    /** Whether gamma is one slider or three. Stored, so reopening shows the same view. */
    val gammaMode: GammaMode = GammaMode.COMBINED,
    val gamma: Int = 0,
    val redGamma: Int = 0,
    val greenGamma: Int = 0,
    val blueGamma: Int = 0,

    val saturation: Int = 0,
    val contrast: Int = 0,
    val hueDegrees: Int = 0,

    /**
     * Below zero dims the panel further than its own minimum; above zero has no sink
     * on any Android version and is reported as such rather than rounded away.
     */
    val brightnessOffset: Int = 0,

    val visionFilter: ColorVisionFilter = ColorVisionFilter.NONE,
    val invertColors: Boolean = false,
) {
    /** Every value inside its own declared range. Called on the way in and out of storage. */
    fun normalised(): ColorCorrection = copy(
        redGain = redGain.coerceIn(GAIN_RANGE),
        greenGain = greenGain.coerceIn(GAIN_RANGE),
        blueGain = blueGain.coerceIn(GAIN_RANGE),
        gamma = gamma.coerceIn(GAMMA_RANGE),
        redGamma = redGamma.coerceIn(GAMMA_RANGE),
        greenGamma = greenGamma.coerceIn(GAMMA_RANGE),
        blueGamma = blueGamma.coerceIn(GAMMA_RANGE),
        saturation = saturation.coerceIn(SATURATION_RANGE),
        contrast = contrast.coerceIn(CONTRAST_RANGE),
        hueDegrees = hueDegrees.coerceIn(HUE_RANGE),
        brightnessOffset = brightnessOffset.coerceIn(BRIGHTNESS_RANGE),
    )

    /** True when this asks for nothing, which is what "no correction" has to mean. */
    val changesNothing: Boolean
        get() = normalised().copy(gammaMode = GammaMode.COMBINED) == NEUTRAL

    /**
     * One field's value, by name.
     *
     * The numeric keypad, the per-field reset and the projection's own reachability
     * report all need to talk about "the field the user is holding" without a `when`
     * per call site. [ColorField] plus these two functions is that vocabulary.
     */
    fun valueOf(field: ColorField): Int = when (field) {
        ColorField.RED_GAIN -> redGain
        ColorField.GREEN_GAIN -> greenGain
        ColorField.BLUE_GAIN -> blueGain
        ColorField.GAMMA -> gamma
        ColorField.RED_GAMMA -> redGamma
        ColorField.GREEN_GAMMA -> greenGamma
        ColorField.BLUE_GAMMA -> blueGamma
        ColorField.SATURATION -> saturation
        ColorField.CONTRAST -> contrast
        ColorField.HUE -> hueDegrees
        ColorField.BRIGHTNESS_OFFSET -> brightnessOffset
    }

    fun with(field: ColorField, value: Int): ColorCorrection {
        val clamped = value.coerceIn(field.range)
        return when (field) {
            ColorField.RED_GAIN -> copy(redGain = clamped)
            ColorField.GREEN_GAIN -> copy(greenGain = clamped)
            ColorField.BLUE_GAIN -> copy(blueGain = clamped)
            ColorField.GAMMA -> copy(gamma = clamped)
            ColorField.RED_GAMMA -> copy(redGamma = clamped)
            ColorField.GREEN_GAMMA -> copy(greenGamma = clamped)
            ColorField.BLUE_GAMMA -> copy(blueGamma = clamped)
            ColorField.SATURATION -> copy(saturation = clamped)
            ColorField.CONTRAST -> copy(contrast = clamped)
            ColorField.HUE -> copy(hueDegrees = clamped)
            ColorField.BRIGHTNESS_OFFSET -> copy(brightnessOffset = clamped)
        }
    }

    /** "Reset this channel", granular, for one field only. */
    fun reset(field: ColorField): ColorCorrection = with(field, NEUTRAL.valueOf(field))

    /**
     * The gamma fields the user is currently looking at.
     *
     * Both the combined slider and the three per-channel ones are stored at all times,
     * so switching modes and back does not lose the other set of values. Only the
     * active set is drawn, projected or described.
     */
    val gammaFields: List<ColorField>
        get() = when (gammaMode) {
            GammaMode.COMBINED -> listOf(ColorField.GAMMA)
            GammaMode.PER_CHANNEL ->
                listOf(ColorField.RED_GAMMA, ColorField.GREEN_GAMMA, ColorField.BLUE_GAMMA)
        }

    /** Every numeric field the user is currently able to see, in the order the screen draws them. */
    val visibleFields: List<ColorField>
        get() = listOf(ColorField.RED_GAIN, ColorField.GREEN_GAIN, ColorField.BLUE_GAIN) +
            gammaFields +
            listOf(
                ColorField.SATURATION,
                ColorField.CONTRAST,
                ColorField.HUE,
                ColorField.BRIGHTNESS_OFFSET,
            )

    /** The visible fields that are set to something other than neutral. */
    val changedFields: List<ColorField>
        get() = visibleFields.filter { valueOf(it) != NEUTRAL.valueOf(it) }

    /**
     * The correction in one phrase, for a row or a chip that has a single line.
     *
     * Lists what the user changed rather than all fourteen values, and says "No change" rather
     * than nothing at all — a preset that asks for nothing is a legitimate thing to have saved
     * and a blank subtitle would read as a rendering bug. Truncation is the caller's business:
     * this is the full phrase, and only the widget drawing it knows how much room there is.
     */
    val summary: String
        get() {
            val parts = changedFields.map { "${it.label} ${it.format(valueOf(it))}" }.toMutableList()
            if (visionFilter != ColorVisionFilter.NONE) parts += visionFilter.label
            if (invertColors) parts += "Inverted"
            return if (parts.isEmpty()) "No change" else parts.joinToString(", ")
        }

    companion object {
        val GAIN_RANGE = -100..100
        val GAMMA_RANGE = -100..100
        val SATURATION_RANGE = -100..100
        val CONTRAST_RANGE = -100..100
        val HUE_RANGE = -180..180
        val BRIGHTNESS_RANGE = -100..100

        /** No correction at all. Also what every "reset" writes back. */
        val NEUTRAL = ColorCorrection()
    }

}

/**
 * The numeric colour fields, each with the range it accepts and the unit it is read in.
 *
 * An enum rather than eleven separate slider call sites, because three features need to
 * treat "a field" uniformly: the tap-to-type keypad, which has to know the range before
 * it can reject an entry; the per-field reset; and the projection's report, which names
 * the fields this device cannot reach. Adding a field here forces the two `when`s in
 * [ColorCorrection] and is picked up by the screen and the report for free.
 */
enum class ColorField(
    val label: String,
    val range: IntRange,
    val unit: String,
    val description: String,
) {
    RED_GAIN(
        "Red", ColorCorrection.GAIN_RANGE, "%",
        "How much red the panel emits, either side of its own output.",
    ),
    GREEN_GAIN(
        "Green", ColorCorrection.GAIN_RANGE, "%",
        "How much green the panel emits, either side of its own output.",
    ),
    BLUE_GAIN(
        "Blue", ColorCorrection.GAIN_RANGE, "%",
        "How much blue the panel emits, either side of its own output.",
    ),
    GAMMA(
        "Gamma", ColorCorrection.GAMMA_RANGE, "",
        "The curve between black and white. Negative lifts shadows, positive deepens them.",
    ),
    RED_GAMMA("Red gamma", ColorCorrection.GAMMA_RANGE, "", "The red channel's own curve."),
    GREEN_GAMMA("Green gamma", ColorCorrection.GAMMA_RANGE, "", "The green channel's own curve."),
    BLUE_GAMMA("Blue gamma", ColorCorrection.GAMMA_RANGE, "", "The blue channel's own curve."),
    SATURATION(
        "Saturation", ColorCorrection.SATURATION_RANGE, "%",
        "How far colours sit from grey. −100% is greyscale.",
    ),
    CONTRAST(
        "Contrast", ColorCorrection.CONTRAST_RANGE, "%",
        "The distance between the darkest and lightest output.",
    ),
    HUE(
        "Hue rotation", ColorCorrection.HUE_RANGE, "°",
        "Rotates every colour around the wheel.",
    ),
    BRIGHTNESS_OFFSET(
        "Brightness offset", ColorCorrection.BRIGHTNESS_RANGE, "%",
        "Shifts output brightness on top of the screen's own brightness level.",
    ),
    ;

    /** Signed, so a slider label reads "+30%" rather than "30%" on the positive side. */
    fun format(value: Int): String {
        val sign = if (value > 0) "+" else ""
        return "$sign$value$unit"
    }
}

/** One gamma slider or three. A view preference, stored with the values it describes. */
enum class GammaMode(val label: String) {
    COMBINED("Combined"),
    PER_CHANNEL("Per channel"),
}

/**
 * A colour-vision filter, named for the condition it is meant to help with.
 *
 * This is the one part of the whole feature that Android implements properly for an
 * app with ADB-level authority: the platform's own daltonizer runs in the compositor
 * and applies device-wide. The int the settings provider wants is not here — it lives
 * in `ColorProjection` with the rest of the platform encoding, because these names
 * describe what the user wants and that number describes how one Android version
 * happens to spell it.
 */
enum class ColorVisionFilter(val label: String, val description: String) {
    NONE("Off", "No colour-vision filter."),
    MONOCHROMACY("Monochromacy", "Removes colour entirely, device-wide."),
    PROTANOPIA("Protanopia", "Shifts reds so they separate from greens."),
    DEUTERANOPIA("Deuteranopia", "Shifts greens so they separate from reds."),
    TRITANOPIA("Tritanopia", "Shifts blues so they separate from yellows."),
}

/**
 * A named set of colour values, stored in the same shape as a crosshair preset.
 *
 * There is no built-in/custom distinction in the type, and that is deliberate. The
 * seven presets the app ships are seeded as ordinary rows on first run, exactly as the
 * crosshair defaults are, so a user can open "Night", move a slider and save it — or
 * rename it, or delete it. A built-in that could not be edited would make the preset
 * list a menu rather than a starting point, and the number of custom presets is limited
 * by nothing but the storage they sit in.
 */
data class ColorPreset(
    val id: Long = 0L,
    val name: String,
    val correction: ColorCorrection = ColorCorrection.NEUTRAL,
) {
    fun normalised(): ColorPreset = copy(
        name = name.trim().take(MAX_NAME_LENGTH).ifBlank { DEFAULT_NAME },
        correction = correction.normalised(),
    )

    companion object {
        /** Fits a chip in the overlay panel and a row in the picker. */
        const val MAX_NAME_LENGTH = 40
        const val DEFAULT_NAME = "Colour"

        /**
         * The presets that ship with the app, seeded once.
         *
         * Warm and Cool are deliberately symmetric even though only one of them is
         * reachable on stock Android — the night display warms and cannot cool. Cool is
         * still here because it is a legitimate thing to ask for, and the honest answer
         * to it is the projection's "no sink for this on this device", not its absence
         * from the list.
         */
        fun builtIns(): List<ColorPreset> = listOf(
            ColorPreset(
                name = "Vibrant",
                correction = ColorCorrection(saturation = 45, contrast = 10),
            ),
            ColorPreset(
                name = "Warm",
                correction = ColorCorrection(redGain = 30, blueGain = -30),
            ),
            ColorPreset(
                name = "Cool",
                correction = ColorCorrection(redGain = -25, blueGain = 25),
            ),
            ColorPreset(
                name = "Night",
                correction = ColorCorrection(
                    redGain = 55,
                    blueGain = -55,
                    brightnessOffset = -35,
                    saturation = -15,
                ),
            ),
            ColorPreset(
                name = "Protanopia",
                correction = ColorCorrection(visionFilter = ColorVisionFilter.PROTANOPIA),
            ),
            ColorPreset(
                name = "Deuteranopia",
                correction = ColorCorrection(visionFilter = ColorVisionFilter.DEUTERANOPIA),
            ),
            ColorPreset(
                name = "Tritanopia",
                correction = ColorCorrection(visionFilter = ColorVisionFilter.TRITANOPIA),
            ),
        )
    }
}
