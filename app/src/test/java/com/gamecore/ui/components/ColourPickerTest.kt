package com.gamecore.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The colour picker's arithmetic, which is the reason it is written out rather than delegated to the
 * platform.
 *
 * `android.graphics.Color.HSVToColor` would do the same job and could not be tested here — it needs a
 * device, and a conversion nobody can check is one that is wrong until a user notices. So the maths is
 * twenty lines of Kotlin with a known answer at every sixth of the hue wheel, and these are those answers.
 */
class ColourPickerTest {

    @Test
    fun `the six corners of the hue wheel are the six colours they should be`() {
        assertColour(0xFFFF0000, hsvToArgb(0f, 1f, 1f))
        assertColour(0xFFFFFF00, hsvToArgb(60f, 1f, 1f))
        assertColour(0xFF00FF00, hsvToArgb(120f, 1f, 1f))
        assertColour(0xFF00FFFF, hsvToArgb(180f, 1f, 1f))
        assertColour(0xFF0000FF, hsvToArgb(240f, 1f, 1f))
        assertColour(0xFFFF00FF, hsvToArgb(300f, 1f, 1f))
    }

    /** The wrap-around, which is where an off-by-one in the sector arithmetic shows up as a green tinge. */
    @Test
    fun `the far end of the hue strip is the same red as the near end`() {
        assertEquals(hsvToArgb(0f, 1f, 1f), hsvToArgb(360f, 1f, 1f))
        assertColour(0xFFFF0000, hsvToArgb(359.9f, 1f, 1f))
    }

    @Test
    fun `a hue outside the wheel wraps rather than clamping`() {
        assertEquals(hsvToArgb(120f, 1f, 1f), hsvToArgb(480f, 1f, 1f))
        assertEquals(hsvToArgb(240f, 1f, 1f), hsvToArgb(-120f, 1f, 1f))
    }

    @Test
    fun `no saturation is grey and no value is black, at every hue`() {
        for (hue in 0..359 step 30) {
            assertColour(0xFFFFFFFF, hsvToArgb(hue.toFloat(), 0f, 1f))
            assertColour(0xFF000000, hsvToArgb(hue.toFloat(), 1f, 0f))
        }
    }

    /**
     * Full brightness has to reach 255 exactly.
     *
     * Truncating instead of rounding gives 254, which is invisible on screen and wrong in the hex field —
     * a user who typed `FFFFFF` and got `FEFEFE` back would be right to distrust the whole control.
     */
    @Test
    fun `a full channel is 255 and not one short of it`() {
        assertEquals(255, hsvToArgb(0f, 0f, 1f) and 0xFF)
    }

    @Test
    fun `every colour survives a round trip through hsv`() {
        val colours = listOf(
            0xFF00E5FF.toInt(),
            0xFFFF1744.toInt(),
            0xFF4CE07A.toInt(),
            0xFF9C6BFF.toInt(),
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFF123456.toInt(),
        )
        for (argb in colours) {
            val hsv = argbToHsv(argb)
            assertEquals(
                "round trip of ${hexOf(argb)}",
                argb,
                hsvToArgb(hsv[0], hsv[1], hsv[2]),
            )
        }
    }

    /**
     * Grey has no hue, and the picker's KDoc says so rather than pretending otherwise.
     *
     * Asserted because it is the one place the round trip above is genuinely lossy, and a future change
     * that "fixed" it by inventing a hue for grey would be inventing a fact about the user's colour.
     */
    @Test
    fun `grey reports no hue and no saturation`() {
        val hsv = argbToHsv(0xFF808080.toInt())
        assertEquals(0f, hsv[0], 0f)
        assertEquals(0f, hsv[1], 0f)
    }

    @Test
    fun `alpha in the input is ignored rather than carried through`() {
        val hsv = argbToHsv(0x0000E5FF)
        assertColour(0xFF00E5FF, hsvToArgb(hsv[0], hsv[1], hsv[2]))
    }

    // ------------------------------------------------------------------------------------ the hex field

    @Test
    fun `a six digit hex parses, with or without the hash`() {
        assertEquals(0xFF00E5FF.toInt(), parseHexColour("00E5FF"))
        assertEquals(0xFF00E5FF.toInt(), parseHexColour("#00E5FF"))
        assertEquals(0xFF00E5FF.toInt(), parseHexColour("  #00e5ff  "))
    }

    @Test
    fun `a three digit hex expands by doubling each digit`() {
        assertEquals(0xFF00AAFF.toInt(), parseHexColour("0AF"))
        assertEquals(0xFFFFFFFF.toInt(), parseHexColour("#fff"))
    }

    /**
     * Everything else is null, which is what stops the square jumping while a value is half typed.
     *
     * The empty string and the one- and two-digit cases are the states a field passes through on the way to
     * a valid colour, so they matter more than the malformed ones.
     */
    @Test
    fun `a half typed or malformed colour is refused rather than guessed at`() {
        for (text in listOf("", "#", "0", "00", "0000", "00E5F", "00E5FFF", "GGGGGG", "not a colour")) {
            assertNull("\"$text\" parsed as a colour", parseHexColour(text))
        }
    }

    @Test
    fun `hex is written without the alpha byte, in upper case, always six digits`() {
        assertEquals("00E5FF", hexOf(0xFF00E5FF.toInt()))
        assertEquals("000000", hexOf(0xFF000000.toInt()))
        assertEquals("0000FF", hexOf(0xFF0000FF.toInt()))
    }

    @Test
    fun `what the field writes is what the field reads`() {
        for (argb in listOf(0xFF00E5FF.toInt(), 0xFFFF1744.toInt(), 0xFF010203.toInt())) {
            assertEquals(argb, parseHexColour(hexOf(argb)))
        }
    }

    /** Both sides as hex in the failure message: `-16711936` says nothing about which channel is wrong. */
    private fun assertColour(expected: Long, actual: Int) {
        assertEquals(hexOf(expected.toInt()), hexOf(actual))
        assertEquals(expected.toInt(), actual)
    }
}
