package com.gamecore.aimlab.runtime

import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.DifficultyParameters
import com.gamecore.aimlab.engine.FakeClock
import com.gamecore.aimlab.engine.SeededRng
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Vec2
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's driver, stepped through whole runs against a clock the test owns.
 *
 * Nothing here sleeps and nothing here is approximate. [AimTrainingLoop] takes its [FakeClock] and its
 * seeded RNG through the primary constructor, and its ticker drives itself with `delay`, so a run can be
 * advanced one 16 ms tick at a time under `runTest`'s virtual time with the fake clock moved in the same
 * step. Every number below is therefore the exact one the loop must produce — a spawn lands on the tick the
 * difficulty's interval reaches, not "about a second later".
 *
 * What is pinned: targets appear only on the difficulty's schedule and die on their lifetime; a shot is
 * counted whether or not it lands and only counts a hit when it does; a timed run ends itself and the
 * ticker stops; a pause takes no run time and a resume continues the same run rather than starting a new
 * one; a seed replays a run exactly; and `stop()` refuses to produce a summary for a run in which the
 * player did nothing, so nothing is ever stored that was not measured.
 */
class AimTrainingLoopTest {

    private val clock = FakeClock(wallMillis = WALL_START_MILLIS, monotonicNanos = MONOTONIC_START_NANOS)
    private val loop = AimTrainingLoop(clock, ::SeededRng)
    private val frames = mutableListOf<TrainingFrame>()
    private val normal = DifficultyParameters.forLevel(Difficulty.NORMAL)

    @Test
    fun `a flick run spawns its first target only once the spawn interval has passed`() = runTest {
        loop.start(config())
        collectFrames()

        tick(ticksToReach(normal.spawnIntervalMillis) - 1)
        assertTrue("a target appeared before the spawn interval", frames.all { it.targets.isEmpty() })
        assertTrue(frames.last().elapsedMillis < normal.spawnIntervalMillis)

        tick(1)
        assertEquals(1, frames.last().targets.size)
        assertTrue(frames.last().elapsedMillis >= normal.spawnIntervalMillis)
    }

    @Test
    fun `a shot on a target counts a hit and a shot that misses counts only a shot`() = runTest {
        loop.start(config())
        collectFrames()
        tick(ticksToReach(normal.spawnIntervalMillis))
        val target = frames.last().targets.single()

        loop.onShot(target.center.x, target.center.y)
        tick(1)
        assertEquals(1, frames.last().hits)
        assertEquals(1, frames.last().shots)
        assertTrue("a hit target stays on screen", frames.last().targets.isEmpty())

        // Nothing is alive now and the next spawn is an interval away, so this one cannot land.
        loop.onShot(0.2f, 0.8f)
        tick(1)
        assertEquals(1, frames.last().hits)
        assertEquals(2, frames.last().shots)
    }

    @Test
    fun `a target left alone past its lifetime counts as a missed target`() = runTest {
        val easy = DifficultyParameters.forLevel(Difficulty.EASY)
        loop.start(config(difficulty = Difficulty.EASY))
        collectFrames()

        // One shot into the empty arena, so the run has something in it and stop() will report on it.
        loop.onShot(0.5f, 0.5f)

        tick(ticksToReach(easy.spawnIntervalMillis))
        val spawned = frames.last().targets.single()

        tick(ticksToPass(easy.targetLifetimeMillis))
        assertTrue("the expired target is still alive", frames.last().targets.none { it.id == spawned.id })
        // The expiry frees the one simultaneous slot, and the interval is long past, so a fresh target
        // takes its place in the same tick: the run continues, it is only that one target that was lost.
        assertEquals(1, frames.last().targets.size)

        // `!!` is the assertion: a run with a shot in it is worth storing.
        val summary = loop.stop()!!
        assertEquals(1, summary.targetsMissed)
        assertEquals(1, summary.shots)
        assertEquals(0, summary.hits)
    }

    @Test
    fun `a timed run finishes itself when its duration elapses and then stops ticking`() = runTest {
        loop.start(config(durationSeconds = 1))
        collectFrames()

        tick(ticksToReach(ONE_SECOND_MILLIS) - 1)
        assertTrue("the run ended early", frames.none { it.finished })

        tick(1)
        assertTrue(frames.last().finished)
        assertFalse(frames.last().running)
        assertTrue(frames.last().elapsedMillis >= ONE_SECOND_MILLIS)

        // The ticker breaks out of its loop once the run is finished; no later frame is ever produced.
        val emitted = frames.size
        tick(100)
        assertEquals(emitted, frames.size)
    }

    @Test
    fun `pause freezes elapsed time and resume continues the same run`() = runTest {
        loop.start(config())
        collectFrames()
        loop.onShot(0.5f, 0.5f) // a miss into the empty arena, so the run has state to carry across.
        tick(10)

        val atPause = frames.last().elapsedMillis
        assertEquals(10 * TICK_MILLIS, atPause)

        loop.pause()
        val beforePause = frames.size
        tick(50)
        // A paused ticker no longer spins at 60 Hz: it emits one frame when it goes idle and then holds,
        // rather than pushing fifty identical frames through fifty recompositions. So this asserts the
        // invariants over every frame emitted *since* the pause, however few, instead of assuming a fixed
        // count: the pause emitted at least one frame, and every frame since froze the clock and reported
        // the run stopped.
        val paused = frames.drop(beforePause)
        assertTrue("the pause emitted no frame", paused.isNotEmpty())
        assertTrue("run time advanced while paused", paused.all { it.elapsedMillis == atPause })
        assertTrue("the run reported itself running while paused", paused.none { it.running })

        // Input is ignored while paused, so a pause cannot be used to bank free shots.
        loop.onShot(0.5f, 0.5f)
        tick(1)
        assertEquals(1, frames.last().shots)

        loop.resume()
        tick(10)
        val resumed = frames.last()
        assertTrue(resumed.running)
        assertEquals("paused wall time leaked into the run", atPause + 10 * TICK_MILLIS, resumed.elapsedMillis)
        assertEquals("resume started a new run", 1, resumed.shots)
    }

