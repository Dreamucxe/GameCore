package com.gamecore.aimlab.runtime

import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.FakeClock
import com.gamecore.aimlab.engine.SeededRng
import com.gamecore.aimlab.engine.TrainingMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-person loop's shot bookkeeping, driven through whole runs against a clock the test owns.
 *
 * The bug that motivates this file has a name and a number: a Reaction run showed 17 "misses" with 0
 * attempts while the target had not appeared (§5). The fix lives in two places the two other tests pin —
 * the pure [com.gamecore.aimlab.engine.TouchClassifier] (a drag is never a tap) and the surface that only
 * calls `onShot` for a classified tap. What is pinned *here* is the loop-side half of the same guarantee,
 * one level below the touch surface: the counters that feed the HUD's "misses = shots − hits" move for
 * exactly one reason and no other.
 *
 *  - **Looking never scores.** [onLookPixels], [onAimDelta] and [onGyro] rotate the camera and touch no
 *    counter, in every mode. So however many times a finger drags across the screen while a reaction target
 *    is still hidden, `shots` stays 0 and the HUD's `shots − hits` stays 0 — the "17 misses while WAITING"
 *    class of bug cannot be produced by looking, by construction.
 *  - **A shot is the only thing that moves `shots`,** and only while the run is live. A deliberate tap
 *    during the reaction wait is a false start (a shot, no hit) — which is correct and intended, and is a
 *    different event from a look-drag. A tap on a shown target is a hit.
 *  - **Input after the run has finished is ignored,** so a late frame or a stray touch cannot pad a total.
 *
 * Every number is exact: [AimTrainingLoop3D] takes its [FakeClock] and seeded RNG through its internal
 * constructor and drives itself with `delay`, so a run advances one 16 ms tick at a time under `runTest`'s
 * virtual time with the fake clock stepped in lockstep — the same harness the 2D [AimTrainingLoopTest] uses.
 */
class AimTrainingLoop3DTest {

    private val clock = FakeClock(wallMillis = WALL_START_MILLIS, monotonicNanos = MONOTONIC_START_NANOS)
    private val loop = AimTrainingLoop3D(clock, ::SeededRng)
    private val frames = mutableListOf<TrainingFrame>()

    @Test
    fun `look-dragging during the reaction wait never scores a shot or a miss`() = runTest {
        loop.start(config(mode = TrainingMode.REACTION))
        collectFrames()

        // The reaction wait is 700..2500 ms; a handful of ticks in, no target has appeared yet. Spam look
        // input the way a player wandering the view while waiting would — many drags, in both axes, ADS and
        // not. This is exactly the gesture that once registered as "misses while WAITING".
        repeat(30) { i ->
            loop.onLookPixels(dxPx = 40f, dyPx = -25f, surfaceWidthPx = 1080, aiming = i % 2 == 0)
            tick(1)
        }

        val f = frames.last()
        assertEquals("a look-drag must never count as a shot", 0, f.shots)
        assertEquals("nothing was hit", 0, f.hits)
        // The HUD derives misses as shots - hits; the whole point of the fix is that this stays 0.
        assertEquals("misses (shots - hits) must be 0 while merely looking", 0, f.shots - f.hits)
        assertTrue("the run is still going", f.running && !f.finished)
    }

    @Test
    fun `gyro and aim-delta input also never move the counters`() = runTest {
        loop.start(config(mode = TrainingMode.GYRO))
        collectFrames()

        repeat(20) {
            loop.onGyro(yawRadians = 0.05f, pitchRadians = -0.03f)
            loop.onAimDelta(dx = 0.1f, dy = 0.1f, aiming = false)
            tick(1)
        }

        val f = frames.last()
        assertEquals(0, f.shots)
        assertEquals(0, f.hits)
    }

