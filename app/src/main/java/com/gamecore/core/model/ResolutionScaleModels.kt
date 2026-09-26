package com.gamecore.core.model

import kotlin.math.roundToInt

/**
 * A resolution downscale preset for Part B (spec §B2).
 *
 * This is the counterpart to [AspectPreset], and the difference is the whole point. [AspectPreset] *stretches*:
 * it keeps the short side and cuts the long one, deliberately handing the game a different aspect ratio. A
 * [ResolutionScale] does the opposite — it multiplies *both* pixel dimensions by the same factor, so the native
 * aspect ratio is preserved exactly and only the pixel count drops. Fewer pixels for the compositor to push,
 * same screen shape.
 *
 * [FULL] is the panel's own size (the controller turns it into `wm size reset`, never an override that happens
 * to equal the panel). [HIGH] and [MEDIUM] shrink to 80% and 60%. Every result is rounded to an EVEN pixel on
 * each axis, because odd dimensions are a known source of off-by-one scaling and encoder complaints — the same
 * rule [DisplaySize.stretchedTo] already follows.
 *
 * Setting a smaller logical resolution does NOT guarantee the game's internal render resolution follows (§B5):
 * that is up to the game. This only changes the surface size `wm size` reports.
 */
enum class ResolutionScale(val percent: Int, val label: String, val summary: String) {

    FULL(100, "100%", "The panel's own resolution. Nothing is scaled."),

    HIGH(80, "80%", "Four fifths of the pixels, same screen shape. A gentle drop."),

    MEDIUM(60, "60%", "Around a third fewer pixels each way. The lightest of these."),
    ;

    /**
     * What this preset is in pixels on a [native] panel. [FULL] returns [native] unchanged; the others scale
     * both axes by [percent] and round each to the nearest even pixel, preserving the native aspect ratio.
     */
    fun sizeFor(native: DisplaySize): DisplaySize {
        if (percent >= 100) return native
        return DisplaySize(scaleEven(native.widthPixels), scaleEven(native.heightPixels))
    }

    /** [dimension] scaled by [percent] and rounded to the nearest even pixel (never below 2). */
    private fun scaleEven(dimension: Int): Int {
        val scaled = (dimension.toDouble() * percent / 100.0).roundToInt()
        return (scaled - scaled % 2).coerceAtLeast(2)
    }

    companion object {

        /**
         * The presets worth offering on a [native] panel, in enum order (100% first). [FULL] is always offered;
         * [HIGH]/[MEDIUM] are dropped when the scaled result would leave the display unusable — reusing
         * [DisplaySize.rejectionFor] so the narrow-side floor (`MIN_SIDE`) is judged in exactly one place.
         */
        fun optionsFor(native: DisplaySize): List<ResolutionScaleChoice> =
            entries.mapNotNull { scale ->
                val size = scale.sizeFor(native)
                if (scale == FULL || size.rejectionFor(native) == null) ResolutionScaleChoice(scale, size) else null
            }

        /** Which preset [size] is on this [native] panel, or null when it is a custom size. */
        fun of(size: DisplaySize, native: DisplaySize): ResolutionScale? =
            entries.firstOrNull { it.sizeFor(native).matches(size) }
    }
}

/** One row: a scale preset, and what it is in pixels on this device. */
data class ResolutionScaleChoice(val scale: ResolutionScale, val size: DisplaySize) {

    /** "80%", "60%", … — the preset's own label. */
    val label: String get() = scale.label

    /** "864 × 1920" — the concrete size the preset resolves to here. */
    val detail: String get() = size.label
}
