package com.gamecore.aimlab.engine3d

import kotlin.math.cos
import kotlin.math.sin

/**
 * A humanoid dummy target: a [DummyPose] plus the geometry that turns it into hittable zones (spec §4).
 *
 * The dummy is built from primitives around the feet [DummyPose.position]: a sphere head, a capsule neck,
 * capsule chest and stomach, capsule arms and legs. Each zone's world geometry is derived from the pose
 * (scale, crouch, vertical jump offset) so a crouching or jumping dummy is hit-tested exactly where it is
 * drawn. The proportions are fractions of a nominal standing height, so [DummyPose.scale] shrinks the
 * whole body — the difficulty knob (spec §3).
 *
 * [intersect] tests the aim ray against every zone and returns the nearest hit, breaking an exact-distance
 * tie by [HitZone.priority] so a shot threading head-and-neck counts as the head (spec §4). Pure Kotlin;
 * the renderer draws the same primitives from the same pose so what is drawn is what is hit.
 */
class HumanoidTarget(
    val id: Long,
    val pose: DummyPose,
    val spawnedAtNanos: Long,
    /** Multiplies every zone's radius; difficulty can shrink hitboxes independently of visible scale. */
    val hitboxScale: Float = 1f,
) {

    /** The nominal standing height in metres before [DummyPose.scale]. */
    private val height: Float get() = STANDING_HEIGHT * pose.scale

    /** Feet position lifted by any jump offset. */
    private val feet: Vec3
        get() = Vec3(pose.position.x, pose.position.y + pose.verticalOffset, pose.position.z)

    /**
     * Tests the ray against every zone and returns the nearest hit (zone + distance), or null on a miss.
     *
     * The crouch compresses the torso and drops the head; the walk cycle's [DummyPose.legSwing] and
     * [DummyPose.armSway] splay the limbs. Every zone is a sphere (head) or capsule (the rest); the nearest
     * positive intersection wins, and [HitZone.priority] only decides a dead tie.
     */
    fun intersect(ray: Ray): ZoneHit? {
        var best: ZoneHit? = null
        for (part in parts()) {
            val h = when (part) {
                is Part.Sphere -> intersectRaySphere(ray, part.centre, part.radius * hitboxScale)
                is Part.Capsule -> intersectRayCapsule(ray, part.a, part.b, part.radius * hitboxScale)
            }
            if (!h.hit) continue
            val current = best
            if (current == null ||
                h.distance < current.distance - DISTANCE_EPS ||
                (kotlin.math.abs(h.distance - current.distance) <= DISTANCE_EPS &&
                    part.zone.priority > current.zone.priority)
            ) {
                best = ZoneHit(part.zone, h.distance)
            }
        }
        return best
    }

    /** The head centre in world space, for the renderer's head-hit marker and for tests. */
    fun headCentre(): Vec3 {
        val crouchDrop = height * CROUCH_DROP * pose.crouch
        return Vec3(feet.x, feet.y + height - height * HEAD_RADIUS - crouchDrop, feet.z)
    }

    /** The zone primitives in world space for the current pose. Order is head→legs; ties resolve by priority. */
    private fun parts(): List<Part> {
        val s = height
        val crouchDrop = s * CROUCH_DROP * pose.crouch
        val yaw = Math.toRadians(pose.facingYawDegrees.toDouble())
        // A small lateral basis so arms sit to the sides of whichever way the dummy faces.
        val rx = cos(yaw).toFloat()
        val rz = -sin(yaw).toFloat()
        fun at(up: Float, side: Float = 0f, fwd: Float = 0f): Vec3 = Vec3(
            feet.x + rx * side,
            feet.y + up - crouchDrop,
            feet.z + rz * side,
        )

        val headR = s * HEAD_RADIUS
        val headY = s - headR
        val head = Part.Sphere(HitZone.HEAD, at(headY), headR)

        val neck = Part.Capsule(HitZone.NECK, at(s * 0.86f), at(s * 0.82f), s * 0.05f)
        val chest = Part.Capsule(HitZone.CHEST, at(s * 0.80f), at(s * 0.62f), s * 0.13f)
        val stomach = Part.Capsule(HitZone.STOMACH, at(s * 0.62f), at(s * 0.48f), s * 0.11f)

        val armSwing = s * 0.04f * sin(pose.armSway.toDouble()).toFloat()
        val armTop = s * 0.78f
        val armBottom = s * 0.42f + armSwing
        val leftArm = Part.Capsule(HitZone.ARMS, at(armTop, side = s * 0.17f), at(armBottom, side = s * 0.19f), s * 0.05f)
        val rightArm = Part.Capsule(HitZone.ARMS, at(armTop, side = -s * 0.17f), at(armBottom, side = -s * 0.19f), s * 0.05f)

        val legSwing = s * 0.05f * sin(pose.legSwing.toDouble()).toFloat()
        val hip = s * 0.46f
        val leftLeg = Part.Capsule(HitZone.LEGS, at(hip, side = s * 0.08f), at(0f, side = s * 0.08f + legSwing), s * 0.06f)
        val rightLeg = Part.Capsule(HitZone.LEGS, at(hip, side = -s * 0.08f), at(0f, side = -s * 0.08f - legSwing), s * 0.06f)

        return listOf(head, neck, chest, stomach, leftArm, rightArm, leftLeg, rightLeg)
    }

    private sealed interface Part {
        val zone: HitZone
        data class Sphere(override val zone: HitZone, val centre: Vec3, val radius: Float) : Part
        data class Capsule(override val zone: HitZone, val a: Vec3, val b: Vec3, val radius: Float) : Part
    }

    companion object {
        const val STANDING_HEIGHT = 1.8f
        const val HEAD_RADIUS = 0.075f      // fraction of standing height
        const val CROUCH_DROP = 0.28f       // how far the head drops at full crouch, as a height fraction
        private const val DISTANCE_EPS = 1e-4f
    }
}

/** A hit on a humanoid: which zone, and how far along the ray. */
data class ZoneHit(val zone: HitZone, val distance: Float)
