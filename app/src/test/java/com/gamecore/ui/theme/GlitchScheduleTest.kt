package com.gamecore.ui.theme

import com.gamecore.core.plugin.theme.PluginGlitch
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the renderer's scheduling and framing maths, which is the half of the glitch effect that can
 * be tested without a display.
 *
 * The drawing itself needs a device. What does not is the question of whether a legal [PluginGlitch]
 * can produce a frame that is out of bounds, an idle interval outside its own range, a band layout
 * that does not cover the screen, or a rhythm that differs between two launches. Those are the
 * mistakes that would ship as a visible hole or a stutter, and they are all decided by these pure
 * functions.
 */
class GlitchScheduleTest {

    private val block = PluginGlitch(intensity = 0.8f)

    @Test
    fun idle_interval_stays_inside_the_themes_own_range() {
        val rng = Random(7)
        repeat(2_000) {
            val ms = nextIdleDelayMs(rng, block, GlitchConditions.None)
            assertTrue("$ms ms is below the theme's floor", ms >= block.burstMinMs)
            assertTrue("$ms ms is above the theme's ceiling", ms <= block.burstMaxMs)
        }
    }

    @Test
    fun an_overloaded_device_bursts_about_twice_as_often_and_never_faster_than_the_floor() {
        val load = GlitchConditions(cpuPercent = 95f)
        assertTrue("the condition reports overload", load.overloaded)

        val rngIdle = Random(11)
        val rngHot = Random(11)
        val idle = (1..500).sumOf { nextIdleDelayMs(rngIdle, block, GlitchConditions.None) }
        val hot = (1..500).sumOf { nextIdleDelayMs(rngHot, block, load) }

        assertTrue("hot total ($hot) must be less than idle ($idle)", hot < idle)
        assertTrue("hot total ($hot) should be roughly half of idle ($idle)", hot * 3 < idle * 2)

        // The floor holds even for a theme whose own floor is the format minimum.
        val floorBlock = PluginGlitch(intensity = 1f, burstMinMs = 120, burstMaxMs = 120)
        val rng = Random(3)
        repeat(100) {
            assertEquals(
                "an overloaded device cannot push below the format floor",
                PluginGlitch.MIN_BURST_MS.toLong(),
                nextIdleDelayMs(rng, floorBlock, load),
            )
        }
    }

    @Test
    fun a_burst_lasts_between_two_and_six_frames_and_grows_with_intensity() {
        val quiet = burstFrameCount(block.copy(intensity = 0f))
        val loud = burstFrameCount(block.copy(intensity = 1f))
        assertTrue("a burst is never a single frame", quiet >= 2)
        assertTrue("a burst is never more than six frames", loud <= 6)
        assertTrue("more intensity means a longer burst", loud > quiet)
        for (i in 0..10) {
            val n = burstFrameCount(block.copy(intensity = i / 10f))
            assertTrue("$n frames is outside 2..6", n in 2..6)
        }
    }

    @Test
    fun bands_cover_the_whole_frame_without_a_seam() {
        val rng = Random(19)
        repeat(500) {
            val bands = tearBands(rng, block)
            assertEquals("band count follows the block", block.slices, bands.size)
            val total = bands.sumOf { it.fraction.toDouble() }
            assertTrue("bands sum to $total, not 1", abs(total - 1.0) < 1e-4)
            assertTrue("a band cannot have negative height", bands.all { it.fraction > 0f })
        }
    }

    @Test
    fun band_offsets_respect_intensity() {
        val rng = Random(23)
        val max = block.sliceOffsetDp * block.intensity
        repeat(500) {
            for (band in tearBands(rng, block)) {
                assertTrue(
                    "offset ${band.offsetDp} exceeds the ${max}dp the theme asked for",
                    abs(band.offsetDp) <= max + 1e-3f,
                )
            }
        }
    }

    @Test
    fun a_throttled_device_drops_the_first_frame_only() {
        val throttled = GlitchConditions(cpuPercent = 40f, thermalLevel = 5)
        assertTrue("the condition reports throttling", throttled.throttling)

        val rng = Random(31)
        val first = burstFrame(rng, block, throttled, index = 0)
        assertEquals("the frame-drop illusion lands on frame zero", FRAME_DROP_DP, first.jitterY, 1e-6f)

        val rng2 = Random(31)
        for (index in 1..4) {
            val later = burstFrame(rng2, block, throttled, index = index)
            assertTrue(
                "frame $index must not carry the drop (was ${later.jitterY})",
                later.jitterY < FRAME_DROP_DP,
            )
        }
    }

    @Test
    fun a_healthy_device_never_drops_a_frame() {
        val rng = Random(37)
        repeat(200) { index ->
            val frame = burstFrame(rng, block, GlitchConditions(cpuPercent = 20f, thermalLevel = 0), index % 6)
            assertTrue("jitter ${frame.jitterY} is a drop on a healthy device", frame.jitterY < FRAME_DROP_DP)
        }
    }

    @Test
    fun the_same_seed_produces_the_same_frames() {
        // The LaunchedEffect seeds its Random from the block's own hashCode, which is what makes a
        // theme look the same on every launch rather than different every time it is installed.
        val a = (0..5).map { burstFrame(Random(99), block, GlitchConditions.None, it) }
        val b = (0..5).map { burstFrame(Random(99), block, GlitchConditions.None, it) }
        assertEquals("identical seeds must produce identical frames", a, b)
    }

    @Test
    fun a_theme_that_asked_for_no_motion_produces_no_glitch_block() {
        // resolvedGlitch() is the renderer's input; zero intensity means "palette only", and the
        // renderer must not even be handed a block to record a layer for.
        val theme = com.gamecore.core.plugin.theme.PluginTheme(
            name = "Quiet",
            dark = true,
            primary = 0xFF00F0FF.toInt(),
            onPrimary = 0xFF000000.toInt(),
            background = 0xFF000000.toInt(),
            surface = 0xFF111111.toInt(),
            onSurface = 0xFFFFFFFF.toInt(),
            accent = 0xFFFF0055.toInt(),
            cornerRadiusDp = 16,
            font = com.gamecore.core.plugin.theme.PluginTheme.Font.MONO,
            uiScale = 1f,
            schema = com.gamecore.core.plugin.theme.PluginTheme.SCHEMA_V3,
            glitch = PluginGlitch(intensity = 0f),
        )
        assertEquals("zero intensity resolves to no block", null, theme.resolvedGlitch())
    }
}