    @Test
    fun `the same seed replays the identical target sequence`() = runTest {
        val first = targetSequence(seed = 4_242L)
        val second = targetSequence(seed = 4_242L)
        val other = targetSequence(seed = 99L)

        assertTrue("too few spawns for the comparison to mean anything", first.size >= 4)
        assertEquals(first, second)
        assertNotEquals(first, other)
    }

    @Test
    fun `stop returns nothing for a run in which the player did nothing`() = runTest {
        loop.start(config())
        collectFrames()
        tick(ticksToReach(normal.spawnIntervalMillis))

        assertTrue(frames.last().targets.isNotEmpty())
        // A target having appeared is not the player having done anything: there is nothing to store.
        assertNull(loop.stop())
    }

    @Test
    fun `stop returns a summary measured from the run that happened`() = runTest {
        loop.start(config())
        collectFrames()
        tick(ticksToReach(normal.spawnIntervalMillis))
        val target = frames.last().targets.single()

        // Hit it exactly one tick after it appeared, so its acquisition time is a known 16 ms.
        tick(1)
        loop.onShot(target.center.x, target.center.y)

        val summary = loop.stop()!!
        assertEquals(TrainingMode.FLICK, summary.mode)
        assertEquals(Difficulty.NORMAL, summary.difficulty)
        assertEquals(1, summary.hits)
        assertEquals(1, summary.shots)
        assertEquals(0, summary.targetsMissed)
        assertEquals(TICK_MILLIS.toFloat(), summary.averageAcquireMillis, TOLERANCE)
        assertEquals(WALL_START_MILLIS, summary.startedAtMillis)
        assertEquals(frames.last().elapsedMillis, summary.durationMillis)
        assertTrue(summary.score > 0)
    }

    // ------------------------------------------------------------------------------ driving a run

    /** Collects [AimTrainingLoop.frames] in the background; the ticker's first tick runs before returning. */
    private fun TestScope.collectFrames(
        target: AimTrainingLoop = loop,
        into: MutableList<TrainingFrame> = frames,
    ): Job {
        val job = backgroundScope.launch { target.frames.collect { into += it } }
        runCurrent()
        return job
    }

    /** Runs [ticks] ticker iterations, moving [source] forward in the same steps the ticker delays for. */
    private fun TestScope.tick(ticks: Int, source: FakeClock = clock) {
        repeat(ticks) {
            source.advanceMillis(TICK_MILLIS)
            advanceTimeBy(TICK_MILLIS)
            runCurrent()
        }
    }

    /** Runs a fixed slice of an EXTREME flick run and returns the centre of every target it spawned. */
    private fun TestScope.targetSequence(seed: Long): List<Vec2> {
        val runClock = FakeClock(wallMillis = WALL_START_MILLIS, monotonicNanos = MONOTONIC_START_NANOS)
        val runLoop = AimTrainingLoop(runClock, ::SeededRng)
        val collected = mutableListOf<TrainingFrame>()
        runLoop.start(config(difficulty = Difficulty.EXTREME, seed = seed))
        val job = collectFrames(runLoop, collected)
        tick(SEQUENCE_TICKS, runClock)
        job.cancel()
        runCurrent()
        return collected.flatMap { it.targets }.distinctBy { it.id }.map { it.center }
    }

    private fun config(
        difficulty: Difficulty = Difficulty.NORMAL,
        durationSeconds: Int = 60,
        seed: Long = 7L,
    ) = TrainingConfig(
        mode = TrainingMode.FLICK,
        difficulty = difficulty,
        durationSeconds = durationSeconds,
        seed = seed,
    )

    private companion object {
        /** The loop's own tick period. The fake clock is stepped by the same amount per tick. */
        const val TICK_MILLIS = 16L

        const val ONE_SECOND_MILLIS = 1_000L
        const val TOLERANCE = 0.001f

        /** 2 s of an EXTREME run: four spawns at its 480 ms interval, enough to compare two seeds on. */
        const val SEQUENCE_TICKS = 125

        /** Both halves start non-zero: a summary whose start time is 0 is rejected as invalid. */
        const val WALL_START_MILLIS = 1_700_000_000_000L
        const val MONOTONIC_START_NANOS = 9_000_000_000L

        /** The first tick landing at or after [millis] of run time — where a `>=` deadline fires. */
        fun ticksToReach(millis: Long): Int = ((millis + TICK_MILLIS - 1) / TICK_MILLIS).toInt()

        /** The first tick landing strictly after [millis] — where a `>` deadline, like a lifetime, fires. */
        fun ticksToPass(millis: Long): Int = (millis / TICK_MILLIS).toInt() + 1
    }
}
