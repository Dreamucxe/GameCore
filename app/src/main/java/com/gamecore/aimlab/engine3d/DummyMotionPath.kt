package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.Rng
import kotlin.math.abs
import kotlin.math.sin

/**
 * A humanoid dummy's pose over time for a chosen [DummyMotion] (spec §3): where it stands, whether it is
 * crouched or mid-jump, and the walk-cycle scalars the renderer animates from. Pure Kotlin, deterministic
 * from the injected [Rng] so a seed replays a run, and allocation-free per call — [poseAt] returns a fresh
 * value object but touches no collection.
 *
 * Difficulty supplies the concrete speeds and rates; this only shapes them into motion. Every returned
 * scalar is finite by construction (the tests assert it), so a NaN can never reach a matrix.
 *
 * @param spawn the dummy's home position (feet), which it moves relative to.
 * @param speed metres/second of lateral or advancing movement.
 * @param directionChangeSeconds how often a strafe reverses / a peek toggles.
 * @param scale overall size multiplier from difficulty.
 */
class DummyMotionPath(
    private val motion: DummyMotion,
    private val spawn: Vec3,
    private val facingYawDegrees: Float,
    private val speed: Float,
    private val directionChangeSeconds: Float,
    private val strafeHalfWidth: Float,
    private val peekHalfWidth: Float,
    private val scale: Float,
    rng: Rng,
) {
    // A per-dummy phase so several dummies do not move in lockstep. Deterministic from the RNG.
    private val phase: Float = rng.nextFloat() * 1000f
    // Whether this dummy crouches/jumps at all, and its cadence, chosen up front for reproducibility.
    private val crouches: Boolean = rng.nextFloat() < 0.5f
    private val jumps: Boolean = rng.nextFloat() < 0.35f

    /** The pose at [seconds] since the run began. Total and bounded for any non-negative time. */
    fun poseAt(seconds: Float): DummyPose {
        val t = (seconds + phase).coerceAtLeast(0f)
        val period = directionChangeSeconds.coerceAtLeast(0.2f)

        var x = spawn.x
        var z = spawn.z
        var legSwing = 0f
        var armSway = 0f

        when (motion) {
            DummyMotion.STILL -> Unit
            DummyMotion.STRAFE -> {
                // Triangle wave across ±strafeHalfWidth with counter-strafe pauses at the ends.
                x = spawn.x + triangle(t / period) * strafeHalfWidth
                legSwing = t * WALK_CADENCE
                armSway = -legSwing
            }
            DummyMotion.PEEK -> {
                // Steps out to peekHalfWidth and back on a square-ish schedule; still while "in cover".
                val out = if ((t / period).toInt() % 2 == 0) smooth(t / period) else 1f - smooth(t / period)
                x = spawn.x + out * peekHalfWidth
                legSwing = out * WALK_CADENCE * t * 0.1f
                armSway = -legSwing
            }
            DummyMotion.ADVANCE -> {
                z = spawn.z + fold(t * speed, ADVANCE_RANGE) // toward the player (+Z), folded to stay in range
                legSwing = t * WALK_CADENCE
                armSway = -legSwing
            }
            DummyMotion.RETREAT -> {
                z = spawn.z - fold(t * speed, ADVANCE_RANGE)
                legSwing = t * WALK_CADENCE
                armSway = -legSwing
            }
        }

        val crouch = if (crouches) (0.5f + 0.5f * sin((t * CROUCH_CADENCE).toDouble()).toFloat())
            .coerceIn(0f, 1f) else 0f
        val jumpOffset = if (jumps) {
            val j = sin((t * JUMP_CADENCE).toDouble()).toFloat()
            if (j > 0f) j * JUMP_HEIGHT * scale else 0f
        } else 0f

        return DummyPose(
            position = Vec3(x, spawn.y, z),
            facingYawDegrees = facingYawDegrees,
            crouch = crouch,
            legSwing = legSwing,
            armSway = armSway,
            verticalOffset = jumpOffset,
            scale = scale,
        )
    }

    /** Triangle wave in [-1, 1] with period 2 in the input. */
    private fun triangle(u: Float): Float {
        val m = ((u % 2f) + 2f) % 2f
        return if (m <= 1f) (m * 2f - 1f) else (3f - m * 2f)
    }

    /** A smooth 0→1 ease over the fractional part of [u], for a peek step. */
    private fun smooth(u: Float): Float {
        val f = u - u.toInt()
        return f * f * (3f - 2f * f)
    }

    /** Folds a distance into [-range, range] with a triangle bounce, so advance/retreat never runs off. */
    private fun fold(distance: Float, range: Float): Float {
        if (range <= 0f) return 0f
        val period = 4f * range
        var m = distance % period
        if (m < 0f) m += period
        return when {
            m <= range -> m
            m <= 3f * range -> 2f * range - m
            else -> m - 4f * range
        }.let { abs(it).coerceAtMost(range) }
    }

    companion object {
        const val WALK_CADENCE = 6f       // rad/s of leg swing while walking
        const val CROUCH_CADENCE = 1.2f   // rad/s of the crouch toggle
        const val JUMP_CADENCE = 0.8f     // rad/s of the jump cycle
        const val JUMP_HEIGHT = 0.4f      // metres at full jump, before scale
        const val ADVANCE_RANGE = 4f      // metres a dummy advances/retreats before folding back
    }
}
