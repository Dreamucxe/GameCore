package com.gamecore.aimlab.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded buffer that keeps a long session from running the device out of memory.
 *
 * At 60–200 Hz an unbounded list would grow without limit, so this fixed ring is what stands between a
 * long run and an OOM. Two behaviours carry that weight: a full buffer must overwrite its oldest sample
 * (never grow), and every aggregate must be zero-safe on an empty buffer so a summary computed before any
 * sample arrived reads a defined zero rather than a NaN. The wrap-ordering test guards the one place an
 * off-by-one would silently reorder the samples a summary is built from.
 */
class FloatRingBufferTest {

    private val eps = 1e-4f

    @Test
    fun `a non-positive capacity is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { FloatRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { FloatRingBuffer(-1) }
    }

    @Test
    fun `adding beyond capacity overwrites the oldest while size caps and total keeps counting`() {
        val buffer = FloatRingBuffer(capacity = 3)
        for (v in listOf(1f, 2f, 3f, 4f, 5f)) buffer.add(v)
        assertEquals(3, buffer.size)
        assertEquals(5L, buffer.totalAdded)
        assertTrue(buffer.isFull)
        // Oldest-first after wrap: the two earliest (1,2) were overwritten by 4,5.
        assertEquals(3f, buffer.get(0), eps)
        assertEquals(4f, buffer.get(1), eps)
        assertEquals(5f, buffer.get(2), eps)
    }

    @Test
    fun `aggregates are defined zeros on an empty buffer`() {
        val buffer = FloatRingBuffer(capacity = 4)
        assertEquals(0f, buffer.mean(), eps)
        assertEquals(0f, buffer.min(), eps)
        assertEquals(0f, buffer.max(), eps)
        assertEquals(0f, buffer.standardDeviation(), eps)
    }

    @Test
    fun `aggregates over known values are exact`() {
        val buffer = FloatRingBuffer(capacity = 4)
        for (v in listOf(2f, 4f, 4f, 6f)) buffer.add(v)
        assertEquals(4f, buffer.mean(), eps)
        assertEquals(2f, buffer.min(), eps)
        assertEquals(6f, buffer.max(), eps)
        // Population sd of {2,4,4,6} = sqrt(mean of squared deviations) = sqrt(2).
        assertEquals(kotlin.math.sqrt(2f), buffer.standardDeviation(), eps)
    }

    @Test
    fun `clear resets both the retained size and the total counter`() {
        val buffer = FloatRingBuffer(capacity = 3)
        for (v in listOf(1f, 2f, 3f, 4f)) buffer.add(v)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0L, buffer.totalAdded)
        assertFalse(buffer.isFull)
        assertEquals(0f, buffer.mean(), eps)
    }
}
