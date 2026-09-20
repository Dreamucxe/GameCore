package com.gamecore.aimlab.engine

/**
 * The training modes Aim Lab offers, and the two axes every session is filed under.
 *
 * The enum name is the stable key stored in Room and in personal-record separation — never the label —
 * so renaming a label never orphans history. [scored] marks the modes that produce a comparable score for
 * personal records; the sandbox and the two editors do not.
 */
enum class TrainingMode(val label: String, val scored: Boolean) {
    FLICK("Flick training", scored = true),
    TRACKING("Tracking training", scored = true),
    REACTION("Reaction test", scored = true),
    GYRO("Gyro training", scored = true),
    RECOIL("Recoil training", scored = true),
    MOVEMENT("Movement training", scored = true),
    FREE_PRACTICE("Free practice", scored = false),
    ;

    companion object {
        /** Parse a stored name defensively — an unknown one is dropped, never thrown on. */
        fun fromName(name: String?): TrainingMode? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The five difficulty steps, mapped to real parameters by [DifficultyParameters.forLevel].
 *
 * CUSTOM carries no numbers of its own — it means "use the values the user set" — so the mapping returns
 * a neutral baseline for it and the caller overlays the stored custom values.
 */
enum class Difficulty(val label: String) {
    EASY("Easy"),
    NORMAL("Normal"),
    HARD("Hard"),
    EXTREME("Extreme"),
    CUSTOM("Custom"),
    ;

    companion object {
        fun fromName(name: String?): Difficulty = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * The concrete parameters a difficulty resolves to, all in engine-normalised units.
 *
 * These are the numbers the modes actually run on, and [DifficultyParameters.forLevel] is the single
 * source of the Easy→Extreme progression so a test can assert that harder really does mean smaller,
 * faster and more — not just a different label (§16 forbids a label-only difficulty).
 *
 * @param targetRadius radius as a fraction of the arena's smaller side. Smaller is harder.
 * @param targetSpeed movement speed in arena units per second (tracking/moving targets). Faster is harder.
 * @param spawnIntervalMillis time between spawns in spawn-driven modes. Shorter is harder.
 * @param simultaneousTargets how many targets are alive at once. More is harder.
 * @param targetLifetimeMillis how long a flick target waits before it counts as missed. Shorter is harder.
 */
data class DifficultyParameters(
    val targetRadius: Float,
    val targetSpeed: Float,
    val spawnIntervalMillis: Long,
    val simultaneousTargets: Int,
    val targetLifetimeMillis: Long,
) {
    companion object {
        /**
         * The Easy→Extreme table.
         *
         * Every field moves monotonically across the four fixed levels: radius shrinks, speed rises, the
         * spawn interval and target lifetime shorten, and the simultaneous count climbs. CUSTOM returns the
         * NORMAL baseline for the caller to overwrite with the user's own values; it is never used raw.
         *
         * The numbers are chosen so the four levels are unmistakably different (the unit tests assert strict
         * inequalities on every field), not tuned to any real game — these are training parameters.
         */
        fun forLevel(level: Difficulty): DifficultyParameters = when (level) {
            Difficulty.EASY -> DifficultyParameters(
                targetRadius = 0.090f,
                targetSpeed = 0.10f,
                spawnIntervalMillis = 1_400L,
                simultaneousTargets = 1,
                targetLifetimeMillis = 3_000L,
            )
            Difficulty.NORMAL, Difficulty.CUSTOM -> DifficultyParameters(
                targetRadius = 0.065f,
                targetSpeed = 0.20f,
                spawnIntervalMillis = 1_000L,
                simultaneousTargets = 2,
                targetLifetimeMillis = 2_200L,
            )
            Difficulty.HARD -> DifficultyParameters(
                targetRadius = 0.045f,
                targetSpeed = 0.34f,
                spawnIntervalMillis = 720L,
                simultaneousTargets = 3,
                targetLifetimeMillis = 1_500L,
            )
            Difficulty.EXTREME -> DifficultyParameters(
                targetRadius = 0.028f,
                targetSpeed = 0.52f,
                spawnIntervalMillis = 480L,
                simultaneousTargets = 4,
                targetLifetimeMillis = 1_000L,
            )
        }
    }
}
