package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [StorageBudget]. Needed = buffer (window×bps) + one worst-case clip of the same window + headroom,
 * i.e. `2 × window × bps + headroom`. Tested at each preset window on BALANCED (750_000 B/s), with free
 * space just below and at the threshold.
 */
class StorageBudgetTest {

    // BALANCED video-only H264 ≈ 6 Mbps ≈ 750_000 bytes/s (audit Q5).
    private val bps = 750_000L
    private val headroom = StorageBudget.DEFAULT_HEADROOM_BYTES // 250_000_000

    @Test
    fun `needed is two window-buffers plus headroom for each preset`() {
        assertEquals(272_500_000L, StorageBudget.neededBytes(15, bps))
        assertEquals(295_000_000L, StorageBudget.neededBytes(30, bps))
        assertEquals(340_000_000L, StorageBudget.neededBytes(60, bps))
        assertEquals(430_000_000L, StorageBudget.neededBytes(120, bps))
    }

    @Test
    fun `free space exactly at the threshold is available`() {
        for (w in intArrayOf(15, 30, 60, 120)) {
            val needed = StorageBudget.neededBytes(w, bps)
            assertEquals(
                "window ${w}s at exactly needed should be Available",
                StorageVerdict.Available,
                StorageBudget.evaluate(w, bps, freeBytes = needed),
            )
        }
    }

    @Test
    fun `free space one byte above the threshold is available`() {
        for (w in intArrayOf(15, 30, 60, 120)) {
            val needed = StorageBudget.neededBytes(w, bps)
            assertEquals(
                StorageVerdict.Available,
                StorageBudget.evaluate(w, bps, freeBytes = needed + 1),
            )
        }
    }

    @Test
    fun `free space one byte below the threshold is unavailable with the real figures`() {
        for (w in intArrayOf(15, 30, 60, 120)) {
            val needed = StorageBudget.neededBytes(w, bps)
            val verdict = StorageBudget.evaluate(w, bps, freeBytes = needed - 1)
            assertEquals(
                StorageVerdict.Unavailable(neededBytes = needed, freeBytes = needed - 1),
                verdict,
            )
        }
    }

    @Test
    fun `unavailable carries the exact needed and free numbers for the 30s preset`() {
        val verdict = StorageBudget.evaluate(30, bps, freeBytes = 100_000_000L)
        assertEquals(
            StorageVerdict.Unavailable(neededBytes = 295_000_000L, freeBytes = 100_000_000L),
            verdict,
        )
    }

    @Test
    fun `zero free space is unavailable`() {
        assertEquals(
            StorageVerdict.Unavailable(neededBytes = 272_500_000L, freeBytes = 0L),
            StorageBudget.evaluate(15, bps, freeBytes = 0L),
        )
    }

    @Test
    fun `a custom headroom shifts the threshold by exactly that amount`() {
        val custom = 10_000_000L
        // 2 * 30 * 750_000 + 10_000_000 = 45_000_000 + 10_000_000 = 55_000_000
        assertEquals(55_000_000L, StorageBudget.neededBytes(30, bps, headroomBytes = custom))
        assertEquals(
            StorageVerdict.Available,
            StorageBudget.evaluate(30, bps, freeBytes = 55_000_000L, headroomBytes = custom),
        )
        assertEquals(
            StorageVerdict.Unavailable(neededBytes = 55_000_000L, freeBytes = 54_999_999L),
            StorageBudget.evaluate(30, bps, freeBytes = 54_999_999L, headroomBytes = custom),
        )
    }

    @Test
    fun `default headroom constant is the documented 250 MB`() {
        assertEquals(250_000_000L, headroom)
    }
}