    @Test
    fun `a deliberate tap during the reaction wait is a false start — a shot, never a hit`() = runTest {
        loop.start(config(mode = TrainingMode.REACTION))
        collectFrames()
        // 2 ticks = 32 ms elapsed, far inside the 700 ms minimum reaction wait, so tickReaction() cannot
        // have spawned a target yet — the shot below is unambiguously a false start. (The 3D frame carries
        // no target list; the world lives in renderState, so this rests on the wait timing, not on the frame.)
        tick(2)

        loop.onShot(0.5f, 0.5f) // the crosshair is centred; x/y are ignored in 3D
        tick(1)

        val f = frames.last()
        assertEquals("a tap with no target shown is one false-start shot", 1, f.shots)
        assertEquals("a false start hits nothing", 0, f.hits)
        assertEquals("the HUD shows exactly one miss for the false start", 1, f.shots - f.hits)
    }

    @Test
    fun `a tap once the reaction target is shown counts as a shot and re-arms the wait`() = runTest {
        loop.start(config(mode = TrainingMode.REACTION))
        collectFrames()

        // Past the maximum wait, the target is on screen. Whether the centred ray intersects the off-centre
        // spawn is a ray/sphere-geometry question pinned by Geometry3DTest/CameraProjectionTest — not this
        // test's business. What this pins is the bookkeeping: a tap while a target is shown is a real shot
        // (not a look), and it re-arms the reaction wait rather than leaving a stale target on screen.
        tick(ticksToPass(REACTION_MAX_WAIT_MS))

        val shotsBefore = frames.last().shots
        loop.onShot(0.5f, 0.5f)
        tick(1)

        val f = frames.last()
        assertEquals("the tap was counted as exactly one more shot", shotsBefore + 1, f.shots)
        assertTrue("hits can never exceed shots", f.hits <= f.shots)
    }

    @Test
    fun `input after the run has finished is ignored`() = runTest {
        loop.start(config(mode = TrainingMode.REACTION, durationSeconds = 1))
        collectFrames()

        tick(ticksToPass(ONE_SECOND_MILLIS))
        assertTrue("the timed run did not finish", frames.last().finished)
        val finalShots = frames.last().shots

        // A stray tap and a stray drag after the end must change nothing.
        loop.onShot(0.5f, 0.5f)
        loop.onLookPixels(50f, 50f, 1080, false)
        tick(2)

        assertEquals("a shot after finish padded the total", finalShots, frames.last().shots)
    }

    @Test
    fun `a flick tap on an empty arena is a shot with no hit`() = runTest {
        loop.start(config(mode = TrainingMode.FLICK))
        collectFrames()
        // First tick only; a flick target spawns on the difficulty's interval, so the arena is still empty.
        tick(1)
        assertTrue(frames.last().targets.isEmpty() || frames.last().hits == 0)

        loop.onShot(0.5f, 0.5f)
        tick(1)

        val f = frames.last()
        assertEquals("the tap is a shot", 1, f.shots)
        assertEquals("an empty-arena tap hits nothing", 0, f.hits)
    }

    // ------------------------------------------------------------------------------ driving a run

    private fun TestScope.collectFrames(): Job {
        val job = backgroundScope.launch { loop.frames.collect { frames += it } }
        runCurrent()
        return job
    }

    /** Runs [ticks] ticker iterations, moving the fake clock forward the same 16 ms the ticker delays for. */
    private fun TestScope.tick(ticks: Int) {
        repeat(ticks) {
            clock.advanceMillis(TICK_MILLIS)
            advanceTimeBy(TICK_MILLIS)
            runCurrent()
        }
    }

    private fun config(
        mode: TrainingMode,
        difficulty: Difficulty = Difficulty.NORMAL,
        durationSeconds: Int = 60,
        seed: Long = 7L,
    ) = TrainingConfig(
        mode = mode,
        difficulty = difficulty,
        durationSeconds = durationSeconds,
        seed = seed,
    )

    private companion object {
        const val TICK_MILLIS = 16L
        const val ONE_SECOND_MILLIS = 1_000L
        const val REACTION_MAX_WAIT_MS = 2_500L
        const val WALL_START_MILLIS = 1_700_000_000_000L
        const val MONOTONIC_START_NANOS = 9_000_000_000L

        /** The first tick landing strictly after [millis] of run time — where a `>` deadline fires. */
        fun ticksToPass(millis: Long): Int = (millis / TICK_MILLIS).toInt() + 1
    }
}
