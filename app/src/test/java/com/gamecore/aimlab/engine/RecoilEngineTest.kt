package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recoil the player is fighting, and the score for fighting it well.
 *
 * §B2.6 pins the reproducibility contract: a given seed must always produce the same pattern so a burst is
 * learnable and a test can assert against it, while a fresh seed with randomness must differ. The other
 * load-bearing promises are physical — the pattern only ever kicks upward, recovery only ever shrinks the
 * offset toward zero (never overshooting its sign), and a perfect counter scores 1 while doing nothing
 * scores ~0. Each is asserted as a property rather than a magic constant.
 */
class RecoilEngineTest {

    private val eps = 1e-4f

    @Test
    fun `a zero-shot burst has no pattern and an n-shot burst has n points`() {
        val engine = RecoilEngine(SeededRng(42))
        assertTrue(engine.pattern(RecoilSpec(), 0).isEmpty())
        assertEquals(8, RecoilEngine(SeededRng(42)).pattern(RecoilSpec(), 8).size)
    }

    @Test
    fun `the same seed reproduces the pattern and a different seed diverges`() {
        val spec = RecoilSpec(randomness = 0.5f)
        val a = RecoilEngine(SeededRng(42)).pattern(spec, 12)
        val b = RecoilEngine(SeededRng(42)).pattern(spec, 12)
        assertEquals(a, b)

        val c = RecoilEngine(SeededRng(7)).pattern(spec, 12)
        assertNotEquals(a, c)
    }

    @Test
    fun `with no randomness the pattern is seed-independent and always kicks upward`() {
        val spec = RecoilSpec(randomness = 0f)
        val fromOneSeed = RecoilEngine(SeededRng(42)).pattern(spec, 10)
        val fromAnother = RecoilEngine(SeededRng(7)).pattern(spec, 10)
        assertEquals(fromOneSeed, fromAnother)

        // Upward is negative Y, and offsets accumulate, so each point sits at or above the previous one.
        for (i in 1 until fromOneSeed.size) {
            assertTrue("y not monotonic at $i", fromOneSeed[i].y <= fromOneSeed[i - 1].y)
        }
    }

    @Test
    fun `recovery shrinks the offset toward zero and leaves it untouched when there is nothing to do`() {
        val engine = RecoilEngine(SeededRng(1))
        val offset = Vec2(0.3f, -0.4f)

        val recovered = engine.recover(offset, elapsedSeconds = 0.5f, recoveryPerSecond = 2.5f)
        assertTrue("magnitude did not shrink", recovered.length < offset.length)
        // Never overshoots the sign of either axis.
        assertTrue(recovered.x in 0f..offset.x)
        assertTrue(recovered.y in offset.y..0f)

        // No elapsed time or no rate is a no-op.
        assertEquals(offset, engine.recover(offset, elapsedSeconds = 0f, recoveryPerSecond = 2.5f))
        assertEquals(offset, engine.recover(offset, elapsedSeconds = 0.5f, recoveryPerSecond = 0f))
    }

    @Test
    fun `compensation is zero for empty or mismatched input`() {
        val engine = RecoilEngine(SeededRng(1))
        assertEquals(0f, engine.compensationScore(emptyList(), emptyList()), eps)
        val recoil = engine.pattern(RecoilSpec(randomness = 0f), 5)
        assertEquals(0f, engine.compensationScore(recoil, recoil.drop(1)), eps)
    }

    @Test
    fun `a perfect counter cancels the recoil for a near-perfect score`() {
        val engine = RecoilEngine(SeededRng(1))
        val recoil = engine.pattern(RecoilSpec(randomness = 0f), 8)
        val counter = recoil.map { Vec2(-it.x, -it.y) }
        assertEquals(1f, engine.compensationScore(recoil, counter), eps)
    }

    @Test
    fun `doing nothing scores near zero against a non-trivial recoil`() {
        val engine = RecoilEngine(SeededRng(1))
        val recoil = engine.pattern(RecoilSpec(randomness = 0f), 8)
        val nothing = recoil.map { Vec2(0f, 0f) }
        assertEquals(0f, engine.compensationScore(recoil, nothing), eps)
    }
}
