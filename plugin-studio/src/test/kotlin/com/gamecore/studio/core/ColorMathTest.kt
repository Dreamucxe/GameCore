package com.gamecore.studio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The colour arithmetic the editor depends on.
 *
 * These are plain JUnit tests over a pure-Kotlin object: no Android types, no Compose, no
 * rendering. The important contracts are round trips (a picker must not drift a colour just
 * because the user opened the HSL tab), WCAG maths (the analysis panel is only useful if its
 * numbers are the ones WCAG defines), and alpha compositing (a translucent ink must be
 * measured against what is actually behind it).
 */
class ColorMathTest {

    private val samples = listOf(
        0xFFFF8A4CL,
        0xFF2BD4C0L,
        0xFF000000L,
        0xFFFFFFFFL,
        0x80123456L,
    )

    // ------------------------------------------------------------------------- HSV and HSL

    @Test
    fun `hsv round trips within one channel step`() {
        samples.forEach { color ->
            val hsv = ColorMath.hsv(color)
            assertTrue("hue was ${hsv.hue}", hsv.hue >= 0f && hsv.hue <= 360f)
            assertTrue("saturation was ${hsv.saturation}", hsv.saturation in 0f..1f)
            assertTrue("value was ${hsv.value}", hsv.value in 0f..1f)
            assertRgbNear("HSV $color", color, ColorMath.fromHsv(hsv, ColorMath.alpha(color)))
        }
    }

    @Test
    fun `hsl round trips within one channel step`() {
        samples.forEach { color ->
            val hsl = ColorMath.hsl(color)
            assertTrue("hue was ${hsl.hue}", hsl.hue >= 0f && hsl.hue <= 360f)
            assertTrue("saturation was ${hsl.saturation}", hsl.saturation in 0f..1f)
            assertTrue("lightness was ${hsl.lightness}", hsl.lightness in 0f..1f)
            assertRgbNear("HSL $color", color, ColorMath.fromHsl(hsl, ColorMath.alpha(color)))
        }
    }

    @Test
    fun `a grey colour has zero saturation and an undefined hue of zero`() {
        val hsv = ColorMath.hsv(0xFF808080L)
        val hsl = ColorMath.hsl(0xFF808080L)
        assertEquals(0f, hsv.saturation, 0.0001f)
        assertEquals(0f, hsl.saturation, 0.0001f)
        assertEquals(0f, hsv.hue, 0.0001f)
        assertEquals(0f, hsl.hue, 0.0001f)
    }

    // ------------------------------------------------------------------------- hex

    @Test
    fun `hex writes six digits for opaque colours and eight when translucent`() {
        assertEquals("#FF8A4C", ColorMath.hex(0xFFFF8A4CL))
        assertEquals("#80FF8A4C", ColorMath.hex(0x80FF8A4CL))
        assertEquals("#000000", ColorMath.hex(0xFF000000L))
        assertEquals("#FFFFFF", ColorMath.hex(0xFFFFFFFFL))
    }

    @Test
    fun `parse hex accepts the three documented forms`() {
        assertEquals(0xFFFF8A4CL, ColorMath.parseHex("#FF8A4C"))
        assertEquals(0xFFFF8844L, ColorMath.parseHex("#F84"))
        assertEquals(0x80FF8A4CL, ColorMath.parseHex("#80FF8A4C"))
        assertEquals(0xFFFF8A4CL, ColorMath.parseHex("FF8A4C"))
        assertEquals(0xFFFF8A4CL, ColorMath.parseHex("#ff8a4c"))
    }

    @Test
    fun `parse hex rejects malformed input instead of repairing it`() {
        assertNull(ColorMath.parseHex(""))
        assertNull(ColorMath.parseHex("#"))
        assertNull(ColorMath.parseHex("#12345"))
        assertNull(ColorMath.parseHex("#GGGGGG"))
        assertNull(ColorMath.parseHex("#+12345"))
        assertNull(ColorMath.parseHex("#123456789"))
    }

    // ------------------------------------------------------------------------- contrast

    @Test
    fun `black on white is the maximum WCAG contrast`() {
        assertEquals(21.0, ColorMath.contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.0001)
        assertEquals(21.0, ColorMath.contrastRatio(0xFFFFFFFFL, 0xFF000000L), 0.0001)
    }

    @Test
    fun `readable ink chooses black or white with the better contrast`() {
        assertEquals(0xFF000000L, ColorMath.readableOn(0xFFFFFFFFL))
        assertEquals(0xFFFFFFFFL, ColorMath.readableOn(0xFF000000L))

        val midGrey = 0xFF777777L
        val ink = ColorMath.readableOn(midGrey)
        assertTrue(
            "readableOn returned ${ColorMath.hex(ink)}",
            ink == 0xFF000000L || ink == 0xFFFFFFFFL,
        )
        assertTrue(
            "readableOn picked ${ColorMath.hex(ink)} with ${ColorMath.contrastRatio(ink, midGrey)}",
            ColorMath.contrastRatio(ink, midGrey) >= 4.5,
        )
    }

    @Test
    fun `contrast composites a translucent foreground over its background`() {
        // 50% white over black is mid-grey, not white: the ratio must fall well below 21:1.
        val ratio = ColorMath.contrastRatio(0x80FFFFFFL, 0xFF000000L)
        assertTrue("ratio was $ratio", ratio in 5.0..5.6)
    }

