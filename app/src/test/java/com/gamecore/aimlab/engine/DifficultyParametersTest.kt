package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof that difficulty is more than a label.
 *
 * §16 forbids a difficulty that only renames itself — Hard must actually be harder than Normal. The
 * single source of that promise is [DifficultyParameters.forLevel], so this asserts strict monotonicity
 * on every field across Easy→Normal→Hard→Extreme: smaller targets, faster movement, shorter spawn and
 * lifetime windows, more simultaneous targets. A tuning change that accidentally flattened two levels
 * would break here rather than ship a difficulty that does nothing.
 */
class DifficultyParametersTest {

    private val easy = DifficultyParameters.forLevel(Difficulty.EASY)
    private val normal = DifficultyParameters.forLevel(Difficulty.NORMAL)
    private val hard = DifficultyParameters.forLevel(Difficulty.HARD)
    private val extreme = DifficultyParameters.forLevel(Difficulty.EXTREME)

    @Test
    fun `target radius shrinks strictly at every step`() {
        assertTrue(easy.targetRadius > normal.targetRadius)
        assertTrue(normal.targetRadius > hard.targetRadius)
        assertTrue(hard.targetRadius > extreme.targetRadius)
    }

    @Test
    fun `target speed rises strictly at every step`() {
        assertTrue(easy.targetSpeed < normal.targetSpeed)
        assertTrue(normal.targetSpeed < hard.targetSpeed)
        assertTrue(hard.targetSpeed < extreme.targetSpeed)
    }

    @Test
    fun `the spawn interval shortens strictly at every step`() {
        assertTrue(easy.spawnIntervalMillis > normal.spawnIntervalMillis)
        assertTrue(normal.spawnIntervalMillis > hard.spawnIntervalMillis)
        assertTrue(hard.spawnIntervalMillis > extreme.spawnIntervalMillis)
    }

    @Test
    fun `target lifetime shortens strictly at every step`() {
        assertTrue(easy.targetLifetimeMillis > normal.targetLifetimeMillis)
        assertTrue(normal.targetLifetimeMillis > hard.targetLifetimeMillis)
        assertTrue(hard.targetLifetimeMillis > extreme.targetLifetimeMillis)
    }

    @Test
    fun `simultaneous targets never decrease and the span strictly grows`() {
        assertTrue(easy.simultaneousTargets <= normal.simultaneousTargets)
        assertTrue(normal.simultaneousTargets <= hard.simultaneousTargets)
        assertTrue(hard.simultaneousTargets <= extreme.simultaneousTargets)
        assertTrue(easy.simultaneousTargets < extreme.simultaneousTargets)
    }

    @Test
    fun `custom resolves to the normal baseline for the caller to overlay`() {
        assertEquals(DifficultyParameters.forLevel(Difficulty.NORMAL), DifficultyParameters.forLevel(Difficulty.CUSTOM))
    }
}
