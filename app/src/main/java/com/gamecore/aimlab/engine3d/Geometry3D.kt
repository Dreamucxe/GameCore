package com.gamecore.aimlab.engine3d

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point or direction in the training room's world space, in metres.
 *
 * The 3D engine is pure Kotlin with no `android.*` and no knowledge of the screen, exactly like the 2D
 * [com.gamecore.aimlab.engine.Vec2] it sits beside: the GL layer turns these world coordinates into
 * clip space with its own projection, and a session recorded on one device means the same on another
 * because nothing here is in pixels. World axes follow the OpenGL convention the renderer uses — +X
 * right, +Y up, −Z into the screen — so a camera at the origin looking down −Z sees the room ahead.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)

    val length: Float get() = sqrt(x * x + y * y + z * z)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    /** Unit vector in the same direction, or the zero vector if this has no length (never NaN). */
    fun normalised(): Vec3 {
        val len = length
        return if (len <= EPSILON) ZERO else Vec3(x / len, y / len, z / len)
    }

    fun distanceTo(o: Vec3): Float = (this - o).length

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        const val EPSILON = 1e-6f
    }
}

/**
 * The angle in **degrees** between two directions, always in `[0, 180]`.
 *
 * Angle, not world distance, is the engine's currency for aim error (§3): a target two metres away
 * subtends a smaller angle than the same target one metre away, and it is the angle the player's wrist
 * actually turns through, so tracking error and flick precision measured this way do not depend on how
 * far into the room a target happens to sit. The dot product of the normalised vectors is clamped to
 * `[-1, 1]` before `acos`, because floating-point error can push it a hair past ±1 and `acos` returns
 * `NaN` there.
 */
fun angleBetweenDegrees(a: Vec3, b: Vec3): Float {
    val na = a.normalised()
    val nb = b.normalised()
    if (na == Vec3.ZERO || nb == Vec3.ZERO) return 0f
    val cosine = na.dot(nb).coerceIn(-1f, 1f)
    return Math.toDegrees(acos(cosine).toDouble()).toFloat()
}

/**
 * A ray from [origin] along a unit [direction]. The aim ray is the camera's forward vector; a shot is
 * this ray, optionally perturbed into a spread cone before the hit test.
 */
data class Ray(val origin: Vec3, val direction: Vec3) {
    companion object {
        /** Builds a ray, normalising the direction so callers cannot pass an unnormalised one by mistake. */
        fun of(origin: Vec3, direction: Vec3): Ray = Ray(origin, direction.normalised())
    }
}

/**
 * The result of testing a ray against a sphere: whether it hit, and if so how far along the ray.
 *
 * [distance] is the parametric distance to the *first* intersection in front of the origin, in metres,
 * so the nearest of several overlapping targets can be chosen. It is meaningless when [hit] is false.
 */
data class RayHit(val hit: Boolean, val distance: Float) {
    companion object {
        val MISS = RayHit(hit = false, distance = 0f)
    }
}

/**
 * Ray–sphere intersection, the hit test that replaces the 2D disc `contains` (§3).
 *
 * Solves `|origin + t·dir − centre|² = r²` for the smallest `t > 0`. The direction is assumed unit
 * length (so the quadratic's leading coefficient is 1). Four cases the tests pin:
 *  - a ray pointing at the sphere hits, with `distance` the near root;
 *  - a ray pointing away misses even though the line through it would intersect (both roots negative);
 *  - a tangent ray grazes at one point and counts as a hit;
 *  - a sphere the origin is *inside* still hits (the far root is positive), so a target you walked into
 *    is not silently un-shootable.
 *
 * A sphere directly behind the camera (centre behind, no positive root) is a miss — you cannot shoot
 * what is not in front of the crosshair.
 */
fun intersectRaySphere(ray: Ray, centre: Vec3, radius: Float): RayHit {
    val oc = ray.origin - centre
    val b = oc.dot(ray.direction)
    val c = oc.dot(oc) - radius * radius
    val discriminant = b * b - c
    if (discriminant < 0f) return RayHit.MISS
    val sqrtD = sqrt(discriminant)
    // Near root first; if it is behind the origin, try the far root (origin inside the sphere).
    val near = -b - sqrtD
    if (near >= 0f) return RayHit(hit = true, distance = near)
    val far = -b + sqrtD
    if (far >= 0f) return RayHit(hit = true, distance = far)
    return RayHit.MISS
}

