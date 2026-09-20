package com.gamecore.aimlab.engine

/**
 * The record of one completed training session — the only thing that reaches Room.
 *
 * High-frequency per-frame data (tracking error samples, gyro correction, recoil residuals) lives in
 * memory during the run and is reduced to these fields at the end (§18/§B). Every field a mode does not
 * produce is left at its zero/absent value rather than invented; [isValid] is what the repository checks
 * before writing, so an empty or corrupt session is rejected rather than stored as a real one.
 *
 * Times are epoch millis from the [Clock]. [mode]/[difficulty]/[weaponName]/[sensitivityName] are the
 * separation keys personal records are grouped by.
 */
data class SessionSummary(
    val id: Long = 0L,
    val mode: TrainingMode,
    val difficulty: Difficulty,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val weaponName: String? = null,
    val sensitivityName: String? = null,
    val score: Int = 0,
    val hits: Int = 0,
    val shots: Int = 0,
    val targetsMissed: Int = 0,
    val reactionStats: ReactionStats = ReactionStats.EMPTY,
    val averageAcquireMillis: Float = 0f,
    val trackingErrorAverage: Float = 0f,
    val timeOnTargetFraction: Float = 0f,
    val recoilCompensation: Float = 0f,
    val gyroStability: Float = 0f,
    /**
     * Which scoring/rendering generation produced this session (§6).
     *
     * The 3D view measures aim in angles and hits by ray–sphere, so its scores are not comparable with
     * the 2D arena's — a flick score of 4000 means different things in each. This field records which
     * engine wrote the row so history can label the old ones "legacy 2D" and records never rank a 2D
     * result against a 3D one. Every session written before version 8 is [SCORING_VERSION_2D] by the
     * migration's default; every session written by the 3D loop is [SCORING_VERSION_3D].
     */
    val scoringVersion: Int = SCORING_VERSION_3D,
) {
    /** Duration in milliseconds. Clamped at zero so a clock hiccup cannot produce a negative session. */
    val durationMillis: Long get() = (endedAtMillis - startedAtMillis).coerceAtLeast(0L)

    val accuracyPercent: Int get() = Stats.accuracyPercent(hits, shots)

    /**
     * Whether this session is worth storing.
     *
     * A session must have a non-negative duration, a start time, and evidence that something happened:
     * either a shot was fired, a reaction was recorded, or tracking time accumulated. Free practice with
     * no activity is not saved. This is the gate §17/§B2.9 asks for — invalid sessions are rejected, not
     * written as zeros.
     */
    val isValid: Boolean
        get() = startedAtMillis > 0L &&
            endedAtMillis >= startedAtMillis &&
            (shots > 0 || reactionStats.attempts > 0 || timeOnTargetFraction > 0f || hits > 0)

    /** True for a session recorded by the old 2D arena, so the UI can label it "legacy 2D" (§6). */
    val isLegacy2D: Boolean get() = scoringVersion < SCORING_VERSION_3D

    companion object {
        /** The flat-arena scoring generation. Every pre-version-8 row is back-filled to this. */
        const val SCORING_VERSION_2D = 1

        /** The first-person 3D scoring generation, in angles and ray–sphere hits. */
        const val SCORING_VERSION_3D = 2
    }
}

/**
 * A personal best for one (mode, difficulty, weapon) combination.
 *
 * Records are separated by all three keys (§B2.8): a best flick score on Easy with a pistol is a different
 * record from Hard with a sniper. [previousValue] keeps the record that was beaten so the UI can show
 * "previous: …". [higherIsBetter] flips the comparison for reaction time, where a *lower* value wins.
 */
data class PersonalRecord(
    val mode: TrainingMode,
    val difficulty: Difficulty,
    val weaponName: String?,
    val metric: RecordMetric,
    val value: Float,
    val previousValue: Float?,
    val achievedAtMillis: Long,
    /**
     * The scoring generation this record belongs to (§6).
     *
     * A record is never challenged across generations: a 3D flick score and a 2D flick score are
     * different measurements, so they are separate records even for the same mode/difficulty/weapon. The
     * generation is part of [key], so the two never collide, and the repository only ever compares a new
     * session against a record of the same version.
     */
    val scoringVersion: Int = SessionSummary.SCORING_VERSION_3D,
) {
    /** The grouping key records are separated by. Weapon-less modes use an empty weapon slot. */
    val key: String
        get() = "${mode.name}|${difficulty.name}|${weaponName.orEmpty()}|${metric.name}|v$scoringVersion"
}

/**
 * The metrics a personal record can track, and their comparison direction.
 *
 * [higherIsBetter] is the whole reason this enum carries behaviour: a bigger score is better, but a
 * smaller reaction time is. [PersonalRecordBook] uses it so "is this a new record" is correct for both.
 */
enum class RecordMetric(val label: String, val higherIsBetter: Boolean) {
    SCORE("Best score", higherIsBetter = true),
    ACCURACY("Best accuracy", higherIsBetter = true),
    FASTEST_REACTION("Fastest reaction", higherIsBetter = false),
    TRACKING_SCORE("Best tracking", higherIsBetter = true),
    RECOIL_SCORE("Best recoil control", higherIsBetter = true),
    GYRO_SCORE("Best gyro score", higherIsBetter = true),
    LONGEST_SESSION("Longest session", higherIsBetter = true),
    ;

    companion object {
        fun fromName(name: String?): RecordMetric? = entries.firstOrNull { it.name == name }
    }
}
