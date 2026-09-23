package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7's drag behaviour, as arithmetic.
 *
 * These are the three bugs the KDoc on [OverlayFrame] names, written as tests: a window clamped by
 * the screen instead of by its own width ends up ungrabbable, a window snapped by its left edge
 * fights the user, and a window wider than its screen throws inside `coerceIn`.
 */
class OverlayFrameTest {

    private val portrait = OverlayFrame(screenWidth = 1080, screenHeight = 2400, windowWidth = 48, windowHeight = 48)

    @Test
    fun `a frame is only measured once the screen has a size`() {
        assertFalse(OverlayFrame.UNMEASURED.isMeasured)
        assertTrue(portrait.isMeasured)
        assertFalse(OverlayFrame(0, 2400, 48, 48).isMeasured)
    }

    @Test
    fun `before the first layout a position is kept rather than clamped to nothing`() {
        assertEquals(OverlayPlacement(50, 60, ScreenEdge.FLOATING), OverlayFrame.UNMEASURED.clamp(50, 60))
        assertEquals(OverlayPlacement(0, 900, ScreenEdge.FLOATING), OverlayFrame.UNMEASURED.clamp(-5, 900))
        // Snapping cannot pick an edge of a screen whose width is unknown.
        assertEquals(OverlayPlacement(50, 60, ScreenEdge.FLOATING), OverlayFrame.UNMEASURED.snap(50, 60))
    }

    @Test
    fun `a window dragged off the edge comes back by its own width, not the screen's`() {
        assertEquals(OverlayPlacement(1032, 2352, ScreenEdge.FLOATING), portrait.clamp(5000, 5000))
        assertEquals(OverlayPlacement(0, 0, ScreenEdge.FLOATING), portrait.clamp(-200, -200))
        assertEquals(1032, portrait.maxX)
        assertEquals(2352, portrait.maxY)
    }

    @Test
    fun `a window bigger than its screen sits at the origin instead of throwing`() {
        val split = OverlayFrame(screenWidth = 720, screenHeight = 1600, windowWidth = 900, windowHeight = 1800)
        assertEquals(0, split.maxX)
        assertEquals(0, split.maxY)
        assertEquals(OverlayPlacement(0, 0, ScreenEdge.FLOATING), split.clamp(300, 400))
    }

    @Test
    fun `snapping picks the edge the window's centre is nearer`() {
        val pill = OverlayFrame(1080, 2400, windowWidth = 200, windowHeight = 60)
        assertEquals(OverlayPlacement(0, 100, ScreenEdge.LEFT), pill.snap(120, 100))
        assertEquals(OverlayPlacement(880, 100, ScreenEdge.RIGHT), pill.snap(600, 100))
    }

    @Test
    fun `a wide window on the right side does not snap left because its left edge is`() {
        // x = 500 of 1080: the left edge is in the left half, the window plainly is not.
        val wide = OverlayFrame(1080, 2400, windowWidth = 600, windowHeight = 60)
        val placed = wide.snap(500, 300)
        assertEquals(ScreenEdge.RIGHT, placed.edge)
        assertEquals(480, placed.x)
    }

    @Test
    fun `a centre exactly on the midline goes left, so the rule has no gap`() {
        val button = OverlayFrame(1080, 2400, windowWidth = 80, windowHeight = 80)
        assertEquals(ScreenEdge.LEFT, button.snap(500, 0).edge)
        assertEquals(ScreenEdge.RIGHT, button.snap(501, 0).edge)
    }

    @Test
    fun `the margin keeps a snapped control clear of curved glass on both sides`() {
        val inset = OverlayFrame(1080, 2400, 48, 48, marginPx = 8)
        assertEquals(OverlayPlacement(8, 100, ScreenEdge.LEFT), inset.snap(0, 100))
        assertEquals(OverlayPlacement(1024, 100, ScreenEdge.RIGHT), inset.snap(1000, 100))
    }

    @Test
    fun `a margin wider than the room available still leaves the window on screen`() {
        val cramped = OverlayFrame(screenWidth = 200, screenHeight = 400, windowWidth = 190, windowHeight = 40, marginPx = 40)
        assertEquals(10, cramped.maxX)
        assertEquals(OverlayPlacement(10, 0, ScreenEdge.LEFT), cramped.snap(0, 0))
        assertEquals(OverlayPlacement(0, 0, ScreenEdge.RIGHT), cramped.snap(199, 0))
    }

    @Test
    fun `snapping moves a window sideways and leaves its height alone`() {
        val placed = portrait.snap(900, 1234)
        assertEquals(1234, placed.y)
        assertEquals(ScreenEdge.RIGHT, placed.edge)
    }

    @Test
    fun `place is the user's snap setting and nothing else`() {
        assertEquals(portrait.snap(900, 300), portrait.place(900, 300, snapToEdge = true))
        assertEquals(portrait.clamp(900, 300), portrait.place(900, 300, snapToEdge = false))
    }

