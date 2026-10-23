package com.gamecore.aimlab.engine

import com.gamecore.aimlab.engine3d.TargetKind

/**
 * Target colour palettes for colour-vision accessibility.
 *
 * Each palette defines the colour for every renderer [TargetKind] (STANDARD / SMALL / FAR — the three
 * cosmetic target families the 3D renderer distinguishes). The palette is chosen in Settings and
 * propagates through [com.gamecore.aimlab.render.RenderState.targetPalette] to the GL renderer.
 * Pure Kotlin, no Android dependencies — the renderer converts to float arrays at draw time.
 */
enum class TargetPalette(val label: String) {

    /** The shipped colours. */
    STANDARD("Standard") {
        override val targetColor: Map<TargetKind, Int> = mapOf(
            TargetKind.STANDARD to 0xFF1ACCE5.toInt(),
            TargetKind.SMALL to 0xFF33E6B3.toInt(),
            TargetKind.FAR to 0xFF4DB3FF.toInt(),
        )
    },

    /** High-contrast palette for low-vision users: pure white on any background. */
    HIGH_CONTRAST("High contrast") {
        override val targetColor: Map<TargetKind, Int> = mapOf(
            TargetKind.STANDARD to 0xFFFFFFFF.toInt(),
            TargetKind.SMALL to 0xFFFFFFFF.toInt(),
            TargetKind.FAR to 0xFFFFFFFF.toInt(),
        )
    },

    /** Deuteranopia (green-blind) safe palette: blue / amber / magenta, no red-green pair. */
    DEUTERANOPIA("Deuteranopia") {
        override val targetColor: Map<TargetKind, Int> = mapOf(
            TargetKind.STANDARD to 0xFF0096FF.toInt(),
            TargetKind.SMALL to 0xFFFFB300.toInt(),
            TargetKind.FAR to 0xFFE040FB.toInt(),
        )
    },

    /** Protanopia (red-blind) safe palette: blue / yellow / violet. */
    PROTANOPIA("Protanopia") {
        override val targetColor: Map<TargetKind, Int> = mapOf(
            TargetKind.STANDARD to 0xFF0091EA.toInt(),
            TargetKind.SMALL to 0xFFFFEB3B.toInt(),
            TargetKind.FAR to 0xFF7B1FA2.toInt(),
        )
    },

    /** Tritanopia (blue-blind) safe palette: cyan / yellow / magenta. */
    TRITANOPIA("Tritanopia") {
        override val targetColor: Map<TargetKind, Int> = mapOf(
            TargetKind.STANDARD to 0xFF00E5FF.toInt(),
            TargetKind.SMALL to 0xFFFFD600.toInt(),
            TargetKind.FAR to 0xFFE040FB.toInt(),
        )
    },
    ;

    abstract val targetColor: Map<TargetKind, Int>

    /** The ARGB colour for a target kind, or the standard fallback for that kind. */
    fun colourFor(kind: TargetKind): Int =
        targetColor[kind] ?: STANDARD.targetColor[kind] ?: 0xFFFFFFFF.toInt()

    /**
     * The colour as normalised RGB floats, ready for the GL uniform.
     *
     * Split out so the conversion from stored ARGB to GL's 0..1 triplet lives in exactly one place.
     */
    fun rgbFor(kind: TargetKind): FloatArray {
        val argb = colourFor(kind)
        return floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
    }
}