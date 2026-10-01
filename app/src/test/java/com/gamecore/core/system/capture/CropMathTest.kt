package com.gamecore.core.system.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crop mapping, pinned down without an Android device.
 *
 * A crop that is read at the wrong scale is the kind of bug that looks fine on the phone it was written on and
 * saves the wrong region on every other one, so the axis scaling, the clamping and the preview==bitmap
 * identity are all asserted here rather than left to be discovered in the field. The point of keeping
 * [CropMath] android-free is precisely that these can run as plain JVM tests.
 */
class CropMathTest {

    @Test
    fun `a preview smaller than the frame scales the crop up by the frame ratio`() {
        val result = CropMath.previewToBitmap(
            rect = CropRect(10, 20, 50, 60),
            preview = CropSize(100, 200),
            bitmap = CropSize(200, 400),
        )
        assertEquals(CropRect(20, 40, 100, 120), result)
    }

    @Test
    fun `a preview larger than the frame scales the crop down`() {
        val result = CropMath.previewToBitmap(
            rect = CropRect(20, 40, 100, 120),
            preview = CropSize(200, 400),
            bitmap = CropSize(100, 200),
        )
        assertEquals(CropRect(10, 20, 50, 60), result)
    }

    @Test
    fun `each axis is scaled by its own ratio, not a shared one`() {
        // 2x across, 4x down: a crop mapped with a single scale would be wrong on one axis.
        val result = CropMath.previewToBitmap(
            rect = CropRect(10, 10, 50, 50),
            preview = CropSize(100, 100),
            bitmap = CropSize(200, 400),
        )
        assertEquals(CropRect(20, 40, 100, 200), result)
    }

    @Test
    fun `edges are rounded to the nearest whole pixel`() {
        // 10/3 = 3.333..: 1 -> 3, 2 -> 6.667 -> 7. Never a truncation that quietly loses a column.
        val result = CropMath.previewToBitmap(
            rect = CropRect(1, 1, 2, 2),
            preview = CropSize(3, 3),
            bitmap = CropSize(10, 10),
        )
        assertEquals(CropRect(3, 3, 7, 7), result)
    }

    @Test
    fun `a crop that runs past the preview is clamped to the frame bounds`() {
        val result = CropMath.previewToBitmap(
            rect = CropRect(-10, -20, 150, 160),
            preview = CropSize(100, 100),
            bitmap = CropSize(100, 100),
        )
        assertEquals(CropRect(0, 0, 100, 100), result)
    }

    @Test
    fun `an equal preview and frame map an in-bounds crop to itself`() {
        val rect = CropRect(30, 50, 120, 200)
        val result = CropMath.previewToBitmap(rect, CropSize(300, 500), CropSize(300, 500))
        assertEquals(rect, result)
    }

    @Test
    fun `a rect dragged the wrong way is normalised before it is scaled`() {
        val result = CropMath.previewToBitmap(
            rect = CropRect(50, 60, 10, 20),
            preview = CropSize(100, 100),
            bitmap = CropSize(100, 100),
        )
        assertEquals(CropRect(10, 20, 50, 60), result)
    }

    @Test
    fun `a non-positive dimension has no mapping and returns an empty rect`() {
        val fromZeroPreview =
            CropMath.previewToBitmap(CropRect(0, 0, 10, 10), CropSize(0, 100), CropSize(100, 100))
        val toZeroBitmap =
            CropMath.previewToBitmap(CropRect(0, 0, 10, 10), CropSize(100, 100), CropSize(100, 0))
        assertEquals(CropRect(0, 0, 0, 0), fromZeroPreview)
        assertTrue(fromZeroPreview.isEmpty)
        assertEquals(CropRect(0, 0, 0, 0), toZeroBitmap)
    }

    @Test
    fun `bitmapToPreview inverts previewToBitmap for an exact scale`() {
        val preview = CropSize(100, 200)
        val bitmap = CropSize(200, 400)
        val original = CropRect(10, 20, 50, 60)
        val inFrame = CropMath.previewToBitmap(original, preview, bitmap)
        val backToPreview = CropMath.bitmapToPreview(inFrame, bitmap, preview)
        assertEquals(original, backToPreview)
    }

    @Test
    fun `CropRect derives a non-negative size and reports emptiness`() {
        val real = CropRect(10, 20, 50, 60)
        assertEquals(40, real.width)
        assertEquals(40, real.height)
        assertFalse(real.isEmpty)

        val flat = CropRect(10, 20, 10, 60)
        assertEquals(0, flat.width)
        assertTrue(flat.isEmpty)
    }
}
