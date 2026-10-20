package com.gamecore.data.repository

import com.gamecore.data.repository.WallpaperImageStore.Companion.isInside
import com.gamecore.data.repository.WallpaperImageStore.Companion.sampleSizeFor
import com.gamecore.data.repository.WallpaperImageStore.Companion.scaleTargetFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The three pure decisions inside the wallpaper import, tested without Android.
 *
 * `BitmapFactory` and `Bitmap` cannot run in a JVM unit test, so the import's arithmetic and its path
 * rule are extracted as pure functions and checked here — the same split
 * `PluginScreenStatResolver` makes for the same reason. What is *not* covered is the decode itself,
 * and saying so is better than implying a green suite proves an image can be imported on a device.
 *
 * The load-bearing case is [sampling_bounds_peak_memory_for_a_phone_photo]: the whole reason this
 * store decodes in two passes is that a full-resolution decode of a modern camera photo would exhaust
 * the heap before any downscale could run, and that claim is only true if the sample size is actually
 * chosen the way the doc says.
 */
class WallpaperImageStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `sampling bounds peak memory for a phone photo`() {
        // A 108 MP source (12000x9000) is ~432 MB at ARGB_8888. The bounds pass must pick a sample
        // size that brings the decoded bitmap down to tens of MB while staying at or above the target,
        // so the final scale is still a scale-down and not an upscale.
        val sample = sampleSizeFor(12_000, 9_000, WallpaperImageStore.MAX_DIMENSION)
        assertEquals(4, sample)

        val decodedWidth = 12_000 / sample
        val decodedHeight = 9_000 / sample
        assertTrue(
            "the subsampled long edge must stay at or above the target so the final step is a downscale",
            maxOf(decodedWidth, decodedHeight) >= WallpaperImageStore.MAX_DIMENSION,
        )
        val peakBytes = decodedWidth.toLong() * decodedHeight * 4
        assertTrue(
            "peak decode should be tens of MB, was $peakBytes bytes",
            peakBytes < 64L * 1024 * 1024,
        )
    }

    @Test
    fun `an image already within the target is neither sampled nor scaled`() {
        assertEquals(1, sampleSizeFor(1_080, 1_920, WallpaperImageStore.MAX_DIMENSION))
        assertNull(scaleTargetFor(1_080, 1_920, WallpaperImageStore.MAX_DIMENSION))
    }

    @Test
    fun `scaling preserves aspect ratio and lands on the target long edge`() {
        val (width, height) = scaleTargetFor(3_000, 2_250, 2_048)!!
        assertEquals(2_048, width)
        assertEquals(1_536, height)
    }

    @Test
    fun `a panoramic source cannot round to a zero-height bitmap`() {
        // createScaledBitmap throws on a zero dimension, so the short edge is floored at 1 rather than
        // allowed to round away. A 10000x3 strip is the degenerate case that finds it.
        val (width, height) = scaleTargetFor(10_000, 3, 2_048)!!
        assertEquals(2_048, width)
        assertTrue("height floored to at least 1, was $height", height >= 1)
    }

    @Test
    fun `degenerate dimensions are refused rather than guessed at`() {
        assertEquals(1, sampleSizeFor(0, 0, 2_048))
        assertNull(scaleTargetFor(0, 100, 2_048))
        assertNull(scaleTargetFor(100, 0, 2_048))
        assertNull(scaleTargetFor(100, 100, 0))
    }

    @Test
    fun `delete refuses a path outside the wallpaper directory`() {
        val directory = folder.newFolder("wallpaper")
        val mine = File(directory, "wallpaper-1.webp").apply { writeBytes(byteArrayOf(1)) }
        assertTrue("a file directly inside the directory is ours", isInside(mine, directory))

        val outside = folder.newFile("someone-elses.webp")
        assertFalse("a sibling of the directory is not ours", isInside(outside, directory))

        val nested = File(directory, "nested/deep.webp")
        nested.parentFile?.mkdirs()
        nested.writeBytes(byteArrayOf(1))
        assertFalse("a file in a subdirectory is not directly inside", isInside(nested, directory))

        val traversal = File(directory, "../escaped.webp")
        assertFalse("a .. segment must not compare as inside", isInside(traversal, directory))
    }
}
