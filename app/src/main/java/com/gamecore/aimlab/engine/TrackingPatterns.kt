package com.gamecore.aimlab.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The movement patterns a tracking target can follow.
 *
 * Every pattern is a pure function of elapsed time, evaluated by [TrackingPath.positionAt], and every one
 * is bounded: the returned point is always inside the arena inset by the target radius, so a target can
 * never leave the screen however long the session runs (§B2.4). Name is the stable stored key.
 */
enum class TrackingPattern(val label: String) {
    HORIZONTAL("Horizontal"),
    VERTICAL("Vertical"),
    CIRCULAR("Circular"),
    RANDOM("Random"),
    ZIGZAG("Zig-zag"),
    ACCELERATING("Accelerating"),
    DECELERATING("Decelerating"),
    ;

    companion object {
        fun fromName(name: String?): TrackingPattern = entries.firstOrNull { it.name == name } ?: HORIZONTAL
    }
}

/**
 * Evaluates a tracking target's position over time for a chosen [pattern].
 *
 * Constructed once per tracking run with the pattern, the target [radius] (so bounds account for the
 * target's size), a [speed] in arena units per second, and the [rng] used only by the RANDOM pattern to
 * pick its waypoints up front — so a given seed reproduces the same random walk and the tests are
 * deterministic.
 *
 * The travel area is the unit square inset by `radius` on every side; a centre coordinate is mapped into
 * `[radius, 1-radius]` so the drawn disc stays fully on screen. `positionAt` is total and defined for any
 * non-negative time.
 */
class TrackingPath(
    private val pattern: TrackingPattern,
    private val radius: Float,
    private val speed: Float,
    rng: Rng,
) {
    private val lo = radius.coerceIn(0f, 0.49f)
    private val hi = 1f - lo
    private val span = (hi - lo).coerceAtLeast(0f)

    // RANDOM walks between a fixed list of waypoints chosen at construction, so it is reproducible.
    private val waypoints: List<Vec2> = if (pattern == TrackingPattern.RANDOM) {
        List(RANDOM_WAYPOINTS) {
            Vec2(lo + rng.nextFloat() * span, lo + rng.nextFloat() * span)
        }
    } else {
        emptyList()
    }

    /**
     * Position at [seconds] since the run began.
     *
     * - HORIZONTAL/VERTICAL: a triangle wave across the axis at constant [speed] (bounces at the edges).
     * - CIRCULAR: uniform circular motion; angular rate scales with speed and radius so linear speed holds.
     * - ZIGZAG: horizontal triangle wave with a faster vertical triangle wave layered on.
     * - ACCELERATING/DECELERATING: horizontal motion whose speed rises/falls with time (bounded), which
     *   the tests verify by comparing successive step sizes.
     * - RANDOM: constant-speed travel along the pre-chosen waypoint list, looping.
     *
     * All axes are mapped through [axis], which folds an unbounded oscillator coordinate back into
     * `[lo, hi]` with a triangle wave, guaranteeing the result is on screen for any time.
     */
    fun positionAt(seconds: Float): Vec2 {
        val t = seconds.coerceAtLeast(0f)
        return when (pattern) {
            TrackingPattern.HORIZONTAL -> Vec2(triangle(t * speed), 0.5f).mapY()
            TrackingPattern.VERTICAL -> Vec2(0.5f, triangle(t * speed)).mapX()
            TrackingPattern.CIRCULAR -> {
                val r = span / 2f
                val angularSpeed = if (r > 0f) speed / r else 0f
                val angle = t * angularSpeed
                Vec2(0.5f + r * cos(angle), 0.5f + r * sin(angle))
            }
            TrackingPattern.ZIGZAG -> Vec2(triangle(t * speed), triangle(t * speed * ZIGZAG_Y_RATIO))
            TrackingPattern.ACCELERATING -> {
                // Distance travelled grows with t^2, so speed rises linearly; folded to stay in bounds.
                val distance = 0.5f * speed * t * t
                Vec2(triangle(distance), 0.5f).mapY()
            }
            TrackingPattern.DECELERATING -> {
                // Distance approaches an asymptote, so step size shrinks over time.
                val distance = speed * (1f - kotlin.math.exp(-t))
                Vec2(triangle(distance), 0.5f).mapY()
            }
            TrackingPattern.RANDOM -> randomAt(t)
        }
    }

    /** Travels the waypoint loop at constant [speed], one unit of `distance` per waypoint segment. */
    private fun randomAt(seconds: Float): Vec2 {
        if (waypoints.size < 2) return Vec2.CENTER
        // Segment length is normalised to 1 for simplicity; speed sets how many segments per second.
        val progress = seconds * speed
        val total = waypoints.size
        val segment = progress.toInt() % total
        val frac = progress - progress.toInt()
        val from = waypoints[segment]
        val to = waypoints[(segment + 1) % total]
        return Vec2(from.x + (to.x - from.x) * frac, from.y + (to.y - from.y) * frac)
    }

    /** A triangle wave in `[lo, hi]`: maps an unbounded coordinate to a bounded bounce. */
    private fun triangle(coordinate: Float): Float {
        if (span <= 0f) return lo
        val period = 2f * span
        var m = coordinate % period
        if (m < 0f) m += period
        val folded = if (m <= span) m else period - m
        return lo + folded
    }

    // The circular/zigzag branches already produce bounded axes; these helpers bound a fixed 0.5 axis in
    // case span < 1 so even the "still" axis sits inside the inset area.
    private fun Vec2.mapX(): Vec2 = Vec2(lo + span * 0.5f, y)
    private fun Vec2.mapY(): Vec2 = Vec2(x, lo + span * 0.5f)

    companion object {
        const val RANDOM_WAYPOINTS = 8
        const val ZIGZAG_Y_RATIO = 2.7f
    }
}

/**
 * Accumulates tracking error over a run: how far the crosshair sat from the target, and for how long it
 * was on it.
 *
 * Fed one sample per frame with the target centre, the crosshair position and the frame's duration. Error
 * is the time-weighted mean distance (arena units); time-on-target is the fraction of elapsed time the
 * crosshair was within the target radius. Both are total and zero-safe.
 */
class TrackingAccumulator(private val targetRadius: Float) {
    private var weightedErrorSum = 0.0
    private var timeOnTargetSeconds = 0.0
    private var totalSeconds = 0.0
    private var samples = 0L
    private var errorPeak = 0f

    fun add(target: Vec2, crosshair: Vec2, frameSeconds: Float) {
        if (frameSeconds <= 0f) return
        val error = target.distanceTo(crosshair)
        weightedErrorSum += error * frameSeconds
        if (error <= targetRadius) timeOnTargetSeconds += frameSeconds
        totalSeconds += frameSeconds
        if (error > errorPeak) errorPeak = error
        samples++
    }

    /** Time-weighted mean tracking error in arena units, or 0 for an empty run. */
    fun averageError(): Float = if (totalSeconds > 0.0) (weightedErrorSum / totalSeconds).toFloat() else 0f

    /** Fraction [0,1] of elapsed time the crosshair was on the target. */
    fun timeOnTargetFraction(): Float =
        if (totalSeconds > 0.0) (timeOnTargetSeconds / totalSeconds).toFloat().coerceIn(0f, 1f) else 0f

    fun peakError(): Float = errorPeak

    val sampleCount: Long get() = samples
}
