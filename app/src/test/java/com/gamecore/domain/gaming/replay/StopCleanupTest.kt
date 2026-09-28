package com.gamecore.domain.gaming.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [StopCleanup]. The encoder is always stopped, the buffer always discarded and the notification always
 * removed; only whether the shared MediaProjection token is released varies by trigger.
 */
class StopCleanupTest {

    @Test
    fun `every trigger stops the encoder, discards the buffer, and removes the notification`() {
        for (trigger in StopTrigger.values()) {
            val plan = StopCleanup.plan(trigger)
            assertTrue("$trigger should stop the encoder", plan.stopEncoder)
            assertTrue("$trigger should discard the buffer", plan.discardBuffer)
            assertTrue("$trigger should remove the notification", plan.removeNotification)
        }
    }

    @Test
    fun `session end releases the projection`() {
        assertEquals(
            CleanupPlan(stopEncoder = true, discardBuffer = true, removeNotification = true, releaseProjection = true),
            StopCleanup.plan(StopTrigger.SessionEnd),
        )
    }

    @Test
    fun `toggle off releases the projection`() {
        assertTrue(StopCleanup.plan(StopTrigger.ToggleOff).releaseProjection)
    }

    @Test
    fun `service stop releases the projection`() {
        assertTrue(StopCleanup.plan(StopTrigger.ServiceStop).releaseProjection)
    }

    @Test
    fun `storage exhausted keeps the projection for the rest of the session`() {
        assertFalse(StopCleanup.plan(StopTrigger.StorageExhausted).releaseProjection)
    }

    @Test
    fun `blocked capture keeps the projection for the rest of the session`() {
        assertFalse(StopCleanup.plan(StopTrigger.BlockedCapture).releaseProjection)
    }

    @Test
    fun `exactly the session-ending triggers release the projection`() {
        val releasing = StopTrigger.values().filter { StopCleanup.plan(it).releaseProjection }.toSet()
        assertEquals(
            setOf(StopTrigger.SessionEnd, StopTrigger.ToggleOff, StopTrigger.ServiceStop),
            releasing,
        )
    }
}
