package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.FloatRingBuffer
import com.gamecore.aimlab.engine.Rng
import kotlin.math.sqrt

/**
 * Perturbs an aim ray into a random point of a spread cone, the 3D weapon-spread model (§3).
 *
 * A weapon with non-zero spread does not fire exactly down the crosshair: the shot goes somewhere inside
 * a cone of half-angle [coneHalfAngleDegrees] around the aim direction. The point in the cone is drawn
 * from the injected [Rng] so a seed still replays a run exactly, and the distribution is uniform over the
 * cap (not merely uniform in angle, which would cluster shots at the centre). Zero spread returns the
 * ray unchanged, so a precise weapon is exactly precise.
 *
 * The maths: pick a uniform azimuth `φ ∈ [0, 2π)` and a polar angle whose cosine is uniform in
 * `[cos θmax, 1]`, build that direction in a frame whose +Z is the aim direction, and rotate it back.
 */
fun applySpreadCone(ray: Ray, coneHalfAngleDegrees: Float, rng: Rng): Ray {
    if (coneHalfAngleDegrees <= 0f) return ray
    val thetaMax = Math.toRadians(coneHalfAngleDegrees.toDouble())
    val cosThetaMax = kotlin.math.cos(thetaMax)
    val cosTheta = cosThetaMax + rng.nextFloat() * (1.0 - cosThetaMax)
    val sinTheta = sqrt((1.0 - cosTheta * cosTheta).coerceAtLeast(0.0))
    val phi = rng.nextFloat() * 2.0 * Math.PI

    // Local direction in the cone, with +Z as the axis.
    val lx = (sinTheta * kotlin.math.cos(phi)).toFloat()
    val ly = (sinTheta * kotlin.math.sin(phi)).toFloat()
    val lz = cosTheta.toFloat()

    // An orthonormal basis around the aim direction. Pick a helper not parallel to the axis.
    val axis = ray.direction.normalised()
    val helper = if (kotlin.math.abs(axis.y) < 0.99f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
    val right = helper.cross(axis).normalised()
    val up = axis.cross(right).normalised()

    val dir = (right * lx) + (up * ly) + (axis * lz)
    return Ray(ray.origin, dir.normalised())
}

/**
 * Accumulates a tracking/gyro run's angle error over time, the 3D analogue of the 2D
 * `TrackingAccumulator` — but in **degrees of aim error**, not arena units (§3), so the figure means the
 * same on any device.
 *
 * Each frame the caller supplies the angle between the crosshair ray and the direction to the target,
 * weighted by the frame duration, plus whether the crosshair was within the target this frame. From that
 * it yields the time-on-target fraction and the time-weighted average error the scoring reads. Pure and
 * allocation-free: the samples live in a fixed [FloatRingBuffer], never a growing list.
 */
class TrackingAccumulator3D(private val onTargetAngleDegrees: Float) {
    private var totalSeconds = 0f
    private var onTargetSeconds = 0f
    private val errorSamples = FloatRingBuffer(2048)

    /**
     * Records one frame.
     *
     * @param errorDegrees angle between the aim ray and the direction to the target centre.
     * @param frameSeconds this frame's duration.
     */
    fun add(errorDegrees: Float, frameSeconds: Float) {
        if (frameSeconds <= 0f) return
        totalSeconds += frameSeconds
        if (errorDegrees <= onTargetAngleDegrees) onTargetSeconds += frameSeconds
        errorSamples.add(errorDegrees)
    }

    /** Fraction of the run the crosshair was on the target, 0..1. */
    fun timeOnTargetFraction(): Float =
        if (totalSeconds <= 0f) 0f else (onTargetSeconds / totalSeconds).coerceIn(0f, 1f)

    /** Mean aim error in degrees over the run, 0 for an empty run. */
    fun averageErrorDegrees(): Float = errorSamples.mean()

    /** Standard deviation of the per-frame error — the correction jitter gyro scoring reads. */
    fun errorStdDevDegrees(): Float = errorSamples.standardDeviation()
}

/**
 * Computes the angle in degrees between where the camera is aiming and where a target actually is — the
 * error a tracking frame records and a flick shot's precision is judged by.
 */
fun aimErrorDegrees(ray: Ray, targetCentre: Vec3): Float =
    angleBetweenDegrees(ray.direction, targetCentre - ray.origin)
