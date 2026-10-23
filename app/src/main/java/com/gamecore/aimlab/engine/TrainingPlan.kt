package com.gamecore.aimlab.engine

import kotlin.math.roundToInt

/**
 * One concrete answer to "what should I train today?", with the evidence that earned it.
 *
 * [mode] and [difficulty] are what the button launches; [reason] is the short headline and [detail] is the
 * measured justification. The detail always names the real figure and sample it came from — a percentage,
 * a score, a reaction time, a session count — so the plan never asks the user to trust a hunch.
 */
data class TrainingPlanRecommendation(
    val mode: TrainingMode,
    val difficulty: Difficulty,
    val reason: String,
    val detail: String,
) {
    /**
     * The short name the home card prints for [mode].
     *
     * Deliberately not [TrainingMode.label]: that is the longer "Flick training" form used on the
     * destination rows. Tilt gets its own word here because it is measured from a lean against gravity,
     * not gyro rotation, and calling it "Gyro" would misdescribe the run the button starts.
     */
    val modeLabel: String
        get() = when (mode) {
            TrainingMode.FLICK -> "Flick"
            TrainingMode.TRACKING -> "Tracking"
            TrainingMode.REACTION -> "Reaction"
            TrainingMode.GYRO -> "Gyro"
            TrainingMode.TILT -> "Tilt"
            TrainingMode.RECOIL -> "Recoil"
            TrainingMode.MOVEMENT -> "Movement"
            TrainingMode.FREE_PRACTICE -> "Free practice"
        }
}

/**
 * Chooses today's training from what the user has actually recorded — no scores are invented and no mode
 * is suggested from a run that does not exist.
 *
 * The rules are ordered by how actionable the weakness is. Tracking comes first because time-on-target is
 * a directly measured fraction; reaction next because a slow median is unambiguous; then a flick score
 * that has fallen off its own recent average; then the two stability modes, gyro before tilt. Only when
 * none of those is true does the plan fall through to free practice (when the last three runs show the
 * user is ready for the next step) or a maintenance flick run.
 *
 * A mode is never recommended on absent evidence: every weakness branch requires at least one session of
 * that mode, and the cold-start branch is the one case where flick is suggested before any flick history
 * exists.
 */
object TrainingPlan {

    /** Fewer than this many runs is not enough history to describe a weakness. */
    private const val MIN_SESSIONS = 3

    /** How many recent runs of a mode are folded into its "recent" figure. */
    private const val RECENT_WINDOW = 3

    /** Below this time-on-target fraction, tracking is the weakest measured skill. */
    private const val TRACKING_FLOOR = 0.60f

    /** Reaction times worse than this many milliseconds are worth training. */
    private const val REACTION_CEILING_MILLIS = 350f

    /** A flick score this far below its recent average is a real dip, not normal variance. */
    private const val FLICK_DROP_FRACTION = 0.10f

    /** Below this 0..1 stability, gyro or tilt control is the weakest measured skill. */
    private const val STABILITY_FLOOR = 0.70f

    /** The accuracy the last three runs must each exceed before free practice steps up a difficulty. */
    private const val MASTERY_ACCURACY_PERCENT = 80

    /**
     * Builds the recommendation, or null only when there is nothing at all to build one from.
     *
     * The current implementation always returns a recommendation: an empty history gets the cold-start
     * flick run, which is an honest first step rather than a guess about a skill. Keeping the return type
     * nullable leaves room for a future rule set that genuinely has no advice, and the screen already
     * hides the card when it is null.
     */
    fun recommend(
        sessions: List<SessionSummary>,
        records: List<PersonalRecord>,
    ): TrainingPlanRecommendation? {
        // Newest first, defensively — the repository already orders this way, but the plan is a pure
        // function and must not depend on its caller's ordering.
        val recent = sessions.sortedByDescending { it.startedAtMillis }

        if (recent.size < MIN_SESSIONS) {
            return TrainingPlanRecommendation(
                mode = TrainingMode.FLICK,
                difficulty = Difficulty.NORMAL,
                reason = "Start with the fundamentals",
                detail = "Three flick runs will give the plan enough data to work with.",
            )
        }

        trackingWeakness(recent)?.let { return it }
        reactionWeakness(recent, records)?.let { return it }
        flickWeakness(recent)?.let { return it }
        stabilityWeakness(recent, TrainingMode.GYRO)?.let { return it }
        stabilityWeakness(recent, TrainingMode.TILT)?.let { return it }

        return maintenancePlan(recent)
    }

