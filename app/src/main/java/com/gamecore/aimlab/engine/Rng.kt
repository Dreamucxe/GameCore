package com.gamecore.aimlab.engine

import kotlin.random.Random

/**
 * A seedable source of randomness for the training engine.
 *
 * Every spawn position, recoil kick and pattern jitter draws from an injected [Rng] rather than
 * `Math.random()` or a bare `Random()`, for one reason: a recoil pattern generated from a given seed must
 * be reproducible, so a test can assert "seed 42 produces exactly this sequence" and so a session can, if
 * we ever want it, be replayed. It also makes the whole engine deterministic under test.
 *
 * The interface is deliberately small — the four draws the engine actually needs — so a fake is trivial
 * and the production implementation is a thin wrapper over [kotlin.random.Random].
 */
interface Rng {
    /** A uniform float in [0, 1). */
    fun nextFloat(): Float

    /**
     * A uniform float in `[min, max)`.
     *
     * The half-open end is enforced rather than assumed. `min + nextFloat() * (max - min)` is three
     * float operations, and at the top of the unit interval the rounding in them lands on `max` exactly:
     * `nextFloat()` can return `0.99999994f`, and for a wide enough span the product rounds up to the
     * full width. Callers read the documented bound and rely on it — a spawn margin computed as
     * `nextFloat(margin, 1f - margin)` returning `1f - margin` puts a target's edge precisely on the
     * arena boundary — so the last representable value below [max] is substituted for [max] itself.
     * An empty or inverted range has no value to draw and yields [min].
     */
    fun nextFloat(min: Float, max: Float): Float {
        if (!(max > min)) return min
        val value = min + nextFloat() * (max - min)
        return if (value >= max) Math.nextDown(max) else value
    }

    /** A uniform int in [0, bound). */
    fun nextInt(bound: Int): Int

    /** A standard-normal (mean 0, sd 1) sample, for jitter that should cluster near zero. */
    fun nextGaussian(): Float
}

/**
 * The production RNG: a seedable [kotlin.random.Random] behind the [Rng] interface.
 *
 * Seeded from the clock at session start in production so each session differs, and from a fixed seed in
 * tests so each run is identical. Gaussian samples use the Box–Muller transform because
 * [kotlin.random.Random] has no native normal draw; the second value of each pair is cached so two calls
 * cost one pair of logs rather than two.
 */
class SeededRng(seed: Long) : Rng {
    private val random = Random(seed)
    private var spareGaussian: Float? = null

    override fun nextFloat(): Float = random.nextFloat()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)

    override fun nextGaussian(): Float {
        spareGaussian?.let {
            spareGaussian = null
            return it
        }
        // Box–Muller: two uniforms in (0,1] → two independent standard-normal samples.
        var u1: Double
        do {
            u1 = random.nextDouble()
        } while (u1 <= 1e-12)
        val u2 = random.nextDouble()
        val magnitude = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
        val z0 = magnitude * kotlin.math.cos(2.0 * Math.PI * u2)
        val z1 = magnitude * kotlin.math.sin(2.0 * Math.PI * u2)
        spareGaussian = z1.toFloat()
        return z0.toFloat()
    }
}
