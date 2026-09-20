package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides a new personal best, and which metrics a mode can even set one for.
 *
 * §B2.8 promises a worse result changes nothing and a tie does not churn the "achieved at" date, so the
 * comparison must be strict and must honour direction — a faster reaction beats a slower one while a
 * bigger score beats a smaller one. The other promise is that the book is never challenged with a number
 * a mode never produced: [PersonalRecordBook.candidatesFor] must offer exactly the metrics the session
 * measured, which is asserted per mode below.
 */
class PersonalRecordBookTest {

    private fun challenge(
        current: PersonalRecord?,
        metric: RecordMetric,
        candidate: Float,
    ): PersonalRecord? = PersonalRecordBook.challenge(
        current = current,
        mode = TrainingMode.FLICK,
        difficulty = Difficulty.NORMAL,
        weaponName = null,
        metric = metric,
        candidateValue = candidate,
        achievedAtMillis = 1_000L,
    )

    private fun record(metric: RecordMetric, value: Float) = PersonalRecord(
        mode = TrainingMode.FLICK,
        difficulty = Difficulty.NORMAL,
        weaponName = null,
        metric = metric,
        value = value,
        previousValue = null,
        achievedAtMillis = 500L,
    )

    @Test
    fun `with no existing record any candidate is a new record with no previous value`() {
        val result = challenge(current = null, metric = RecordMetric.SCORE, candidate = 100f)
        assertNotNull(result)
        assertEquals(100f, result!!.value, 1e-4f)
        assertNull(result.previousValue)
    }

    @Test
    fun `a higher-is-better metric replaces only on a strictly greater value`() {
        val current = record(RecordMetric.SCORE, 100f)
        val win = challenge(current, RecordMetric.SCORE, candidate = 150f)
        assertNotNull(win)
        assertEquals(100f, win!!.previousValue!!, 1e-4f)

        assertNull(challenge(current, RecordMetric.SCORE, candidate = 100f)) // tie loses
        assertNull(challenge(current, RecordMetric.SCORE, candidate = 50f)) // worse loses
    }

    @Test
    fun `a lower-is-better metric replaces only on a strictly smaller value`() {
        val current = record(RecordMetric.FASTEST_REACTION, 250f)
        val win = PersonalRecordBook.challenge(
            current = current,
            mode = TrainingMode.REACTION,
            difficulty = Difficulty.NORMAL,
            weaponName = null,
            metric = RecordMetric.FASTEST_REACTION,
            candidateValue = 200f,
            achievedAtMillis = 1_000L,
        )
        assertNotNull(win)
        assertEquals(250f, win!!.previousValue!!, 1e-4f)

        assertNull(
            PersonalRecordBook.challenge(
                current, TrainingMode.REACTION, Difficulty.NORMAL, null,
                RecordMetric.FASTEST_REACTION, 300f, 1_000L,
            ),
        )
    }

    private fun summary(
        mode: TrainingMode,
        hits: Int = 0,
        shots: Int = 0,
        reactionStats: ReactionStats = ReactionStats.EMPTY,
        timeOnTargetFraction: Float = 0f,
    ) = SessionSummary(
        mode = mode,
        difficulty = Difficulty.NORMAL,
        startedAtMillis = 1_000L,
        endedAtMillis = 2_000L,
        score = 500,
        hits = hits,
        shots = shots,
        reactionStats = reactionStats,
        timeOnTargetFraction = timeOnTargetFraction,
    )

    private fun metricsOf(summary: SessionSummary): Set<RecordMetric> =
        PersonalRecordBook.candidatesFor(summary).map { it.first }.toSet()

    @Test
    fun `an unscored mode offers no record candidates`() {
        assertTrue(PersonalRecordBook.candidatesFor(summary(TrainingMode.FREE_PRACTICE)).isEmpty())
    }

    @Test
    fun `flick offers score, longest session and accuracy only when shots were fired`() {
        val withShots = metricsOf(summary(TrainingMode.FLICK, hits = 8, shots = 10))
        assertTrue(withShots.containsAll(setOf(RecordMetric.SCORE, RecordMetric.ACCURACY, RecordMetric.LONGEST_SESSION)))

        val noShots = metricsOf(summary(TrainingMode.FLICK, hits = 0, shots = 0, timeOnTargetFraction = 0.5f))
        assertTrue(noShots.contains(RecordMetric.SCORE))
        assertTrue(noShots.contains(RecordMetric.LONGEST_SESSION))
        assertTrue(!noShots.contains(RecordMetric.ACCURACY))
    }

    @Test
    fun `reaction offers fastest reaction only when a reaction was recorded`() {
        val withReaction = metricsOf(
            summary(TrainingMode.REACTION, hits = 5, shots = 5, reactionStats = ReactionStats.from(listOf(200L, 250L))),
        )
        assertTrue(withReaction.contains(RecordMetric.FASTEST_REACTION))

        val noReaction = metricsOf(summary(TrainingMode.REACTION, hits = 1, shots = 1))
        assertTrue(!noReaction.contains(RecordMetric.FASTEST_REACTION))
    }

    @Test
    fun `tracking, recoil and gyro each offer their own score metric`() {
        assertTrue(metricsOf(summary(TrainingMode.TRACKING, timeOnTargetFraction = 0.5f)).contains(RecordMetric.TRACKING_SCORE))
        assertTrue(metricsOf(summary(TrainingMode.RECOIL, shots = 10)).contains(RecordMetric.RECOIL_SCORE))
        assertTrue(metricsOf(summary(TrainingMode.GYRO, timeOnTargetFraction = 0.5f)).contains(RecordMetric.GYRO_SCORE))
    }
}
