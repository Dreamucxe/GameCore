package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
