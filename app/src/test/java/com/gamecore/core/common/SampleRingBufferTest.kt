package com.gamecore.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRingBufferTest {

    @Test
    fun capacityZeroThrows() {
        assertThrows(IllegalArgumentException::class.java) { SampleRingBuffer(0) }
    }

    @Test
    fun capacityNegativeThrows() {
        assertThrows(IllegalArgumentException::class.java) { SampleRingBuffer(-3) }
    }

    @Test
    fun newBufferIsEmpty() {
        val buffer = SampleRingBuffer(4)
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty())
        assertFalse(buffer.isFull())
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun addBelowCapacityGrowsSizeAndKeepsInsertionOrder() {
        val buffer = SampleRingBuffer(4)
        buffer.add(1f)
        buffer.add(2f)
        buffer.add(3f)
        assertEquals(3, buffer.size)
        assertFalse(buffer.isFull())
        assertEquals(listOf(1f, 2f, 3f), buffer.snapshot())
    }

    @Test
    fun addBeyondCapacityEvictsOldestAndCapsSize() {
        val buffer = SampleRingBuffer(3)
        buffer.add(1f)
        buffer.add(2f)
        buffer.add(3f)
        buffer.add(4f)
        buffer.add(5f)
        assertEquals(3, buffer.size)
        assertTrue(buffer.isFull())
        // The last `capacity` values, oldest→newest.
        assertEquals(listOf(3f, 4f, 5f), buffer.snapshot())
    }

    @Test
    fun clearEmptiesBuffer() {
        val buffer = SampleRingBuffer(3)
        buffer.add(1f)
        buffer.add(2f)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertTrue(buffer.isEmpty())
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun reusableAfterClear() {
        val buffer = SampleRingBuffer(3)
        buffer.add(1f)
        buffer.add(2f)
        buffer.add(3f)
        buffer.clear()
        buffer.add(9f)
        buffer.add(8f)
        assertEquals(2, buffer.size)
        assertEquals(listOf(9f, 8f), buffer.snapshot())
    }

    @Test
    fun isFullTransitionsCorrectly() {
        val buffer = SampleRingBuffer(2)
        assertFalse(buffer.isFull())
        buffer.add(1f)
        assertFalse(buffer.isFull())
        buffer.add(2f)
        assertTrue(buffer.isFull())
        // Overwriting keeps it full, not over-full.
        buffer.add(3f)
        assertTrue(buffer.isFull())
        assertEquals(2, buffer.size)
    }

    @Test
    fun latestReturnsNewestOrNull() {
        val buffer = SampleRingBuffer(3)
        assertNull(buffer.latest())
        buffer.add(1f)
        assertEquals(1f, buffer.latest())
        buffer.add(2f)
        assertEquals(2f, buffer.latest())
        // After eviction the newest is still the last added.
        buffer.add(3f)
        buffer.add(4f)
        assertEquals(4f, buffer.latest())
    }

    @Test
    fun snapshotIsACopyNotALiveView() {
        val buffer = SampleRingBuffer(3)
        buffer.add(1f)
        buffer.add(2f)
        val first = buffer.snapshot()
        // A later add must not change an already-taken snapshot.
        buffer.add(3f)
        val second = buffer.snapshot()
        assertEquals(listOf(1f, 2f), first)
        assertEquals(listOf(1f, 2f, 3f), second)
    }
}
