package com.gamecore.domain.trigger

import com.gamecore.core.model.FractionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trigger-point coordinate maths (feature 4), proved without a device.
 *
 * A stored point is a pair of [0,1] fractions plus the rotation it was captured in, and the promise of
 * [TapCoordinateMapper] is that it lands on the same physical spot after a rotation or a resolution
 * change. That promise is pure geometry — no `Display`, no `input tap`, no panel — so it is exactly the
 * part that can be pinned here: the same-rotation identity, the axis swap on a quarter turn, the mirror on
 * a half turn, the round trip through [TapCoordinateMapper.captureFraction], and the clamping that keeps a
 * bad input from producing an off-screen tap.
 */
class TapCoordinateMapperTest {

    // A portrait panel and the same panel's dimensions once it is rotated a quarter turn into landscape.
    private val portraitW = 1080
    private val portraitH = 2400
    private val landscapeW = 2400
    private val landscapeH = 1080

    // ------------------------------------------------------------------ same rotation: fraction × size

    @Test
    fun `same rotation maps a fraction straight to its pixel`() {
        val point = FractionPoint(0.25f, 0.75f)
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = point,
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_0,
            currentWidthPx = portraitW,
            currentHeightPx = portraitH,
        )
        // 1080 * 0.25 = 270, 2400 * 0.75 = 1800.
        assertEquals(PixelPoint(270, 1800), pixel)
    }

    @Test
    fun `same rotation holds in a rotated frame too`() {
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(0.5f, 0.5f),
            captureRotation = ScreenRotation.ROTATION_90,
            currentRotation = ScreenRotation.ROTATION_90,
            currentWidthPx = landscapeW,
            currentHeightPx = landscapeH,
        )
        assertEquals(PixelPoint(1200, 540), pixel)
    }

    // ------------------------------------------------------------------ quarter turns: the axes swap

    @Test
    fun `a portrait point resolves into landscape pixels on a 90 degree turn`() {
        // Captured near the top, a quarter of the way across, in portrait; fired in landscape.
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(0.25f, 0.10f),
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_90,
            currentWidthPx = landscapeW,
            currentHeightPx = landscapeH,
        )
        // delta = 90 -> (fx, fy) = (1 - y, x) = (0.90, 0.25); scaled by the landscape dimensions.
        // 2400 * 0.90 = 2160, 1080 * 0.25 = 270.
        assertEquals(PixelPoint(2160, 270), pixel)
    }

    @Test
    fun `a 270 degree turn swaps the axes the other way`() {
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(0.25f, 0.10f),
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_270,
            currentWidthPx = landscapeW,
            currentHeightPx = landscapeH,
        )
        // delta = 270 -> (fx, fy) = (y, 1 - x) = (0.10, 0.75).
        // 2400 * 0.10 = 240, 1080 * 0.75 = 810.
        assertEquals(PixelPoint(240, 810), pixel)
    }

    // ------------------------------------------------------------------ half turn: both axes mirror

    @Test
    fun `a 180 degree turn mirrors both axes`() {
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(0.25f, 0.10f),
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_180,
            // 180 does not swap width and height, so the frame is still portrait-sized.
            currentWidthPx = portraitW,
            currentHeightPx = portraitH,
        )
        // delta = 180 -> (fx, fy) = (1 - x, 1 - y) = (0.75, 0.90).
        // 1080 * 0.75 = 810, 2400 * 0.90 = 2160.
        assertEquals(PixelPoint(810, 2160), pixel)
    }

    @Test
    fun `the difference between rotations is what counts, not their absolute value`() {
        // 270 -> 0 is the same quarter turn as 0 -> 90: both are a delta of 90.
        val fromDelta = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(0.25f, 0.10f),
            captureRotation = ScreenRotation.ROTATION_270,
            currentRotation = ScreenRotation.ROTATION_0,
            currentWidthPx = landscapeW,
            currentHeightPx = landscapeH,
        )
        assertEquals(PixelPoint(2160, 270), fromDelta)
    }

    // ------------------------------------------------------------------ captureFraction is the inverse

    @Test
    fun `captureFraction inverts toCurrentPixels in the same rotation`() {
        val original = FractionPoint(0.3f, 0.6f)
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = original,
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_0,
            currentWidthPx = portraitW,
            currentHeightPx = portraitH,
        )
        val roundTripped = TapCoordinateMapper.captureFraction(pixel.x, pixel.y, portraitW, portraitH)
        assertEquals(original.x, roundTripped.x, 0.001f)
        assertEquals(original.y, roundTripped.y, 0.001f)
    }

    @Test
    fun `a captured pixel round-trips back to itself within rounding`() {
        // A tap that does not divide evenly, to exercise the rounding rather than dodge it.
        val fraction = TapCoordinateMapper.captureFraction(333, 777, portraitW, portraitH)
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = fraction,
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_0,
            currentWidthPx = portraitW,
            currentHeightPx = portraitH,
        )
        assertTrue(Math.abs(pixel.x - 333) <= 1)
        assertTrue(Math.abs(pixel.y - 777) <= 1)
    }

    // ------------------------------------------------------------------ out-of-range is clamped

    @Test
    fun `captureFraction clamps a tap reported outside the screen`() {
        val beyond = TapCoordinateMapper.captureFraction(2000, 5000, portraitW, portraitH)
        assertEquals(1f, beyond.x, 0f)
        assertEquals(1f, beyond.y, 0f)

        val negative = TapCoordinateMapper.captureFraction(-50, -10, portraitW, portraitH)
        assertEquals(0f, negative.x, 0f)
        assertEquals(0f, negative.y, 0f)
    }

    @Test
    fun `captureFraction survives a zero-sized frame`() {
        // A size read that has not landed yet must not divide by zero.
        val fraction = TapCoordinateMapper.captureFraction(5, 5, 0, 0)
        assertEquals(1f, fraction.x, 0f)
        assertEquals(1f, fraction.y, 0f)
    }

    @Test
    fun `toCurrentPixels clamps a stored fraction outside the unit square`() {
        val pixel = TapCoordinateMapper.toCurrentPixels(
            point = FractionPoint(1.5f, -0.2f),
            captureRotation = ScreenRotation.ROTATION_0,
            currentRotation = ScreenRotation.ROTATION_0,
            currentWidthPx = portraitW,
            currentHeightPx = portraitH,
        )
        assertEquals(PixelPoint(1080, 0), pixel)
    }

    // ------------------------------------------------------------------ the rotation helpers

    @Test
    fun `fromSurfaceRotation maps the four Surface constants`() {
        assertEquals(ScreenRotation.ROTATION_0, TapCoordinateMapper.fromSurfaceRotation(0))
        assertEquals(ScreenRotation.ROTATION_90, TapCoordinateMapper.fromSurfaceRotation(1))
        assertEquals(ScreenRotation.ROTATION_180, TapCoordinateMapper.fromSurfaceRotation(2))
        assertEquals(ScreenRotation.ROTATION_270, TapCoordinateMapper.fromSurfaceRotation(3))
        // Out of range wraps rather than throwing.
        assertEquals(ScreenRotation.ROTATION_90, TapCoordinateMapper.fromSurfaceRotation(5))
        assertEquals(ScreenRotation.ROTATION_270, TapCoordinateMapper.fromSurfaceRotation(-1))
    }

    @Test
    fun `rotationFromDegrees maps degrees to quarter turns`() {
        assertEquals(ScreenRotation.ROTATION_0, TapCoordinateMapper.rotationFromDegrees(0))
        assertEquals(ScreenRotation.ROTATION_90, TapCoordinateMapper.rotationFromDegrees(90))
        assertEquals(ScreenRotation.ROTATION_180, TapCoordinateMapper.rotationFromDegrees(180))
        assertEquals(ScreenRotation.ROTATION_270, TapCoordinateMapper.rotationFromDegrees(270))
        assertEquals(ScreenRotation.ROTATION_0, TapCoordinateMapper.rotationFromDegrees(360))
        assertEquals(ScreenRotation.ROTATION_90, TapCoordinateMapper.rotationFromDegrees(450))
    }
}