    @Test
    fun `a rotation moves a free-floating window proportionally rather than in pixels`() {
        val landscape = OverlayFrame(screenWidth = 2400, screenHeight = 1080, windowWidth = 48, windowHeight = 48)
        val moved = landscape.rescaleFrom(portrait, x = 900, y = 1200, snapToEdge = false)
        assertEquals(OverlayPlacement(2029, 526, ScreenEdge.FLOATING), moved)
    }

    @Test
    fun `a button snapped right in portrait is still on the right in landscape`() {
        val portraitInset = OverlayFrame(1080, 2400, 48, 48, marginPx = 8)
        val landscapeInset = OverlayFrame(2400, 1080, 48, 48, marginPx = 8)
        val moved = landscapeInset.rescaleFrom(portraitInset, x = 1024, y = 400, snapToEdge = true)
        assertEquals(ScreenEdge.RIGHT, moved.edge)
        assertEquals(2344, moved.x)
    }

    @Test
    fun `rescaling from a frame that was never measured is just a placement`() {
        val moved = portrait.rescaleFrom(OverlayFrame.UNMEASURED, x = 5000, y = 5000, snapToEdge = false)
        assertEquals(OverlayPlacement(1032, 2352, ScreenEdge.FLOATING), moved)
    }

    // ------------------------------------------------------------------ spec §2: safe-area insets

    /**
     * A frame with no insets is the old frame exactly. This is the guarantee that adding the safe area did
     * not move the button and pill that drag today: every value the pre-§2 tests above assert still holds
     * because the four inset defaults are zero.
     */
    @Test
    fun `with no insets the usable box is the whole screen`() {
        assertEquals(0, portrait.minX)
        assertEquals(0, portrait.minY)
        assertEquals(1032, portrait.maxX)
        assertEquals(2352, portrait.maxY)
    }

    private val cutout = OverlayFrame(
        screenWidth = 2400, screenHeight = 1080, windowWidth = 48, windowHeight = 48,
        insetLeft = 100, insetTop = 30, insetRight = 100, insetBottom = 40,
    )

    @Test
    fun `a clamp keeps the window inside the safe area, not just on the screen`() {
        // Dragged into the notch on the left: pulled back to the left inset, not to x = 0.
        assertEquals(OverlayPlacement(100, 30, ScreenEdge.FLOATING), cutout.clamp(-500, -500))
        // Dragged off the right: stops a window-width short of the right inset.
        assertEquals(2252, cutout.maxX) // 2400 - 100 - 48
        assertEquals(992, cutout.maxY) // 1080 - 40 - 48
        assertEquals(OverlayPlacement(2252, 992, ScreenEdge.FLOATING), cutout.clamp(9999, 9999))
    }

    @Test
    fun `snapping respects the side insets`() {
        assertEquals(ScreenEdge.LEFT, cutout.snap(200, 500).edge)
        assertEquals(100, cutout.snap(200, 500).x) // left inset, margin 0
        assertEquals(2252, cutout.snap(2000, 500).x) // right inset
    }

    // ------------------------------------------------------------------ spec §2: four corners

    @Test
    fun `each corner pins to the right two edges inside the safe area`() {
        assertEquals(OverlayPlacement(100, 30, ScreenEdge.LEFT, Corner.TOP_LEFT), cutout.placeAtCorner(Corner.TOP_LEFT))
        assertEquals(OverlayPlacement(2252, 30, ScreenEdge.RIGHT, Corner.TOP_RIGHT), cutout.placeAtCorner(Corner.TOP_RIGHT))
        assertEquals(OverlayPlacement(100, 992, ScreenEdge.LEFT, Corner.BOTTOM_LEFT), cutout.placeAtCorner(Corner.BOTTOM_LEFT))
        assertEquals(OverlayPlacement(2252, 992, ScreenEdge.RIGHT, Corner.BOTTOM_RIGHT), cutout.placeAtCorner(Corner.BOTTOM_RIGHT))
    }

    @Test
    fun `a corner placement carries the margin on both of its edges`() {
        val m = OverlayFrame(1080, 2400, 48, 48, marginPx = 12)
        assertEquals(OverlayPlacement(12, 12, ScreenEdge.LEFT, Corner.TOP_LEFT), m.placeAtCorner(Corner.TOP_LEFT))
        assertEquals(OverlayPlacement(1020, 2340, ScreenEdge.RIGHT, Corner.BOTTOM_RIGHT), m.placeAtCorner(Corner.BOTTOM_RIGHT))
    }

    @Test
    fun `a free drag resolves to the nearest corner by the window's centre`() {
        // Top-left quadrant of the usable box.
        assertEquals(Corner.TOP_LEFT, cutout.nearestCorner(200, 100))
        assertEquals(Corner.TOP_RIGHT, cutout.nearestCorner(2200, 100))
        assertEquals(Corner.BOTTOM_LEFT, cutout.nearestCorner(200, 1000))
        assertEquals(Corner.BOTTOM_RIGHT, cutout.nearestCorner(2200, 1000))
    }