    /**
     * Tracking: the average time-on-target of the last few tracking runs is below [TRACKING_FLOOR].
     *
     * Averaging rather than reading only the latest run keeps one bad start from dominating, and the
     * detail names both the measured percentage and how many runs it came from. No tracking sessions means
     * no recommendation — a zero in a flick row is not evidence about tracking.
     */
    private fun trackingWeakness(recent: List<SessionSummary>): TrainingPlanRecommendation? {
        val tracking = recent
            .filter { it.mode == TrainingMode.TRACKING }
            .take(RECENT_WINDOW)
        if (tracking.isEmpty()) return null

        val average = tracking
            .map { it.timeOnTargetFraction.coerceIn(0f, 1f) }
            .average()
            .toFloat()
        if (average >= TRACKING_FLOOR) return null

        val count = tracking.size
        return TrainingPlanRecommendation(
            mode = TrainingMode.TRACKING,
            difficulty = tracking.first().difficulty,
            reason = "Tracking is the weakest part of your aim",
            detail = "Time on target averaged ${percent(average)}% over the last $count tracking " +
                (if (count == 1) "session." else "sessions."),
        )
    }

    /**
     * Reaction: the latest reaction test's typical time is slower than [REACTION_CEILING_MILLIS].
     *
     * The median is the fair figure — one lucky fast tap should not hide a slow set — and the fastest is
     * printed beside it so the detail is complete evidence rather than a single number. When the stored
     * session did not carry reaction samples, a fastest-reaction personal record is used instead: a best
     * ever slower than the ceiling proves every attempt is slow too.
     */
    private fun reactionWeakness(
        recent: List<SessionSummary>,
        records: List<PersonalRecord>,
    ): TrainingPlanRecommendation? {
        val reactionSessions = recent.filter { it.mode == TrainingMode.REACTION }
        if (reactionSessions.isEmpty()) return null

        val latest = reactionSessions.firstOrNull { it.reactionStats.attempts > 0 }
        if (latest != null) {
            val stats = latest.reactionStats
            val median = stats.medianMillis.takeIf { it > 0f }
            val fastest = stats.fastestMillis.takeIf { it > 0L }

            if (median != null || fastest != null) {
                val tooSlow = (median != null && median > REACTION_CEILING_MILLIS) ||
                    (fastest != null && fastest > REACTION_CEILING_MILLIS)
                if (!tooSlow) return null

                val parts = buildList {
                    if (fastest != null) add("a $fastest ms fastest time")
                    if (median != null) add("a ${median.roundToInt()} ms median")
                }
                return TrainingPlanRecommendation(
                    mode = TrainingMode.REACTION,
                    difficulty = latest.difficulty,
                    reason = "Your reactions are slower than target",
                    detail = "Your last reaction test measured ${parts.joinToString(" and ")}.",
                )
            }
        }

        // No usable samples in the session itself; fall back to the best reaction ever recorded. The
        // record is the *fastest* value, so a best slower than the ceiling means every run was slower.
        val recordFastest = records
            .filter { it.mode == TrainingMode.REACTION && it.metric == RecordMetric.FASTEST_REACTION }
            .minOfOrNull { it.value }
            ?: return null
        if (recordFastest <= REACTION_CEILING_MILLIS) return null

        return TrainingPlanRecommendation(
            mode = TrainingMode.REACTION,
            difficulty = reactionSessions.first().difficulty,
            reason = "Your reactions are slower than target",
            detail = "Your best recorded reaction time is ${recordFastest.roundToInt()} ms.",
        )
    }

