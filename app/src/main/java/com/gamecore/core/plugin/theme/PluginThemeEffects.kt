package com.gamecore.core.plugin.theme

/**
 * The schema-3 motion contract: what a theme may ask the host's renderer to distort.
 *
 * A theme used to be able to recolour and re-shape, and nothing more. That is a static medium —
 * every frame it produces is the frame before it. This block is the one way a theme can ask for
 * *motion*, and it is deliberately a request rather than a program: the theme names how hard it
 * wants to be shaken, and the host owns every frame of what that looks like. A `.gcplugin` is
 * data, and it stays data — nothing here lets a package reach the canvas, the window manager, or
 * the frame clock.
 *
 * The values are bounded rather than open. Each one is clamped by [PluginThemeCodec] on the way
 * in and again by [clamped], for the same reason the corner radius is: a value slightly outside
 * the range is best honoured at the edge, and the edge is chosen so that no legal combination can
 * either hide the UI or pin a core.
 *
 * The host's own accessibility setting outranks all of it. A user whose `animations` preference
 * is Off gets no bursts at all, whatever a theme asks for — see `GlitchLayer`, which treats this
 * block as an intention and the preference as a veto.
 *
 * Pure Kotlin, no Android, so the bounds are unit-tested on the JVM.
 */
data class PluginGlitch(
    /**
     * How far every other number leans toward its maximum, 0..1.
     *
     * Not a master switch — [PluginThemeCodec] rejects a `glitch` block without it, so a theme
     * that carries the block always glitches at least a little. Intensity 0 is still a legal
     * value and collapses every derived quantity to its floor, which the renderer treats as "off
     * in all but name".
     */
    val intensity: Float = DEFAULT_INTENSITY,

    /** How many horizontal bands the frame is torn into during a burst. */
    val slices: Int = DEFAULT_SLICES,

    /** The furthest, in dp, any single band may be slid sideways. */
    val sliceOffsetDp: Int = DEFAULT_SLICE_OFFSET_DP,

    /** The horizontal gap, in dp, between a band and its two ghost copies. */
    val ghostOffsetDp: Int = DEFAULT_GHOST_OFFSET_DP,

    /** Draw a fracture line at band boundaries, in the theme's accent. */
    val fractureLines: Boolean = true,

    /** Draw translucent block noise over the panel during a burst. */
    val noise: Boolean = true,

    /** One-frame, one-or-two-pixel displacements between bursts: the "unstable supply" feel. */
    val microJitter: Boolean = true,

    /**
     * Let the device's own load decide how often it misbehaves.
     *
     * On, a hot or thrashing device gets bursts about twice as often, and a thermally throttled
     * one gets the frame-drop illusion. Off, the effect is a fixed cosmetic rhythm and reads no
     * sensors at all — which is also the setting that costs nothing to run.
     */
    val hardwareReactive: Boolean = true,

    /** The shortest and longest idle between bursts, in milliseconds. */
    val burstMinMs: Int = DEFAULT_BURST_MIN_MS,
    val burstMaxMs: Int = DEFAULT_BURST_MAX_MS,
) {
    /**
     * This block with every value back inside the format's bounds.
     *
     * The codec already clamps on the way in, so this is the second half of the belt-and-braces
     * the corner radius gets: a value that reached the renderer by some other route still cannot
     * ask for a twenty-band tear at four frames a second. [burstMinMs] and [burstMaxMs] are
     * ordered here as well as clamped, because a theme that swapped them is asking for a range
     * the other way round, and every legal interpretation of that is "the two values, ordered".
     */
    fun clamped(): PluginGlitch {
        val low = burstMinMs.coerceIn(MIN_BURST_MS, MAX_BURST_MS)
        val high = burstMaxMs.coerceIn(MIN_BURST_MS, MAX_BURST_MS)
        return copy(
            intensity = intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY),
            slices = slices.coerceIn(MIN_SLICES, MAX_SLICES),
            sliceOffsetDp = sliceOffsetDp.coerceIn(MIN_SLICE_OFFSET_DP, MAX_SLICE_OFFSET_DP),
            ghostOffsetDp = ghostOffsetDp.coerceIn(MIN_GHOST_OFFSET_DP, MAX_GHOST_OFFSET_DP),
            burstMinMs = minOf(low, high),
            burstMaxMs = maxOf(low, high),
        )
    }

    /**
     * Whether this block asks for anything visible at all.
     *
     * A theme whose intensity has collapsed to zero is a theme that wanted the *palette* of a
     * glitch and none of its motion, which is a legitimate design and one the renderer should
     * not pay for: at zero there is no tear to draw and no layer to record.
     */
    val isVisible: Boolean get() = intensity > 0f

    companion object {
        const val MIN_INTENSITY = 0f
        const val MAX_INTENSITY = 1f

        /**
         * Two bands is the fewest that reads as a tear rather than a shift; six is where the
         * frame stops looking corrupted and starts looking like a broken layout, and where a
         * single burst starts costing real draws per band.
         */
        const val MIN_SLICES = 2
        const val MAX_SLICES = 6

        /** Past ~48dp a band leaves its own panel entirely and lands on whatever is behind it. */
        const val MIN_SLICE_OFFSET_DP = 0
        const val MAX_SLICE_OFFSET_DP = 48

        /** A ghost past ~12dp stops reading as an edge and starts reading as a second copy. */
        const val MIN_GHOST_OFFSET_DP = 0
        const val MAX_GHOST_OFFSET_DP = 12

        /** The spec's own cadence: a burst every 150–800 ms is the "system struggling" rhythm. */
        const val MIN_BURST_MS = 120
        const val MAX_BURST_MS = 8000
        const val DEFAULT_BURST_MIN_MS = 150
        const val DEFAULT_BURST_MAX_MS = 800

        const val DEFAULT_INTENSITY = 0.6f
        const val DEFAULT_SLICES = 3
        const val DEFAULT_SLICE_OFFSET_DP = 18
        const val DEFAULT_GHOST_OFFSET_DP = 4

        /** Everything at its default, for the SDK and for tests that only care about one field. */
        val DEFAULT = PluginGlitch()
    }
}
