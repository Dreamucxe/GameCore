package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [replayChip]. Each branch is exercised in isolation, and the precedence is pinned by stacking every
 * lower-priority condition under a higher one and asserting the higher wins.
 */
class ReplayChipTest {

    private val available = StorageVerdict.Available
    private val unavailable = StorageVerdict.Unavailable(neededBytes = 100L, freeBytes = 0L)

    // --- each branch in isolation -------------------------------------------------------------------

    @Test
    fun `all healthy is buffering`() {
        assertEquals(
            ReplayChip.Buffering,
            replayChip(enabled = true, hasPermission = true, storage = available, blocked = false, thermalPaused = false),
        )
    }

    @Test
    fun `disabled is off`() {
        assertEquals(
            ReplayChip.Off,
            replayChip(enabled = false, hasPermission = true, storage = available, blocked = false, thermalPaused = false),
        )
    }

    @Test
    fun `missing permission needs permission`() {
        assertEquals(
            ReplayChip.NeedsPermission,
            replayChip(enabled = true, hasPermission = false, storage = available, blocked = false, thermalPaused = false),
        )
    }

    @Test
    fun `storage unavailable reports storage`() {
        assertEquals(
            ReplayChip.UnavailableStorage,
            replayChip(enabled = true, hasPermission = true, storage = unavailable, blocked = false, thermalPaused = false),
        )
    }

    @Test
    fun `blocked capture reports blocked`() {
        assertEquals(
            ReplayChip.UnavailableBlocked,
            replayChip(enabled = true, hasPermission = true, storage = available, blocked = true, thermalPaused = false),
        )
    }

    @Test
    fun `thermal pause reports overheating`() {
        assertEquals(
            ReplayChip.PausedOverheating,
            replayChip(enabled = true, hasPermission = true, storage = available, blocked = false, thermalPaused = true),
        )
    }

    // --- precedence ---------------------------------------------------------------------------------

    @Test
    fun `off outranks every other condition`() {
        assertEquals(
            ReplayChip.Off,
            replayChip(enabled = false, hasPermission = false, storage = unavailable, blocked = true, thermalPaused = true),
        )
    }

    @Test
    fun `needs-permission outranks storage, blocked and thermal`() {
        assertEquals(
            ReplayChip.NeedsPermission,
            replayChip(enabled = true, hasPermission = false, storage = unavailable, blocked = true, thermalPaused = true),
        )
    }

    @Test
    fun `storage outranks blocked and thermal`() {
        assertEquals(
            ReplayChip.UnavailableStorage,
            replayChip(enabled = true, hasPermission = true, storage = unavailable, blocked = true, thermalPaused = true),
        )
    }

    @Test
    fun `blocked outranks thermal`() {
        assertEquals(
            ReplayChip.UnavailableBlocked,
            replayChip(enabled = true, hasPermission = true, storage = available, blocked = true, thermalPaused = true),
        )
    }
}
