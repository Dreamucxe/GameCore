package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that decides whether a finished run becomes a stored session at all.
 *
 * This is the single place §30's "never generate fake statistics" is enforced in code: a run that recorded
 * nothing must not reach Room, because a row of zeros is indistinguishable from a real session the user
 * played badly, and it would drag every average on the statistics screen down with a run that never
 * happened. `AimTrainingLoop.stop()` returns null on `!isValid`, so these cases are the difference between
 * an honest history and a padded one.
 */
class SessionSummaryTest {

    private fun summary(
        mode: TrainingMode = TrainingMode.FLICK,
        startedAtMillis: Long = 1_000L,
        endedAtMillis: Long = 61_000L,
        score: Int = 0,
        hits: Int = 0,
        shots: Int = 0,
        reactionStats: ReactionStats = ReactionStats.EMPTY,
        timeOnTargetFraction: Float = 0f,
    ) = SessionSummary(
        mode = mode,
        difficulty = Difficulty.NORMAL,
        startedAtMillis = startedAtMillis,
        endedAtMillis = endedAtMillis,
        score = score,
        hits = hits,
        shots = shots,
        reactionStats = reactionStats,
        timeOnTargetFraction = timeOnTargetFraction,
    )

    @Test
    fun `a run that recorded nothing at all is not a session`() {
        assertFalse(summary().isValid)
    }

    @Test
    fun `a run with a timestamp problem is rejected even when it has results`() {
        // A clock that went backwards, or a summary assembled before the run started, would produce a
        // negative duration and a nonsense entry in the history.
        assertFalse(summary(startedAtMillis = 0L, shots = 10, hits = 4).isValid)
        assertFalse(summary(startedAtMillis = 5_000L, endedAtMillis = 4_000L, shots = 10, hits = 4).isValid)
    }

    @Test
    fun `a zero-length run is allowed as long as it recorded something`() {
        // Two events in the same millisecond is possible on a coarse clock; the evidence, not the
        // duration, is what makes the session real.
        assertTrue(summary(startedAtMillis = 5_000L, endedAtMillis = 5_000L, shots = 3, hits = 1).isValid)
    }

    @Test
    fun `any one form of recorded evidence is enough`() {
        assertTrue(summary(shots = 1).isValid)
        assertTrue(summary(hits = 1).isValid)
        assertTrue(summary(reactionStats = ReactionStats.from(listOf(250L))).isValid)
        assertTrue(summary(timeOnTargetFraction = 0.4f).isValid)
    }

    @Test
    fun `a missed-every-shot run still counts, because missing is a real result`() {
        assertTrue(summary(shots = 20, hits = 0).isValid)
        assertEquals(0, summary(shots = 20, hits = 0).accuracyPercent)
    }

    @Test
    fun `duration is the elapsed span and never negative`() {
        assertEquals(60_000L, summary().durationMillis)
        assertEquals(0L, summary(startedAtMillis = 5_000L, endedAtMillis = 4_000L).durationMillis)
    }

    @Test
    fun `accuracy percent comes from the shared helper rather than its own arithmetic`() {
        val s = summary(hits = 3, shots = 4)
        assertEquals(Stats.accuracyPercent(3, 4), s.accuracyPercent)
        assertEquals(75, s.accuracyPercent)
    }

    @Test
    fun `a record key separates mode, difficulty, weapon and metric`() {
        val base = PersonalRecord(
            mode = TrainingMode.FLICK,
            difficulty = Difficulty.HARD,
            weaponName = "AR",
            metric = RecordMetric.SCORE,
            value = 100f,
            previousValue = null,
            achievedAtMillis = 1L,
        )
        // The key now carries the scoring generation as its last segment (§6), so a 3D record and a 2D
        // record for the same combination are different keys and can never overwrite one another.
        assertEquals("FLICK|HARD|AR|SCORE|v${SessionSummary.SCORING_VERSION_3D}", base.key)
        // Each field genuinely participates, so a hard-difficulty record cannot overwrite an easy one and
        // a pistol score cannot overwrite a rifle score.
        assertNotEquals(base.key, base.copy(difficulty = Difficulty.EASY).key)
        assertNotEquals(base.key, base.copy(mode = TrainingMode.TRACKING).key)
        assertNotEquals(base.key, base.copy(weaponName = "SMG").key)
        assertNotEquals(base.key, base.copy(metric = RecordMetric.ACCURACY).key)
        // The generation participates too: the same record from the 2D engine is a distinct key.
        assertNotEquals(base.key, base.copy(scoringVersion = SessionSummary.SCORING_VERSION_2D).key)
    }

    @Test
    fun `a record with no weapon still produces a stable key`() {
        val noWeapon = PersonalRecord(
            mode = TrainingMode.REACTION,
            difficulty = Difficulty.NORMAL,
            weaponName = null,
            metric = RecordMetric.FASTEST_REACTION,
            value = 210f,
            previousValue = 240f,
            achievedAtMillis = 1L,
        )
        assertEquals("REACTION|NORMAL||FASTEST_REACTION|v${SessionSummary.SCORING_VERSION_3D}", noWeapon.key)
    }

    @Test
    fun `reaction time is the one metric where lower is better`() {
        assertFalse(RecordMetric.FASTEST_REACTION.higherIsBetter)
        assertTrue(RecordMetric.SCORE.higherIsBetter)
    }
}
