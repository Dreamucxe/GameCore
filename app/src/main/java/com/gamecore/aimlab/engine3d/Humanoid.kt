package com.gamecore.aimlab.engine3d

/**
 * The shared contract for humanoid training-dummy targets (spec §9): the hit zones, the target-type
 * choice, the dummy's pose, and the per-zone stats a run accumulates. Pure Kotlin, no `android.*` — the
 * renderer builds meshes from a [DummyPose] and the loop hit-tests against [HumanoidTarget].
 *
 * A dummy is a neutral training mannequin: a sphere head, a box/capsule torso split into chest and
 * stomach, and capsule limbs. There is no face, no gore, nothing violent — the zones are colour-blocked
 * regions to aim at, and "down" is a particle pop, never a ragdoll (spec §2).
 */

/**
 * The body zones a shot can land in, from most to least valuable by default.
 *
 * Stored by `name` if it ever reaches Room. [HEAD] and [NECK] overlap near the top of the torso, so the
 * hit test resolves them by *nearest intersection along the ray*, and [priority] only breaks an exact
 * distance tie — a higher priority wins, so a shot threading head-and-neck counts as the head.
 */
enum class HitZone(val label: String, val priority: Int, val defaultDamageMultiplier: Float) {
    HEAD("Head", priority = 6, defaultDamageMultiplier = 2.5f),
    NECK("Neck", priority = 5, defaultDamageMultiplier = 1.8f),
    CHEST("Chest", priority = 4, defaultDamageMultiplier = 1.2f),
    STOMACH("Stomach", priority = 3, defaultDamageMultiplier = 1.0f),
    ARMS("Arms", priority = 2, defaultDamageMultiplier = 0.8f),
    LEGS("Legs", priority = 1, defaultDamageMultiplier = 0.75f),
    ;

    companion object {
        fun fromName(name: String?): HitZone? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a session spawns as targets (spec §1). Stored by `name` in the session config; old sessions with
 * no value default to [SPHERES].
 */
enum class TargetType(val label: String) {
    SPHERES("Spheres"),
    HUMANOID("Humanoid"),
    MIXED("Mixed"),
    ;

    companion object {
        fun fromName(name: String?): TargetType = entries.firstOrNull { it.name == name } ?: SPHERES
    }
}

/**
 * The movement pattern a humanoid dummy follows (spec §3). Difficulty maps to the concrete speeds and
 * rates in [Difficulty3DParameters]; this only names the shape.
 */
enum class DummyMotion {
    STILL,
    STRAFE,        // left-right with direction changes and counter-strafe stops
    PEEK,          // step out of cover and back
    ADVANCE,       // walk toward the player
    RETREAT,       // walk away
    ;

    companion object {
        fun fromName(name: String?): DummyMotion = entries.firstOrNull { it.name == name } ?: STILL
    }
}

/**
 * A dummy's pose at one instant: where it stands and the few procedural-animation scalars the renderer
 * turns into limb transforms (spec §3 — "a few matrices, no skeletal system"). Every field is finite by
 * construction (the tests assert it), so a NaN can never reach a matrix.
 *
 * @param position world position of the dummy's feet.
 * @param facingYawDegrees which way it faces; the player is looked toward.
 * @param crouch 0 standing … 1 fully crouched, scales torso height and eye height.
 * @param legSwing radians of leg swing for a walk cycle; 0 when still.
 * @param armSway radians of arm sway, a smaller counter-phase to [legSwing].
 * @param verticalOffset metres the whole body is lifted (a jump); 0 on the ground.
 * @param scale overall size multiplier from difficulty (smaller is harder).
 */
data class DummyPose(
    val position: Vec3,
    val facingYawDegrees: Float = 0f,
    val crouch: Float = 0f,
    val legSwing: Float = 0f,
    val armSway: Float = 0f,
    val verticalOffset: Float = 0f,
    val scale: Float = 1f,
)

/**
 * The aggregate a humanoid run folds its shots into (spec §4/§7): per-zone hit counts, head accuracy and
 * total damage. Everything is measured — a shot is added exactly once, in the zone the hit test returned,
 * and damage is the zone's multiplier. Nothing here is simulated.
 */
data class ZoneStats(
    val shots: Int = 0,
    val hits: Int = 0,
    val perZoneHits: Map<HitZone, Int> = emptyMap(),
    val damage: Float = 0f,
) {
    val headHits: Int get() = perZoneHits[HitZone.HEAD] ?: 0

    /** Head hits as a fraction of all *landed* hits, 0 when nothing landed (never NaN). */
    val headAccuracy: Float get() = if (hits <= 0) 0f else (headHits.toFloat() / hits).coerceIn(0f, 1f)

    /** Records one shot: a miss adds only a shot; a hit adds the zone and its damage. */
    fun recordShot(zone: HitZone?, damageMultiplier: Float): ZoneStats {
        if (zone == null) return copy(shots = shots + 1)
        val next = perZoneHits.toMutableMap()
        next[zone] = (next[zone] ?: 0) + 1
        return copy(
            shots = shots + 1,
            hits = hits + 1,
            perZoneHits = next,
            damage = damage + damageMultiplier,
        )
    }

    companion object {
        val EMPTY = ZoneStats()
    }
}