    /**
     * Flick: the latest flick run scored more than [FLICK_DROP_FRACTION] below the recent flick average.
     *
     * The average deliberately excludes the run being judged and takes the preceding few flick runs, so a
     * single weak result is measured against the user's own normal rather than against a zero-inflated
     * window that includes the dip itself. At least two flick runs are needed: with one there is no
     * "recent average" and no honest claim of a decline.
     */
    private fun flickWeakness(recent: List<SessionSummary>): TrainingPlanRecommendation? {
        val flicks = recent.filter { it.mode == TrainingMode.FLICK }
        if (flicks.size < 2) return null

        val latest = flicks.first()
        val baseline = flicks.drop(1).take(RECENT_WINDOW - 1)
        if (baseline.isEmpty()) return null

        val average = baseline.map { it.score.toFloat() }.average().toFloat()
        if (average <= 0f) return null
        if (latest.score >= average * (1f - FLICK_DROP_FRACTION)) return null

        val dropPercent = ((average - latest.score) / average * 100f).roundToInt()
        return TrainingPlanRecommendation(
            mode = TrainingMode.FLICK,
            difficulty = latest.difficulty,
            reason = "Your flick score has dipped",
            detail = "Your last flick score was ${latest.score} points, $dropPercent% below your recent " +
                "average of ${average.roundToInt()} points.",
        )
    }

    /**
     * Gyro or tilt: the recent stability average is below [STABILITY_FLOOR].
     *
     * The two modes are separate evidence sources and separate recommendations. Tilt is measured from a
     * lean against gravity, so its text says "tilt" throughout; it is never described as gyro even though
     * the training loop uses the same stability formula for both.
     */
    private fun stabilityWeakness(
        recent: List<SessionSummary>,
        mode: TrainingMode,
    ): TrainingPlanRecommendation? {
        val runs = recent
            .filter { it.mode == mode }
            .take(RECENT_WINDOW)
        if (runs.isEmpty()) return null

        val average = runs
            .map { it.gyroStability.coerceIn(0f, 1f) }
            .average()
            .toFloat()
        if (average >= STABILITY_FLOOR) return null

        val name = if (mode == TrainingMode.TILT) "Tilt" else "Gyro"
        val count = runs.size
        return TrainingPlanRecommendation(
            mode = mode,
            difficulty = runs.first().difficulty,
            reason = "$name control needs work",
            detail = "$name stability averaged ${percent(average)}% over the last $count " +
                "${name.lowercase()} ${if (count == 1) "session." else "sessions."}",
        )
    }

    /**
     * The all-clear: step up to free practice when the last three runs all cleared 80% accuracy, else a
     * maintenance flick run at the difficulty the user is currently working at.
     *
     * Every one of the last three runs must carry shot evidence and beat the threshold — two good runs and
     * one with no shots is not proof of mastery. The difficulty is the most recent run's, so the plan
     * follows the user's own progression rather than an arbitrary default.
     */
    private fun maintenancePlan(recent: List<SessionSummary>): TrainingPlanRecommendation {
        val latest = recent.first()
        val current = latest.difficulty
        val lastThree = recent.take(MIN_SESSIONS)
        val mastered = lastThree.size == MIN_SESSIONS &&
            lastThree.all { it.shots > 0 && it.accuracyPercent > MASTERY_ACCURACY_PERCENT }

        if (mastered) {
            val next = nextDifficulty(current)
            return TrainingPlanRecommendation(
                mode = TrainingMode.FREE_PRACTICE,
                difficulty = next,
                reason = "You're ready to move up",
                detail = "Your last 3 runs all finished above 80% accuracy, so free practice at " +
                    "${next.label} will stretch you.",
            )
        }

        return TrainingPlanRecommendation(
            mode = TrainingMode.FLICK,
            difficulty = current,
            reason = "Keep your flick sharp",
            detail = "No single weakness stood out, so a flick run at ${current.label} keeps your aim sharp.",
        )
    }

    /** One step harder on the fixed scale; the top step and Custom stay where they are. */
    private fun nextDifficulty(current: Difficulty): Difficulty = when (current) {
        Difficulty.EASY -> Difficulty.NORMAL
        Difficulty.NORMAL -> Difficulty.HARD
        Difficulty.HARD -> Difficulty.EXTREME
        Difficulty.EXTREME -> Difficulty.EXTREME
        Difficulty.CUSTOM -> Difficulty.CUSTOM
    }

    /** A whole percent for the detail text, consistent with how the rest of the section shows percentages. */
    private fun percent(fraction: Float): Int = (fraction * 100f).roundToInt()
}
