package com.gamecore.aimlab.engine3d

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One gravity-derived tilt pose, in radians.
 *
 * [steer] is the in-plane rotation of "down" across the screen — bank the phone like a steering wheel and
 * this changes. [tip] is how far the screen's top edge leans away from the player — tip the phone away and
 * this changes. Both are measured from the screen the player is looking at, so neither needs the display
 * rotation: the pair is already in the frame the player experiences.
 */
data class TiltPose(val steer: Float, val tip: Float)

/**
 * Turns accelerometer samples into the two aim angles a phone without a gyroscope can honestly provide.
 *
 * ## Why these two angles
 *
 * An accelerometer measures the direction of gravity in the device's frame. Rotation *about* the gravity
 * axis — turning the phone left or right while holding it upright — changes nothing about that direction,
 * so no accelerometer can sense it. That is physics, not a missing calibration, and it is why this is a
 * tilt fallback rather than a gyroscope substitute.
 *
 * What gravity can see is the other two rotations, and they are the ones this object reports:
 *
 *  - [TiltPose.steer] — rotation in the plane of the screen (about the screen normal). It is what a
 *    racing-game tilt control uses: bank right, the value rises.
 *  - [TiltPose.tip] — leaning the top edge away from the player (about the screen's horizontal axis).
 *    Tip the top away, the value rises.
 *
 * Both are angles, not rates, so the reader differences successive poses to get the per-sample rotation
 * delta the aim pipeline already consumes. Differencing also means sensor noise does not accumulate: the
 * deltas telescope to the total angle moved.
 *
 * ## What it refuses
 *
 * An accelerometer reports *acceleration*, not gravity. Two cases make the reading untrustworthy, and both
 * return null rather than a guess:
 *
 *  - the magnitude is far from [GRAVITY], which means the phone is being swung;
 *  - the screen is close to horizontal, where "where is down on the screen" has no stable answer.
 *
 * A null is a real answer — "there is nothing honest to report this sample" — and the reader re-arms its
 * reference instead of emitting a jump. Nothing here invents a rest angle or fills a gap.
 *
 * Pure and Android-free, so the whole convention is unit-testable on the JVM.
 */
object TiltAim {

    /** Standard gravity in m/s², the magnitude a phone at rest reports. */
    const val GRAVITY = 9.80665f

    /**
     * How far the measured magnitude may sit from [GRAVITY] before the sample is treated as movement
     * rather than gravity. 3 m/s² is roughly a third of a g — beyond a deliberate swing, inside ordinary
     * hand tremor.
     */
    const val SHAKE_TOLERANCE = 3f

    /**
     * The smallest projection of "down" onto the screen plane that still defines a direction.
     *
     * At 0.20 the screen is about 11.5° from horizontal. Below that the in-plane angle spins arbitrarily
     * and a small wobble would become a large aim jump.
     */
    const val MIN_SCREEN_PROJECTION = 0.20f

    /**
     * Reads one accelerometer sample, or null when it cannot be trusted as gravity.
     *
     * [ax], [ay], [az] are the platform's own values in m/s², including gravity. The screen is the device's
     * X-Y plane and its normal is Z.
     */
    fun read(ax: Float, ay: Float, az: Float): TiltPose? {
        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        if (!magnitude.isFinite() || magnitude <= 0f) return null
        if (abs(magnitude - GRAVITY) > SHAKE_TOLERANCE) return null

        // The accelerometer reports the reaction to gravity, so "down" is the negated, normalised vector.
        val downX = -ax / magnitude
        val downY = -ay / magnitude
        val downZ = -az / magnitude

        // How much of "down" lies in the screen plane. Near zero means the screen faces the sky or the
        // floor, and the in-plane direction is undefined.
        val screenProjection = sqrt(downX * downX + downY * downY)
        if (screenProjection < MIN_SCREEN_PROJECTION) return null

        // Steer is zero with the phone upright in portrait, and rises when the right edge drops: banking
        // the phone the way the player wants to look. Tip is zero with the screen vertical and rises as
        // the top edge leans away from the player.
        val steer = atan2(-downX, -downY)
        val tip = atan2(-downZ, screenProjection)
        return TiltPose(steer, tip)
    }

    /**
     * The rotation between two poses, as `(dx, dy)` radians: `dx` looks right and `dy` looks up.
     *
     * Both differences are wrapped into `(-π, π]`, so a pose crossing the ±180° seam produces the small
     * motion that actually happened rather than a full-turn jump.
     */
    fun delta(from: TiltPose, to: TiltPose): Pair<Float, Float> =
        wrap(to.steer - from.steer) to wrap(to.tip - from.tip)

    /** Folds an angle difference into `(-π, π]`. The house helper for a wrapped angle is in `Camera3D`. */
    fun wrap(radians: Float): Float = atan2(sin(radians), cos(radians))
}