    @Test
    fun `an unmeasured frame still answers a corner request without throwing`() {
        val p = OverlayFrame.UNMEASURED.placeAtCorner(Corner.BOTTOM_RIGHT)
        assertEquals(Corner.BOTTOM_RIGHT, p.corner)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    // ------------------------------------------------------------------ spec §2: fractional position

    @Test
    fun `a position round-trips through its fraction to within rounding`() {
        val f = cutout.fractionOf(1200, 600)
        val back = cutout.fromFraction(f, snapToEdge = false)
        // The clamp inside fractionOf leaves 1200,600 untouched (well inside the safe box), so the round
        // trip returns the same pixel give or take integer truncation.
        assertTrue("x drifted: ${back.x}", kotlin.math.abs(back.x - 1200) <= 1)
        assertTrue("y drifted: ${back.y}", kotlin.math.abs(back.y - 600) <= 1)
    }

    @Test
    fun `a fraction set in portrait resolves onto a landscape screen inside its safe area`() {
        val portraitInset = OverlayFrame(1080, 2400, 48, 48, insetLeft = 20, insetTop = 60, insetRight = 20, insetBottom = 80)
        val landscapeInset = OverlayFrame(2400, 1080, 48, 48, insetLeft = 60, insetTop = 20, insetRight = 60, insetBottom = 20)
        // Bottom-right in portrait.
        val f = portraitInset.fractionOf(portraitInset.maxX, portraitInset.maxY)
        assertEquals(1f, f.xFraction, 0.001f)
        assertEquals(1f, f.yFraction, 0.001f)
        val landed = landscapeInset.fromFraction(f, snapToEdge = false)
        // Still bottom-right, now of the landscape safe box.
        assertEquals(landscapeInset.maxX, landed.x)
        assertEquals(landscapeInset.maxY, landed.y)
    }

    @Test
    fun `the fraction of an unmeasured frame is the origin, not a divide by zero`() {
        assertEquals(PositionFraction(0f, 0f), OverlayFrame.UNMEASURED.fractionOf(500, 500))
    }

    @Test
    fun `a taller-than-wide screen is portrait and a wider-than-tall screen is not`() {
        assertTrue(OverlayFrame(1080, 2400, 48, 48).isPortrait)
        assertFalse(OverlayFrame(2400, 1080, 48, 48).isPortrait)
        // A square ties to portrait — the tie only decides which slot a fraction is filed under.
        assertTrue(OverlayFrame(1080, 1080, 48, 48).isPortrait)
    }

    /**
     * The four corners as fractions, and the round trip back: each corner is a fraction with both
     * components pinned to 0 or 1, and only those exact points name a corner. This is what lets a corner
     * pin be stored as a plain fraction (no corner field on the model) and re-resolve after a rotation.
     */
    @Test
    fun `each corner is the fraction of the box side it hugs`() {
        assertEquals(PositionFraction(0f, 0f), Corner.TOP_LEFT.fraction)
        assertEquals(PositionFraction(1f, 0f), Corner.TOP_RIGHT.fraction)
        assertEquals(PositionFraction(0f, 1f), Corner.BOTTOM_LEFT.fraction)
        assertEquals(PositionFraction(1f, 1f), Corner.BOTTOM_RIGHT.fraction)
    }

    @Test
    fun `a corner's fraction resolves back to that corner, and a spot between them to none`() {
        for (corner in Corner.entries) {
            assertEquals(corner, Corner.fromFraction(corner.fraction))
        }
        assertNull(Corner.fromFraction(PositionFraction(0.5f, 0.5f)))
        assertNull(Corner.fromFraction(PositionFraction(1f, 0.75f))) // the default anchor is not a corner
    }

    @Test
    fun `a corner's fraction resolves to the matching extreme of the usable box`() {
        // marginPx 0: fromFraction resolves to the raw safe-box edges, which is where placeAtCorner also
        // lands with no margin. (With a margin, placeAtCorner insets the touched sides and fromFraction
        // does not — a deliberate, minor difference, so this pins the no-margin case where they agree.)
        val frame = OverlayFrame(1080, 2400, 48, 48, insetTop = 60, insetBottom = 80)
        for (corner in Corner.entries) {
            val viaFraction = frame.fromFraction(corner.fraction, snapToEdge = false)
            assertEquals("${corner.name} x", if (corner.isLeft) frame.minX else frame.maxX, viaFraction.x)
            assertEquals("${corner.name} y", if (corner.isTop) frame.minY else frame.maxY, viaFraction.y)
            assertEquals("${corner.name} vs placeAtCorner", frame.placeAtCorner(corner).x, viaFraction.x)
            assertEquals("${corner.name} vs placeAtCorner", frame.placeAtCorner(corner).y, viaFraction.y)
        }
    }
}
