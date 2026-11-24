package com.gamecore.core.wheel

import com.gamecore.core.model.WheelProfile
import com.gamecore.core.model.WheelResponseCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTouchProcessorTest {

    @Test
    fun `linear movement is amplified synchronously from the down anchor`() {
        val processor = WheelTouchProcessor()
        val profile = WheelProfile(sensitivity = 2f, deadzonePx = 0, radiusPx = 200)

        val down = processor.down(profile, x = 100f, y = 200f, eventTime = 10L)
        val move = processor.move(profile, x = 120f, y = 190f, eventTime = 11L)!!

        assertEquals(WheelAction.DOWN, down.action)
        assertEquals(140f, move.x, 0.001f)
        assertEquals(180f, move.y, 0.001f)
        assertEquals(40f, move.outputDx, 0.001f)
        assertEquals(-20f, move.outputDy, 0.001f)
        assertEquals(2L, processor.processedEvents)
    }

    @Test
    fun `down starts at the wheel centre while movement stays relative to the finger`() {
        val processor = WheelTouchProcessor()
        val profile = WheelProfile(sensitivity = 2f, deadzonePx = 0, radiusPx = 200)

        val down = processor.down(
            profile,
            x = 120f,
            y = 220f,
            eventTime = 10L,
            originX = 100f,
            originY = 200f,
        )
        // The game receives a DOWN at the wheel centre, where its own joystick lives, not at the
        // off-centre point the thumb happened to touch.
        assertEquals(100f, down.x, 0.001f)
        assertEquals(200f, down.y, 0.001f)

        val move = processor.move(profile, x = 140f, y = 210f, eventTime = 11L)!!
        assertEquals(140f, move.x, 0.001f)
        assertEquals(180f, move.y, 0.001f)
        assertEquals(40f, move.outputDx, 0.001f)
        assertEquals(-20f, move.outputDy, 0.001f)

        val cancel = processor.cancel(eventTime = 12L)!!
        assertEquals(140f, cancel.x, 0.001f)
        assertEquals(180f, cancel.y, 0.001f)
    }

    @Test
    fun `deadzone is radial and emits zero displacement at its boundary`() {
        val processor = WheelTouchProcessor()
        val profile = WheelProfile(sensitivity = 4f, deadzonePx = 10, radiusPx = 200)
        processor.down(profile, 50f, 50f, eventTime = 1L)

        val move = processor.move(profile, 56f, 58f, eventTime = 2L)!!

        assertTrue(move.insideDeadzone)
        assertEquals(0f, move.outputDx, 0.001f)
        assertEquals(0f, move.outputDy, 0.001f)
    }

    @Test
    fun `axis gains inversion and radius clamp all affect the emitted coordinates`() {
        val processor = WheelTouchProcessor()
        val asymmetric = WheelProfile(
            sensitivity = 1f,
            xSensitivity = 2f,
            ySensitivity = 0.5f,
            invertY = true,
            deadzonePx = 0,
            radiusPx = 200,
        )
        processor.down(asymmetric, 0f, 0f, eventTime = 1L)
        val adjusted = processor.move(asymmetric, 30f, 40f, eventTime = 2L)!!

        assertEquals(60f, adjusted.outputDx, 0.001f)
        assertEquals(-20f, adjusted.outputDy, 0.001f)
        assertFalse(adjusted.clamped)

        processor.reset()
        val fast = WheelProfile(sensitivity = 4f, deadzonePx = 0, radiusPx = 100)
        processor.down(fast, 10f, 20f, eventTime = 3L)
        val clamped = processor.move(fast, 110f, 20f, eventTime = 4L)!!

        assertTrue(clamped.clamped)
        assertEquals(100f, WheelTouchProcessor.magnitude(clamped), 0.001f)
        assertEquals(110f, clamped.x, 0.001f)
    }

    @Test
    fun `smoothing filters the value without buffering or dropping events`() {
        val processor = WheelTouchProcessor()
        val profile = WheelProfile(
            sensitivity = 2f,
            deadzonePx = 0,
            radiusPx = 200,
            smoothing = 0.5f,
        )
        processor.down(profile, 0f, 0f, eventTime = 1L)

        val first = processor.move(profile, 20f, 0f, eventTime = 2L)!!
        val second = processor.move(profile, 20f, 0f, eventTime = 3L)!!

        assertEquals(20f, first.outputDx, 0.001f)
        assertEquals(30f, second.outputDx, 0.001f)
        assertEquals(3L, processor.processedEvents)
    }

    @Test
    fun `up and cancel close the pointer lifecycle and preserve down time`() {
        val processor = WheelTouchProcessor()
        val profile = WheelProfile(deadzonePx = 0)

        assertNull(processor.move(profile, 1f, 1f, eventTime = 1L))
        processor.down(profile, 5f, 6f, eventTime = 10L, downTime = 8L)
        val up = processor.up(profile, 7f, 8f, eventTime = 12L)!!
        assertEquals(WheelAction.UP, up.action)
        assertEquals(8L, up.downTime)
        assertFalse(processor.isActive)
        assertNull(processor.cancel(13L))

        processor.down(profile, 1f, 2f, eventTime = 20L)
        processor.move(profile, 5f, 2f, eventTime = 21L)
        val cancel = processor.cancel(22L)!!
        assertEquals(WheelAction.CANCEL, cancel.action)
        assertEquals(20L, cancel.downTime)
        assertFalse(processor.isActive)
    }

    @Test
    fun `response curves produce distinct real movement`() {
        fun displacement(curve: WheelResponseCurve): Float {
            val processor = WheelTouchProcessor()
            val profile = WheelProfile(
                sensitivity = 1f,
                deadzonePx = 0,
                radiusPx = 200,
                curve = curve,
            )
            processor.down(profile, 0f, 0f, eventTime = 1L)
            return processor.move(profile, 50f, 0f, eventTime = 2L)!!.outputDx
        }

        assertEquals(50f, displacement(WheelResponseCurve.LINEAR), 0.001f)
        assertTrue(displacement(WheelResponseCurve.PROGRESSIVE) > 50f)
        assertTrue(displacement(WheelResponseCurve.AGGRESSIVE) > 50f)
        assertTrue(displacement(WheelResponseCurve.PRECISE) > 50f)
    }
}
