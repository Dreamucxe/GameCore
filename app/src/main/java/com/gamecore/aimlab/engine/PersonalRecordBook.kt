package com.gamecore.aimlab.engine

/**
 * Decides whether a finished session beats an existing personal record, and by what.
 *
 * Pure comparison logic, separate from storage: the repository owns the stored records, and this decides —
 * given a candidate value and the current record for the same key — whether the candidate wins, honouring
 * [RecordMetric.higherIsBetter] so a faster reaction beats a slower one while a bigger score beats a
 * smaller one. A win carries the beaten value forward as [PersonalRecord.previousValue]; a non-win returns
 * null and nothing is stored (§B2.8: worse result changes nothing).
 */
object PersonalRecordBook {

    /**
     * Returns the new [PersonalRecord] if [candidateValue] beats [current], or null if it does not.
     *
     * With no existing record, any candidate is a new record with `previousValue = null`. With one, the
     * candidate must be strictly better in the metric's direction — ties do not replace, so a repeated
     * identical result does not churn the "achieved at" date.
     */
    fun challenge(
        current: PersonalRecord?,
        mode: TrainingMode,
        difficulty: Difficulty,
        weaponName: String?,
        metric: RecordMetric,
        candidateValue: Float,
        achievedAtMillis: Long,
        scoringVersion: Int = SessionSummary.SCORING_VERSION_3D,
    ): PersonalRecord? {
        val beats = when {
            current == null -> true
            metric.higherIsBetter -> candidateValue > current.value
            else -> candidateValue < current.value
        }
        if (!beats) return null
        return PersonalRecord(
            mode = mode,
            difficulty = difficulty,
            weaponName = weaponName,
            metric = metric,
            value = candidateValue,
            previousValue = current?.value,
            achievedAtMillis = achievedAtMillis,
            scoringVersion = scoringVersion,
        )
    }

    /**
     * The metrics a session can set records for, with the value each contributes.
     *
     * Only the metrics the session actually measured are returned — a flick session offers SCORE and
     * ACCURACY but not TRACKING_SCORE — so the record book is never challenged with a zero the mode never
     * produced. LONGEST_SESSION is offered by every scored mode. Reaction offers FASTEST_REACTION only
     * when at least one reaction was recorded.
     */
    fun candidatesFor(summary: SessionSummary): List<Pair<RecordMetric, Float>> {
        if (!summary.mode.scored) return emptyList()
        val out = mutableListOf<Pair<RecordMetric, Float>>()
        out += RecordMetric.LONGEST_SESSION to summary.durationMillis.toFloat()
        when (summary.mode) {
            TrainingMode.FLICK, TrainingMode.MOVEMENT -> {
                out += RecordMetric.SCORE to summary.score.toFloat()
                if (summary.shots > 0) out += RecordMetric.ACCURACY to Stats.accuracy(summary.hits, summary.shots)
            }
            TrainingMode.REACTION -> {
                out += RecordMetric.SCORE to summary.score.toFloat()
                if (summary.reactionStats.attempts > 0) {
                    out += RecordMetric.FASTEST_REACTION to summary.reactionStats.fastestMillis.toFloat()
                }
            }
            TrainingMode.TRACKING -> {
                out += RecordMetric.TRACKING_SCORE to summary.score.toFloat()
            }
            TrainingMode.RECOIL -> {
                out += RecordMetric.RECOIL_SCORE to summary.score.toFloat()
            }
            TrainingMode.GYRO -> {
                out += RecordMetric.GYRO_SCORE to summary.score.toFloat()
            }
            TrainingMode.FREE_PRACTICE -> Unit
        }
        return out
    }
}