    @Test
    fun `contrast composites a translucent background over white`() {
        // 50% black over white is mid-grey, so the ratio is about 5.3:1 rather than 21:1.
        val ratio = ColorMath.contrastRatio(0xFF000000L, 0x80000000L)
        assertTrue("ratio was $ratio", ratio in 5.0..5.6)
    }

    // ------------------------------------------------------------------------- compositing

    @Test
    fun `source over mixes half red into blue and stays opaque`() {
        val mixed = ColorMath.blendOver(0x80FF0000L, 0xFF0000FFL)
        val rgb = ColorMath.rgb(mixed)
        assertEquals(255, ColorMath.alpha(mixed))
        assertTrue("red was ${rgb.red}", rgb.red in 126..129)
        assertEquals(0, rgb.green)
        assertTrue("blue was ${rgb.blue}", rgb.blue in 126..128)
    }

    @Test
    fun `source over leaves opaque foregrounds and transparent foregrounds alone`() {
        val background = 0xFF0000FFL
        assertEquals(0xFFFF0000L, ColorMath.blendOver(0xFFFF0000L, background))
        assertEquals(background, ColorMath.blendOver(0x00FF0000L, background))
    }

    @Test
    fun `with alpha replaces only the alpha byte`() {
        assertEquals(0x40FF8A4CL, ColorMath.withAlpha(0xFFFF8A4CL, 0x40))
        assertEquals(0x40123456L, ColorMath.withAlpha(0xFF123456L, 0x40))
        assertEquals(0xFF123456L, ColorMath.withAlpha(0x00123456L, 0xFF))
    }

    // ------------------------------------------------------------------------- palette helpers

    @Test
    fun `complementary rotates the hue by half a turn`() {
        val original = 0xFFFF0000L
        val complement = ColorMath.complementary(original)
        val hue = ColorMath.hsv(complement).hue
        assertEquals(180f, hue, 1f)
        assertEquals(255, ColorMath.alpha(complement))
    }

    @Test
    fun `analogous returns minus thirty zero and plus thirty degrees`() {
        val original = 0xFF3B5BDBL
        val palette = ColorMath.analogous(original)
        assertEquals(3, palette.size)
        assertEquals(original, palette[1])
        val base = ColorMath.hsv(original).hue
        assertEquals((base - 30f + 360f) % 360f, ColorMath.hsv(palette[0]).hue, 1f)
        assertEquals((base + 30f) % 360f, ColorMath.hsv(palette[2]).hue, 1f)
    }

    @Test
    fun `monochromatic returns five steps from dark to light in one hue`() {
        val palette = ColorMath.monochromatic(0xFF2BD4C0L)
        assertEquals(5, palette.size)
        val baseHue = ColorMath.hsl(0xFF2BD4C0L).hue
        val luminances = palette.map { ColorMath.relativeLuminance(it) }
        palette.forEach { color ->
            assertEquals(baseHue, ColorMath.hsl(color).hue, 1f)
        }
        luminances.zipWithNext().forEach { (darker, lighter) ->
            assertTrue("luminance did not increase: $luminances", lighter > darker)
        }
    }

    @Test
    fun `lighten and darken preserve alpha and move luminance`() {
        val original = 0x80808080L
        val lighter = ColorMath.lighten(original, 0.25f)
        val darker = ColorMath.darken(original, 0.25f)
        assertEquals(128, ColorMath.alpha(lighter))
        assertEquals(128, ColorMath.alpha(darker))
        assertTrue(ColorMath.relativeLuminance(lighter) > ColorMath.relativeLuminance(original))
        assertTrue(ColorMath.relativeLuminance(darker) < ColorMath.relativeLuminance(original))
        assertEquals(original, ColorMath.lighten(original, 0f))
        assertEquals(original, ColorMath.darken(original, 0f))
    }

    // ------------------------------------------------------------------------- colour vision

    @Test
    fun `simulated colour vision keeps alpha and returns a valid colour`() {
        val original = 0x80E5484DL
        ColorVision.entries.forEach { vision ->
            val simulated = ColorMath.simulate(original, vision)
            assertEquals("alpha changed for $vision", 128, ColorMath.alpha(simulated))
            val rgb = ColorMath.rgb(simulated)
            assertTrue(rgb.red in 0..255)
            assertTrue(rgb.green in 0..255)
            assertTrue(rgb.blue in 0..255)
        }
        assertNotEquals(
            "protanopia left pure red unchanged",
            0xFFFF0000L,
            ColorMath.simulate(0xFFFF0000L, ColorVision.PROTANOPIA),
        )
    }

    @Test
    fun `a neutral grey stays approximately neutral under simulation`() {
        val simulated = ColorMath.simulate(0xFF808080L, ColorVision.DEUTERANOPIA)
        val rgb = ColorMath.rgb(simulated)
        assertTrue(abs(rgb.red - rgb.green) <= 2)
        assertTrue(abs(rgb.green - rgb.blue) <= 2)
    }

    // ------------------------------------------------------------------------- helpers

    private fun assertRgbNear(label: String, expected: Long, actual: Long, tolerance: Int = 1) {
        val e = ColorMath.rgb(expected)
        val a = ColorMath.rgb(actual)
        assertTrue("$label: red ${e.red} != ${a.red}", abs(e.red - a.red) <= tolerance)
        assertTrue("$label: green ${e.green} != ${a.green}", abs(e.green - a.green) <= tolerance)
        assertTrue("$label: blue ${e.blue} != ${a.blue}", abs(e.blue - a.blue) <= tolerance)
        assertEquals("$label: alpha", ColorMath.alpha(expected), ColorMath.alpha(actual))
    }
}