/**
 * Ray–capsule intersection: a capsule is a segment [a]→[b] of radius [radius] (a cylinder with a
 * hemispherical cap at each end). This is what a humanoid dummy's torso and limbs are hit-tested as
 * (spec §4) — a capsule is a far better fit for an arm or a leg than a box, and its rounded ends mean a
 * grazing shot behaves the way a sphere's does.
 *
 * The implementation tests the infinite cylinder around the segment axis, keeps a hit only where it falls
 * between the two end planes, and otherwise falls back to the two end-cap spheres — the standard
 * decomposition. The nearest positive `t` across all three parts wins, so an angled shot that clips a cap
 * is caught. A ray pointing away, or a capsule wholly behind the origin, misses. The direction is assumed
 * unit length. A degenerate capsule (a ≈ b) reduces to a single sphere.
 */
fun intersectRayCapsule(ray: Ray, a: Vec3, b: Vec3, radius: Float): RayHit {
    val axis = b - a
    val axisLenSq = axis.dot(axis)
    if (axisLenSq <= Vec3.EPSILON) return intersectRaySphere(ray, a, radius)

    var best = Float.MAX_VALUE
    var hit = false

    // Infinite-cylinder test around the axis, then clamp the hit to the segment's extent.
    val oa = ray.origin - a
    val dDotAxis = ray.direction.dot(axis)
    val oaDotAxis = oa.dot(axis)
    // Quadratic A t² + 2B t + C = 0 for the distance to the cylinder surface.
    val aCoef = axisLenSq - dDotAxis * dDotAxis
    val bCoef = axisLenSq * oa.dot(ray.direction) - oaDotAxis * dDotAxis
    val cCoef = axisLenSq * (oa.dot(oa) - radius * radius) - oaDotAxis * oaDotAxis
    if (kotlin.math.abs(aCoef) > Vec3.EPSILON) {
        val disc = bCoef * bCoef - aCoef * cCoef
        if (disc >= 0f) {
            val sqrtD = sqrt(disc)
            for (t in floatArrayOf((-bCoef - sqrtD) / aCoef, (-bCoef + sqrtD) / aCoef)) {
                if (t < 0f || t >= best) continue
                // Where along the axis does this hit fall? Keep it only within the segment.
                val m = oaDotAxis + t * dDotAxis
                if (m in 0f..axisLenSq) {
                    best = t
                    hit = true
                }
            }
        }
    }

    // The two hemispherical end caps.
    for (centre in arrayOf(a, b)) {
        val capHit = intersectRaySphere(ray, centre, radius)
        if (capHit.hit && capHit.distance < best) {
            best = capHit.distance
            hit = true
        }
    }

    return if (hit) RayHit(hit = true, distance = best) else RayHit.MISS
}

/**
 * A unit forward direction from a [yawDegrees]/[pitchDegrees] pair, the camera's look conventions.
 *
 * Yaw turns around the world +Y axis and pitch tilts up/down. At yaw 0, pitch 0 the forward is −Z (into
 * the room), matching the renderer's view matrix. Pitch is expected pre-clamped by the camera; this is
 * the pure trig so the same conversion is used by the loop, the renderer and the tests.
 */
fun forwardFromAngles(yawDegrees: Float, pitchDegrees: Float): Vec3 {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    val pitch = Math.toRadians(pitchDegrees.toDouble())
    val cosPitch = cos(pitch)
    // Derived so that (yaw,pitch)=(0,0) → (0,0,-1), yaw+ turns toward +X, pitch+ lifts toward +Y.
    val x = (cosPitch * sin(yaw)).toFloat()
    val y = sin(pitch).toFloat()
    val z = (-cosPitch * cos(yaw)).toFloat()
    return Vec3(x, y, z).normalised()
}
