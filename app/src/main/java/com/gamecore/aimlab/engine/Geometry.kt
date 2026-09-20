package com.gamecore.aimlab.engine

import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A point in the training arena, in normalised [0,1] coordinates.
 *
 * Everything spatial in the engine is normalised, never pixels: the engine has no android.* and no idea
 * how big the screen is. The Android layer scales these to the surface's measured size at draw time, the
 * same way the HUD editor turns fractions into pixels. That keeps the engine testable and makes a session
 * recorded on one device meaningful on another.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Float): Vec2 = Vec2(x * scale, y * scale)

    /** Euclidean distance to [other], in arena units. */
    fun distanceTo(other: Vec2): Float = hypot(x - other.x, y - other.y)

    val length: Float get() = sqrt(x * x + y * y)

    /**
     * Clamped into the unit square inset by [margin] on every side, so a point never leaves the arena.
     *
     * [margin] is itself clamped to half the arena first. Without that, a margin past 0.5 inverts the
     * range — `coerceIn(0.6f, 0.4f)` throws `IllegalArgumentException`, from a geometry helper, mid-run.
     * A margin that wide has no sane answer to give (the inset region is empty), so it collapses to the
     * centre point, which is the limit the inset approaches as it grows and is always inside the arena.
     * The caller passing it is a target radius bigger than the screen, which is a difficulty
     * configuration to reject elsewhere, not a crash to take here.
     */
    fun clampToArena(margin: Float = 0f): Vec2 {
        val inset = margin.clampFinite(0f, 0.5f)
        return Vec2(
            x.clampFinite(inset, 1f - inset, fallback = 0.5f),
            y.clampFinite(inset, 1f - inset, fallback = 0.5f),
        )
    }

    companion object {
        val CENTER = Vec2(0.5f, 0.5f)
    }
}

/**
 * A circular target in the arena.
 *
 * [radius] is a fraction of the arena's smaller dimension, so a target keeps its shape on any aspect
 * ratio. [spawnedAtNanos] is engine-monotonic and is what target-acquisition time is measured against.
 */
data class Target(
    val id: Long,
    val center: Vec2,
    val radius: Float,
    val spawnedAtNanos: Long,
    /** For moving targets: current velocity in arena units per second. Zero for static targets. */
    val velocity: Vec2 = Vec2(0f, 0f),
) {
    /** True when [point] falls within this target's disc. The hit test the engine uses for every shot. */
    fun contains(point: Vec2): Boolean = center.distanceTo(point) <= radius
}
