package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adaptive plan's decision table, one test per branch.
 *
 * The point of these tests is honesty: each recommendation must be earned by a real session field, the
 * detail must quote that field, and a mode with no sessions must never win on a zero default. The cold
 * start is the single deliberate exception — it recommends flick before any flick history exists because
 * three runs is what the plan needs to start measuring.
 */
class TrainingPlanTest {

    private fun session(
        mode: TrainingMode,
        startedAtMillis: Long = 1_000L,
        difficulty: Difficulty = Difficulty.NORMAL,
        score: Int = 0,
        hits: Int = 0,
        shots: Int = 0,
        reactionStats: ReactionStats = ReactionStats.EMPTY,
        timeOnTargetFraction: Float = 0f,
        gyroStability: Float = 0f,
    ) = SessionSummary(
        mode = mode,
        difficulty = difficulty,
        startedAtMillis = startedAtMillis,
        endedAtMillis = startedAtMillis + 60_000L,
        score = score,
        hits = hits,
        shots = shots,
        reactionStats = reactionStats,
        timeOnTargetFraction = timeOnTargetFraction,
        gyroStability = gyroStability,
    )

    @Test
    fun `cold start recommends flick at normal with the exact fundamentals wording`() {
        // Two runs is still cold: the plan needs three before it can call any skill weak.
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.TRACKING, startedAtMillis = 2_000L, timeOnTargetFraction = 0.2f),
                session(TrainingMode.FLICK, startedAtMillis = 1_000L, score = 100),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.FLICK, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertEquals("Start with the fundamentals", plan.reason)
        assertEquals("Three flick runs will give the plan enough data to work with.", plan.detail)
    }

    @Test
    fun `an empty history still gets the cold-start plan rather than an invented weakness`() {
        val plan = TrainingPlan.recommend(sessions = emptyList(), records = emptyList())

        assertNotNull(plan)
        assertEquals(TrainingMode.FLICK, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertEquals("Start with the fundamentals", plan.reason)
    }

    @Test
    fun `weak tracking quotes the measured percentage and the session count`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.TRACKING, startedAtMillis = 3_000L, timeOnTargetFraction = 0.50f),
                session(TrainingMode.TRACKING, startedAtMillis = 2_000L, timeOnTargetFraction = 0.58f),
                session(TrainingMode.FLICK, startedAtMillis = 1_000L, score = 900),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.TRACKING, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("54%"))
        assertTrue(plan.detail, plan.detail.contains("2 tracking sessions"))
    }

    @Test
    fun `slow reactions quote the fastest and median milliseconds`() {
        val reaction = ReactionStats.from(listOf(380L, 420L, 450L))
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.REACTION, startedAtMillis = 3_000L, reactionStats = reaction),
                session(TrainingMode.REACTION, startedAtMillis = 2_000L, reactionStats = reaction),
                session(TrainingMode.REACTION, startedAtMillis = 1_000L, reactionStats = reaction),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.REACTION, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("380 ms"))
        assertTrue(plan.detail, plan.detail.contains("420 ms"))
    }

    @Test
    fun `a reaction record above the ceiling is evidence when the session carried no samples`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.REACTION, startedAtMillis = 3_000L),
                session(TrainingMode.REACTION, startedAtMillis = 2_000L),
                session(TrainingMode.REACTION, startedAtMillis = 1_000L),
            ),
            records = listOf(
                PersonalRecord(
                    mode = TrainingMode.REACTION,
                    difficulty = Difficulty.NORMAL,
                    weaponName = null,
                    metric = RecordMetric.FASTEST_REACTION,
                    value = 410f,
                    previousValue = null,
                    achievedAtMillis = 1_000L,
                ),
            ),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.REACTION, plan!!.mode)
        assertTrue(plan.detail, plan.detail.contains("410 ms"))
    }

    @Test
    fun `a flick score more than ten percent below the recent average is called out`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.FLICK, startedAtMillis = 3_000L, score = 700, hits = 7, shots = 10),
                session(TrainingMode.FLICK, startedAtMillis = 2_000L, score = 1_000, hits = 9, shots = 10),
                session(TrainingMode.FLICK, startedAtMillis = 1_000L, score = 1_000, hits = 9, shots = 10),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.FLICK, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("700 points"))
        assertTrue(plan.detail, plan.detail.contains("30%"))
        assertTrue(plan.detail, plan.detail.contains("1000 points"))
    }

    @Test
    fun `weak gyro stability recommends gyro at the measured percentage`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.GYRO, startedAtMillis = 3_000L, gyroStability = 0.50f),
                session(TrainingMode.GYRO, startedAtMillis = 2_000L, gyroStability = 0.50f),
                session(TrainingMode.GYRO, startedAtMillis = 1_000L, gyroStability = 0.50f),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.GYRO, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("50%"))
        assertTrue(plan.detail, plan.detail.contains("3 gyro sessions"))
    }

    @Test
    fun `weak tilt stability recommends tilt and never calls it gyro`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.TILT, startedAtMillis = 3_000L, gyroStability = 0.50f),
                session(TrainingMode.TILT, startedAtMillis = 2_000L, gyroStability = 0.55f),
                session(TrainingMode.TILT, startedAtMillis = 1_000L, gyroStability = 0.60f),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.TILT, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("55%"))
        assertTrue(plan.detail, plan.detail.contains("tilt sessions"))
        assertFalse(plan.reason, plan.reason.contains("gyro", ignoreCase = true))
        assertFalse(plan.detail, plan.detail.contains("gyro", ignoreCase = true))
    }

    @Test
    fun `tilt is labelled Tilt and never Gyro`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.TILT, startedAtMillis = 3_000L, gyroStability = 0.40f),
                session(TrainingMode.TILT, startedAtMillis = 2_000L, gyroStability = 0.40f),
                session(TrainingMode.TILT, startedAtMillis = 1_000L, gyroStability = 0.40f),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals("Tilt", plan!!.modeLabel)
        assertFalse(plan.modeLabel.contains("gyro", ignoreCase = true))
    }

    @Test
    fun `three strong runs step free practice up one difficulty`() {
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(
                    TrainingMode.FLICK,
                    startedAtMillis = 3_000L,
                    difficulty = Difficulty.NORMAL,
                    score = 1_000,
                    hits = 9,
                    shots = 10,
                ),
                session(
                    TrainingMode.FLICK,
                    startedAtMillis = 2_000L,
                    difficulty = Difficulty.NORMAL,
                    score = 1_000,
                    hits = 9,
                    shots = 10,
                ),
                session(
                    TrainingMode.FLICK,
                    startedAtMillis = 1_000L,
                    difficulty = Difficulty.NORMAL,
                    score = 1_000,
                    hits = 9,
                    shots = 10,
                ),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.FREE_PRACTICE, plan!!.mode)
        assertEquals(Difficulty.HARD, plan.difficulty)
        assertTrue(plan.detail, plan.detail.contains("80%"))
    }

    @Test
    fun `the plan does not treat a non-mode's zero defaults as evidence`() {
        // None of these runs measured tracking, reaction, gyro or tilt, so those zero fields must not win.
        // The maintenance flick run is the honest fallback at the difficulty the user is already running.
        val plan = TrainingPlan.recommend(
            sessions = listOf(
                session(TrainingMode.FLICK, startedAtMillis = 3_000L, score = 1_000, hits = 7, shots = 10),
                session(TrainingMode.FLICK, startedAtMillis = 2_000L, score = 1_000, hits = 7, shots = 10),
                session(TrainingMode.FLICK, startedAtMillis = 1_000L, score = 1_000, hits = 7, shots = 10),
            ),
            records = emptyList(),
        )

        assertNotNull(plan)
        assertEquals(TrainingMode.FLICK, plan!!.mode)
        assertEquals(Difficulty.NORMAL, plan.difficulty)
    }
}
