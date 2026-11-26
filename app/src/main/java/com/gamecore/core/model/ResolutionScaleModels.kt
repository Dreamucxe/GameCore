package com.gamecore.core.model

import kotlin.math.roundToInt

/**
 * One of Android's per-game Game Modes.
 *
 * The numbers are the platform's own `GAME_MODE_PERFORMANCE` / `GAME_MODE_BATTERY` constants. The
 * strings are what `cmd game mode` accepts on Android 13, and the numbers are what `cmd game set
 * --mode` accepts on Android 14 — which is why both are carried rather than one being derived from
 * the other.
 */
enum class GameModeMode(val number: Int, val argument: String, val label: String) {
    PERFORMANCE(2, "performance", "Performance"),
    BATTERY(3, "battery", "Battery"),
}

/**
 * Android's per-game Game Mode service: the version gates, the mode mapping and the argument grammar.
 *
 * This is the clean transport for a per-game change. Unlike a `wm size` override it is scoped to one
 * package, so the rest of the phone keeps its resolution; unlike a global refresh-rate pin it can cap
 * one game's frames without touching the launcher. Android applies the values when the game next
 * launches, and the shell surface GameCore uses has no command that reads a package's current
 * overrides back, so a successful write is reported as requested rather than confirmed.
 *
 * **The command changed between releases, and the difference is not cosmetic.** Android 13's `cmd
 * game set` requires `--mode performance|battery` first and only then accepts `--downscale` and
 * `--fps`; Android 14's `set` accepts the interventions on their own and treats them as a custom
 * mode. Passing Android 14's argv to Android 13 prints `Invalid option '--downscale'` and changes
 * nothing, which is why [modeNeutralOn] gates the route rather than [supportedOn] alone. Android 13
 * also accepts only 30–90% in 5% steps for `--downscale`, where Android 14 parses any float.
 *
 * Kept Android-free (`SDK_INT` is supplied by the caller) so every one of those rules is a JVM test.
 */
object GameMode {

    /** `Build.VERSION_CODES.TIRAMISU`. Android 13 is the first release with Game Mode. */
    const val FIRST_SDK = 33

    /** `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`. First release where `set` takes no `--mode`. */
    const val MODE_NEUTRAL_SDK = 34

    /**
     * The frame rates `--fps` accepts, from the platform's own help text.
     *
     * Deliberately not every rate a panel can report: this is the platform's enum, and a value
     * outside it is rejected by the command rather than rounded to a neighbour the user did not ask
     * for. 30 is the one that matters most here — it is the honest fallback on a 60 Hz-only panel
     * where a 30 Hz *display mode* does not exist.
     */
    val FPS_CHOICES: List<Int> = listOf(30, 40, 45, 60, 90, 120)

    /** True when `cmd game` exists at all on [sdkInt]. */
    fun supportedOn(sdkInt: Int): Boolean = sdkInt >= FIRST_SDK

    /** True when `cmd game set` accepts interventions without a `--mode`. */
    fun modeNeutralOn(sdkInt: Int): Boolean = sdkInt >= MODE_NEUTRAL_SDK

    /** The Game Mode a profile's own mode maps onto, or null when it asks for neither. */
    fun modeFor(mode: PerformanceMode): GameModeMode? = when (mode) {
        PerformanceMode.PERFORMANCE -> GameModeMode.PERFORMANCE
        PerformanceMode.BATTERY_SAVER -> GameModeMode.BATTERY
        PerformanceMode.BALANCED, PerformanceMode.CUSTOM -> null
    }

    /**
     * The `--fps` argument for [fps], or null when the platform does not accept that rate.
     *
     * Rounded to the nearest whole frame and then required to be one of [FPS_CHOICES]. The tolerance
     * is small so a panel value of 59.94 does not silently become a request for 60 the user did not
     * make; a profile imported with such a value will simply not use this transport.
     */
    fun fpsCapArgument(fps: Float?): Int? {
        if (fps == null) return null
        val rounded = fps.roundToInt()
        if (kotlin.math.abs(fps - rounded) > 0.05f) return null
        return rounded.takeIf { it in FPS_CHOICES }
    }

    /**
     * The frame rate a profile's target should be delivered as a *cap*, or null when it should use
     * the display's own modes instead.
     *
     * A cap is the right transport only when the panel has no mode at that rate and the rate is one
     * the platform accepts — the case this exists for is 30 FPS on a 60 Hz-only screen. A rate the
     * panel already lists belongs to the refresh-rate pin: that changes what the display does, not
     * how many frames the game may draw. A rate above the panel's highest is not a cap either; the
     * display cannot show it, and capping at a number it can never reach would be a no-op dressed
     * up as a change.
     *
     * Returns null when [panelRates] is empty rather than guessing: without the panel's modes there
     * is no way to tell a cap from a display mode, and the refresh-rate path reports that honestly.
     */
    fun frameCapRate(rateHz: Float?, panelRates: List<Float>): Int? {
        if (rateHz == null || panelRates.isEmpty()) return null
        val fps = fpsCapArgument(rateHz) ?: return null
        val highest = panelRates.maxOrNull() ?: return null
        if (rateHz > highest + 0.5f) return null
        val onPanel = panelRates.any { kotlin.math.abs(it - rateHz) <= 0.5f }
        return if (onPanel) null else fps
    }

    /**
     * The `--downscale` argument for [percent], or null when the platform will not accept it.
     *
     * 100% is the platform's `disable`, not `1.0`: the help text documents `disable`, and asking for
     * `disable` is the same instruction without relying on a float the platform may round. The 30–90%
     * 5%-step window is Android 13's list; it is applied on every release because the app's own
     * presets all land inside it, and refusing a value the platform documents is safer than guessing
     * that a newer build kept the same parser.
     */
    fun downscaleArgument(percent: Int): String? = when {
        percent == 100 -> "disable"
        percent in 30..90 && percent % 5 == 0 -> (percent / 100.0).toString()
        else -> null
    }
}

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
