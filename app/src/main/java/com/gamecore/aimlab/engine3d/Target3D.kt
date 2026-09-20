package com.gamecore.aimlab.engine3d

/**
 * A target in the training room: a sphere at a world [position] with a world-unit [radius].
 *
 * The 3D counterpart of the 2D [com.gamecore.aimlab.engine.Target]. Radius is in metres, not a fraction
 * of the screen, because the room is a fixed physical space and a target's difficulty is how small an
 * *angle* it subtends from where the player stands — which the distance and the radius together decide,
 * not the pixel size. [velocity] is metres per second for moving targets (tracking/movement patterns);
 * it is zero for the static spheres flick and reaction spawn.
 *
 * [kind] only varies the look (the renderer tints and shades by it, §1) and never the hit test, so a
 * cosmetic change can never affect scoring. [spawnedAtNanos] is engine-monotonic, the clock acquisition
 * time is measured against, exactly as in 2D.
 */
data class Target3D(
    val id: Long,
    val position: Vec3,
    val radius: Float,
    val spawnedAtNanos: Long,
    val velocity: Vec3 = Vec3.ZERO,
    val kind: TargetKind = TargetKind.STANDARD,
) {
    /** Whether the ray strikes this sphere, and how far along it — the engine's only hit test (§3). */
    fun intersect(ray: Ray): RayHit = intersectRaySphere(ray, position, radius)

    /** This target advanced by [seconds] along its velocity. Static targets return themselves unchanged. */
    fun advanced(seconds: Float): Target3D =
        if (velocity == Vec3.ZERO) this else copy(position = position + velocity * seconds)
}

/**
 * The cosmetic families the renderer distinguishes (§1: "small variation by target type"). Stored as the
 * enum name if it ever reaches Room; parsed back defensively. It carries no gameplay weight.
 */
enum class TargetKind {
    STANDARD,
    SMALL,
    FAR,
    ;

    companion object {
        fun fromName(name: String?): TargetKind = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/**
 * The room the training happens inside, as a centred axis-aligned box in metres.
 *
 * The player spawns at the origin on the floor and cannot leave; targets spawn within the walls inset by
 * a margin so a sphere never clips a surface. One place defines the extents so the engine's spawn bounds
 * and the renderer's geometry agree — a target spawned outside the box the renderer drew would float in a
 * wall.
 */
data class Room(
    val halfWidth: Float = DEFAULT_HALF_WIDTH,
    val halfDepth: Float = DEFAULT_HALF_DEPTH,
    val height: Float = DEFAULT_HEIGHT,
) {
    /** The larger horizontal half-extent, for clamping player movement to a square footprint. */
    val halfExtent: Float get() = maxOf(halfWidth, halfDepth)

    companion object {
        const val DEFAULT_HALF_WIDTH = 6f
        const val DEFAULT_HALF_DEPTH = 8f
        const val DEFAULT_HEIGHT = 4f
    }
}
