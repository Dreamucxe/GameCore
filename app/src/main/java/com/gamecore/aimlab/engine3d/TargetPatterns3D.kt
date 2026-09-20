package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.Rng
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 3D counterparts of the 2D tracking patterns: a target's world position as a pure function of time.
 *
 * Reuses the enum names of [com.gamecore.aimlab.engine.TrackingPattern] semantically but works in world
 * metres on a plane in front of the player, at a fixed depth, so a tracked sphere moves left/right and
 * up/down across the far wall the way a real trainer's does. Every path is bounded inside the room's
 * inset walls (§3: spawn/stay inside room bounds), evaluated at [positionAt], and — for RANDOM —
 * reproducible from the injected [Rng], so a seed replays a run exactly and the tests are deterministic.
 *
 * The plane is centred at eye height and set back [depth] metres down −Z; [halfSpan] is how far the
 * target may travel from centre on each axis, kept within the room by the caller. Pure, android-free.
 */
class TargetPath3D(
    private val pattern: Pattern3D,
    private val depth: Float,
    private val halfSpan: Float,
    private val eyeHeight: Float,
    private val speed: Float,
    rng: Rng,
) {
    // RANDOM walks a fixed waypoint loop chosen up front, so a seed reproduces the same walk.
    private val waypoints: List<Vec3> = if (pattern == Pattern3D.RANDOM) {
        List(RANDOM_WAYPOINTS) {
            Vec3(
                (rng.nextFloat() * 2f - 1f) * halfSpan,
                eyeHeight + (rng.nextFloat() * 2f - 1f) * halfSpan,
                -depth,
            )
        }
    } else {
        emptyList()
    }

    /** World position at [seconds] since the run began. Total and bounded for any non-negative time. */
    fun positionAt(seconds: Float): Vec3 {
        val t = seconds.coerceAtLeast(0f)
        return when (pattern) {
            Pattern3D.HORIZONTAL -> Vec3(triangle(t * speed), eyeHeight, -depth)
            Pattern3D.VERTICAL -> Vec3(0f, eyeHeight + triangle(t * speed), -depth)
            Pattern3D.CIRCULAR -> {
                val r = halfSpan * 0.7f
                val angular = if (r > 0f) speed / r else 0f
                val a = t * angular
                Vec3(r * cos(a), eyeHeight + r * sin(a), -depth)
            }
            Pattern3D.ZIGZAG -> Vec3(
                triangle(t * speed),
                eyeHeight + triangle(t * speed * ZIGZAG_Y_RATIO),
                -depth,
            )
            Pattern3D.RANDOM -> randomAt(t)
        }
    }

    private fun randomAt(seconds: Float): Vec3 {
        if (waypoints.size < 2) return Vec3(0f, eyeHeight, -depth)
        val progress = seconds * speed
        val total = waypoints.size
        val segment = progress.toInt() % total
        val frac = progress - progress.toInt()
        val from = waypoints[segment]
        val to = waypoints[(segment + 1) % total]
        return Vec3(
            from.x + (to.x - from.x) * frac,
            from.y + (to.y - from.y) * frac,
            -depth,
        )
    }

    /** A triangle wave in `[-halfSpan, halfSpan]`: folds an unbounded coordinate into a bounded bounce. */
    private fun triangle(coordinate: Float): Float {
        if (halfSpan <= 0f) return 0f
        val period = 4f * halfSpan
        var m = coordinate % period
        if (m < 0f) m += period
        // 0..span rising, span..3span falling, 3span..4span rising back — a symmetric triangle.
        return when {
            m <= halfSpan -> m
            m <= 3f * halfSpan -> 2f * halfSpan - m
            else -> m - 4f * halfSpan
        }
    }

    companion object {
        const val RANDOM_WAYPOINTS = 8
        const val ZIGZAG_Y_RATIO = 2.7f
    }
}

/** The 3D tracking patterns; a subset of the 2D set that reads naturally on a wall-plane. */
enum class Pattern3D {
    HORIZONTAL,
    VERTICAL,
    CIRCULAR,
    ZIGZAG,
    RANDOM,
    ;

    companion object {
        fun fromName(name: String?): Pattern3D = entries.firstOrNull { it.name == name } ?: CIRCULAR
    }
}

/**
 * Chooses a world position for a flick/reaction target: a random point on a spherical shell in front of
 * the player, at a difficulty-driven distance, within the room's angular reach (§3: spheres at varied
 * angles/distances).
 *
 * Draws a yaw within [maxYawDegrees] and a pitch within [maxPitchDegrees] of straight ahead, and a
 * distance in `[minDistance, maxDistance]`, from the injected [Rng] so a seed reproduces the spawn. The
 * point is then clamped inside the room box so a wide-angle draw at a long distance can never end up
 * behind a wall.
 */
fun spawnFlickTarget3D(
    rng: Rng,
    eye: Vec3,
    room: Room,
    minDistance: Float,
    maxDistance: Float,
    maxYawDegrees: Float,
    maxPitchDegrees: Float,
): Vec3 {
    val yaw = (rng.nextFloat() * 2f - 1f) * maxYawDegrees
    val pitch = (rng.nextFloat() * 2f - 1f) * maxPitchDegrees
    val distance = minDistance + rng.nextFloat() * (maxDistance - minDistance).coerceAtLeast(0f)
    val dir = forwardFromAngles(yaw, pitch)
    val raw = eye + dir * distance
    // Keep the sphere inside the walls, insetting by a margin so it never clips a surface.
    val m = SPAWN_MARGIN
    return Vec3(
        raw.x.coerceIn(-room.halfWidth + m, room.halfWidth - m),
        raw.y.coerceIn(m, room.height - m),
        raw.z.coerceIn(-room.halfDepth + m, room.halfDepth - m),
    )
}

/** How far a spawned sphere is kept from any wall, so its surface never pokes through. */
const val SPAWN_MARGIN = 0.6f
