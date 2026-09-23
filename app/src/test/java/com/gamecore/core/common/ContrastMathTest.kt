package com.gamecore.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contrast figures the redesign's §3/§11 sign-off is checked against.
 *
 * The reference values are the ones any online WCAG contrast tool prints — black
 * on white is exactly 21:1, white on white is 1:1 — so they are written out
 * literally rather than recomputed from the same arithmetic the code uses. A
 * tolerance is allowed because the sRGB linearisation is a real-number power
 * curve, not exact decimal maths.
 */
class ContrastMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val midGrey = 0xFF888888.toInt()

    @Test
    fun `black on white is the maximum ratio of 21 to 1`() {
        assertEquals(21.0, ContrastMath.contrastRatio(black, white), 0.05)
    }

    @Test
    fun `a colour against itself has no contrast`() {
        assertEquals(1.0, ContrastMath.contrastRatio(white, white), 0.05)
        assertEquals(1.0, ContrastMath.contrastRatio(black, black), 0.05)
    }

    @Test
    fun `the ratio does not depend on which colour is foreground`() {
        assertEquals(
            ContrastMath.contrastRatio(black, white),
            ContrastMath.contrastRatio(white, black),
            0.0,
        )
        assertEquals(
            ContrastMath.contrastRatio(midGrey, white),
            ContrastMath.contrastRatio(white, midGrey),
            0.0,
        )
    }

    @Test
    fun `black text on white clears the body-text bar`() {
        assertTrue(ContrastMath.meetsBodyText(black, white))
    }

    @Test
    fun `mid-grey on white fails body text because it falls short of 4-and-a-half to one`() {
        // Derived, not assumed: 0x888888 on white computes to ~3.54, so the boolean must track
        // the number rather than a guess. Below 4.5, so body text fails; at or above 3.0, so
        // large text passes — which is exactly the split §11 relies on.
        val ratio = ContrastMath.contrastRatio(midGrey, white)
        assertEquals(3.54, ratio, 0.05)
        assertEquals(ratio >= ContrastMath.BODY_TEXT_MIN, ContrastMath.meetsBodyText(midGrey, white))
        assertFalse(ContrastMath.meetsBodyText(midGrey, white))
    }

    @Test
    fun `a pair between the two bars passes large text but not body text`() {
        // Same mid-grey: ~3.54 sits above the 3.0 large-text bar and below the 4.5 body bar.
        val ratio = ContrastMath.contrastRatio(midGrey, white)
        assertTrue("expected >= $ratio to clear the large-text bar", ratio >= ContrastMath.LARGE_TEXT_MIN)
        assertTrue("expected < $ratio to miss the body bar", ratio < ContrastMath.BODY_TEXT_MIN)
        assertTrue(ContrastMath.meetsLargeText(midGrey, white))
        assertFalse(ContrastMath.meetsBodyText(midGrey, white))
    }

    @Test
    fun `white is fully luminous and black is not luminous at all`() {
        assertEquals(1.0, ContrastMath.relativeLuminance(white), 0.001)
        assertEquals(0.0, ContrastMath.relativeLuminance(black), 0.001)
    }

    @Test
    fun `luminance ignores the alpha byte`() {
        // A translucent white and an opaque white must read the same, because contrast is only
        // defined once the foreground has been flattened onto the background.
        assertEquals(
            ContrastMath.relativeLuminance(0xFFFFFFFF.toInt()),
            ContrastMath.relativeLuminance(0x00FFFFFF),
            0.0,
        )
    }
}
