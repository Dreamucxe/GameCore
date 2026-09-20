package com.gamecore.aimlab.engine3d

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.DifficultyParameters

/**
 * Maps a [Difficulty] to concrete 3D-world parameters (§3: difficulty → real 3D parameters).
 *
 * The 2D [DifficultyParameters] speaks in arena fractions; a 3D room speaks in metres and degrees. This
 * derives the world figures from the same Easy→Extreme progression so the two views stay in step: a
 * target's world radius, how near and far it spawns, the angular cone it can appear within, how fast a
 * tracked sphere moves across the wall, the spawn interval, and how many are alive at once. Harder means
 * a smaller sphere, further away, over a wider arc, moving faster, spawning sooner, more at once — every
 * field moves monotonically across the four fixed levels, which the unit tests assert.
 *
 * The spawn interval, simultaneous count and lifetime are taken straight from [DifficultyParameters] so
 * the pacing logic is shared with 2D; only the spatial figures are new. CUSTOM resolves to the NORMAL
 * baseline for the caller to overlay, exactly as in 2D.
 *
 * @param targetRadius sphere radius in metres.
 * @param minDistance nearest a flick/reaction target spawns, metres.
 * @param maxDistance furthest a flick/reaction target spawns, metres.
 * @param maxYawDegrees half-width of the horizontal spawn cone from straight ahead.
 * @param maxPitchDegrees half-height of the vertical spawn cone.
 * @param trackingSpeed metres/second a tracked sphere travels.
 * @param spawnIntervalMillis time between spawns (shared with 2D pacing).
 * @param simultaneousTargets how many targets are alive at once (shared with 2D).
 * @param targetLifetimeMillis how long a flick target waits before it counts as missed (shared with 2D).
 * @param onTargetAngleDegrees the aim-error angle within which a tracking frame counts as on target.
 */
data class Difficulty3DParameters(
    val targetRadius: Float,
    val minDistance: Float,
    val maxDistance: Float,
    val maxYawDegrees: Float,
    val maxPitchDegrees: Float,
    val trackingSpeed: Float,
    val spawnIntervalMillis: Long,
    val simultaneousTargets: Int,
    val targetLifetimeMillis: Long,
    val onTargetAngleDegrees: Float,
) {
    companion object {
        fun forLevel(level: Difficulty): Difficulty3DParameters {
            val base = DifficultyParameters.forLevel(level)
            return when (level) {
                Difficulty.EASY -> Difficulty3DParameters(
                    targetRadius = 0.55f,
                    minDistance = 4f, maxDistance = 7f,
                    maxYawDegrees = 22f, maxPitchDegrees = 12f,
                    trackingSpeed = 1.2f,
                    spawnIntervalMillis = base.spawnIntervalMillis,
                    simultaneousTargets = base.simultaneousTargets,
                    targetLifetimeMillis = base.targetLifetimeMillis,
                    onTargetAngleDegrees = 3.5f,
                )
                Difficulty.NORMAL, Difficulty.CUSTOM -> Difficulty3DParameters(
                    targetRadius = 0.42f,
                    minDistance = 5f, maxDistance = 9f,
                    maxYawDegrees = 34f, maxPitchDegrees = 18f,
                    trackingSpeed = 2.2f,
                    spawnIntervalMillis = base.spawnIntervalMillis,
                    simultaneousTargets = base.simultaneousTargets,
                    targetLifetimeMillis = base.targetLifetimeMillis,
                    onTargetAngleDegrees = 2.6f,
                )
                Difficulty.HARD -> Difficulty3DParameters(
                    targetRadius = 0.30f,
                    minDistance = 6f, maxDistance = 11f,
                    maxYawDegrees = 46f, maxPitchDegrees = 24f,
                    trackingSpeed = 3.6f,
                    spawnIntervalMillis = base.spawnIntervalMillis,
                    simultaneousTargets = base.simultaneousTargets,
                    targetLifetimeMillis = base.targetLifetimeMillis,
                    onTargetAngleDegrees = 1.9f,
                )
                Difficulty.EXTREME -> Difficulty3DParameters(
                    targetRadius = 0.20f,
                    minDistance = 7f, maxDistance = 13f,
                    maxYawDegrees = 58f, maxPitchDegrees = 30f,
                    trackingSpeed = 5.4f,
                    spawnIntervalMillis = base.spawnIntervalMillis,
                    simultaneousTargets = base.simultaneousTargets,
                    targetLifetimeMillis = base.targetLifetimeMillis,
                    onTargetAngleDegrees = 1.3f,
                )
            }
        }
    }
}
