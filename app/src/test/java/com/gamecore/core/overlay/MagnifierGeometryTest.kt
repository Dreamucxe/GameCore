package com.gamecore.core.overlay

import com.gamecore.core.overlay.MagnifierGeometry.MAX_FACTOR
import com.gamecore.core.overlay.MagnifierGeometry.MIN_FACTOR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The magnifier's geometry, and above all the one invariant the feature cannot ship without: the region
 * being magnified never overlaps the window doing the magnifying (spec §14.4).
 *
 * That invariant is why these tests exist at this level. The failure it prevents — MediaProjection
 * capturing GameCore's own loupe, so the loupe magnifies itself into an infinite mirror — is obvious on
 * a device and invisible in code review, and checking it on a device means a build, an install, a
 * screen-capture consent dialog and a pair of eyes. Here it is integer arithmetic, so every anchor
 * position and every magnification can be swept in milliseconds.
 */
class MagnifierGeometryTest {

    private val delta = 0.0001f

    /** A 1080x2400 phone, which is the shape most of these assertions are reasoned about on. */
    private val screen = PixelRect(0, 0, 1080, 2400)

    // ---- PixelRect ----

    @Test
    fun intersects_touchingEdgesIsNotAnOverlap() {
        val left = PixelRect(0, 0, 100, 100)
        val right = PixelRect(100, 0, 200, 100)
        // The half-open convention: `left` owns pixels 0..99, `right` owns 100..199. A loupe parked
        // exactly against the source region is legal, and calling this an overlap would reject
        // placements that are fine — including, on a narrow screen, the only one available.
        assertFalse(left.intersects(right))
        assertFalse(right.intersects(left))
    }

    @Test
    fun intersects_oneSharedPixelIsAnOverlap() {
        val a = PixelRect(0, 0, 100, 100)
        val b = PixelRect(99, 99, 200, 200)
        assertTrue(a.intersects(b))
        assertTrue(b.intersects(a))
    }

    @Test
    fun intersects_emptyRectangleOverlapsNothing() {
        val empty = PixelRect(50, 50, 50, 50)
        val covering = PixelRect(0, 0, 100, 100)
        assertTrue(empty.isEmpty)
        // A zero-area source region cannot be re-captured, so it is not the dangerous case even though
        // it sits geometrically inside the other rectangle.
        assertFalse(empty.intersects(covering))
        assertFalse(covering.intersects(empty))
    }

    @Test
    fun width_invertedRectangleIsZeroNotNegative() {
        val inverted = PixelRect(200, 200, 100, 100)
        assertEquals(0, inverted.width)
        assertEquals(0, inverted.height)
        assertTrue(inverted.isEmpty)
    }

    @Test
    fun of_negativeSizeCollapsesToEmpty() {
        val rect = PixelRect.of(left = 10, top = 10, width = -50, height = -50)
        assertEquals(10, rect.right)
        assertEquals(10, rect.bottom)
        assertTrue(rect.isEmpty)
    }

    @Test
    fun contains_reportsWhollyInsideOnly() {
        val outer = PixelRect(0, 0, 100, 100)
        assertTrue(outer.contains(PixelRect(0, 0, 100, 100)))
        assertTrue(outer.contains(PixelRect(10, 10, 90, 90)))
        assertFalse(outer.contains(PixelRect(-1, 10, 90, 90)))
        assertFalse(outer.contains(PixelRect(10, 10, 101, 90)))
    }

    // ---- factor clamping ----

    @Test
    fun clampFactor_holdsTheOfferedRange() {
        assertEquals(MIN_FACTOR, MagnifierGeometry.clampFactor(1f), delta)
        assertEquals(MIN_FACTOR, MagnifierGeometry.clampFactor(-4f), delta)
        assertEquals(MAX_FACTOR, MagnifierGeometry.clampFactor(40f), delta)
        assertEquals(3.5f, MagnifierGeometry.clampFactor(3.5f), delta)
    }

    @Test
    fun clampFactor_nanBecomesTheMinimumNotNan() {
        // A NaN would propagate through the division in sourceRegion and produce a region of NaN size,
        // which `toInt()` turns into zero — a loupe showing nothing, with no indication why.
        assertEquals(MIN_FACTOR, MagnifierGeometry.clampFactor(Float.NaN), delta)
    }

    @Test
    fun snapFactor_roundsUpNeverDown() {
        assertEquals(3.3f, MagnifierGeometry.snapFactor(3.24f), delta)
        assertEquals(3.3f, MagnifierGeometry.snapFactor(3.201f), delta)
        // Losing a step is the wrong direction to err for someone who cannot read the screen.
        assertTrue(MagnifierGeometry.snapFactor(3.24f) >= 3.24f)
    }

    @Test
    fun snapFactor_valueAlreadyOnAStepIsUnchanged() {
        // Load-bearing: if an exact request snapped upward, resolve() would set raisedFromRequested and
        // the UI would explain a constraint that never applied.
        assertEquals(MIN_FACTOR, MagnifierGeometry.snapFactor(MIN_FACTOR), delta)
        assertEquals(3f, MagnifierGeometry.snapFactor(3f), delta)
        assertEquals(6.6f, MagnifierGeometry.snapFactor(6.6f), delta)
        assertEquals(MAX_FACTOR, MagnifierGeometry.snapFactor(MAX_FACTOR), delta)
    }

    @Test
    fun snapFactor_reachesTheAdvertisedCeiling() {
        // Guards the truncation bug the SCAN_STEPS KDoc describes: `6f / 0.1f` is 59.999999, so a
        // truncating step count would cap the whole feature at 7.9x while the constant says 8x.
        assertEquals(MAX_FACTOR, MagnifierGeometry.snapFactor(7.95f), delta)
    }

    // ---- source region ----

    @Test
    fun sourceRegion_sizeIsLoupeSizeOverFactor() {
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            factor = 4f, screen = screen,
        )
        assertEquals(150, region.rect.width)
        assertEquals(100, region.rect.height)
        assertFalse(region.wasConstrained)
        assertEquals(4f, region.effectiveFactor, delta)
    }

    @Test
    fun sourceRegion_isCentredOnTheAnchor() {
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            factor = 4f, screen = screen,
        )
        assertEquals(540 - 75, region.rect.left)
        assertEquals(1200 - 50, region.rect.top)
    }

    @Test
    fun sourceRegion_anchorAtTheEdgeShiftsTheRegionInsteadOfShrinkingIt() {
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 0, anchorY = 0,
            loupeWidth = 600, loupeHeight = 400,
            factor = 4f, screen = screen,
        )
        assertEquals(0, region.rect.left)
        assertEquals(0, region.rect.top)
        // The size is what it was in the middle of the screen. Shrinking here would zoom the picture as
        // the anchor approached a corner, which is a magnification that moves on its own.
        assertEquals(150, region.rect.width)
        assertEquals(100, region.rect.height)
        assertEquals(4f, region.effectiveFactor, delta)
        assertFalse(region.wasConstrained)
    }

    @Test
    fun sourceRegion_alwaysStaysOnScreen() {
        // Swept rather than spot-checked: an off-screen source rect is an ImageReader crop that throws
        // or reads garbage, and the anchor is under the user's finger so every position is reachable.
        for (factor in listOf(MIN_FACTOR, 3.7f, 5f, MAX_FACTOR)) {
            for (x in -200..1280 step 40) {
                for (y in -200..2600 step 80) {
                    val region = MagnifierGeometry.sourceRegion(
                        anchorX = x, anchorY = y,
                        loupeWidth = 600, loupeHeight = 400,
                        factor = factor, screen = screen,
                    )
                    assertTrue(
                        "source $region escaped the screen at ($x,$y) factor $factor",
                        screen.contains(region.rect),
                    )
                }
            }
        }
    }

    @Test
    fun sourceRegion_regionTooLargeForTheScreenReportsTheRealFactor() {
        // A 2000px-wide loupe at 2x wants a 1000px region, which fits; at 2x on a 480px-wide screen it
        // wants 1000px and cannot have it. The region is capped at the screen and the factor actually
        // being applied is 480/2000... reported the other way up: 2000 loupe px over 480 source px.
        val narrow = PixelRect(0, 0, 480, 800)
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 240, anchorY = 400,
            loupeWidth = 2000, loupeHeight = 1400,
            factor = MIN_FACTOR, screen = narrow,
        )
        assertEquals(480, region.rect.width)
        assertTrue(region.wasConstrained)
        // Never flattering: the reported factor is the one the squeezed axis achieved, and it is above
        // the requested 2x rather than echoing it.
        assertTrue(region.effectiveFactor > MIN_FACTOR)
        assertEquals(MIN_FACTOR, region.requestedFactor, delta)
    }

    @Test
    fun sourceRegion_neverCollapsesToNothing() {
        // 8x of a tiny loupe rounds toward zero; a zero-size region would render an empty loupe with no
        // explanation, so the floor is one pixel.
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 100, anchorY = 100,
            loupeWidth = 4, loupeHeight = 4,
            factor = MAX_FACTOR, screen = screen,
        )
        assertTrue(region.rect.width >= 1)
        assertTrue(region.rect.height >= 1)
    }

    // ---- placement: the feedback-loop constraint ----

    @Test
    fun placeLoupe_neverReturnsAPlacementThatOverlapsTheSource() {
        // THE invariant. Every safe placement this returns, for every anchor and every factor, must not
        // intersect the region being captured — otherwise the loupe magnifies itself.
        var safeCount = 0
        for (factor in listOf(MIN_FACTOR, 2.5f, 4f, 6f, MAX_FACTOR)) {
            for (x in 0..1080 step 30) {
                for (y in 0..2400 step 60) {
                    val region = MagnifierGeometry.sourceRegion(
                        anchorX = x, anchorY = y,
                        loupeWidth = 600, loupeHeight = 400,
                        factor = factor, screen = screen,
                    )
                    val placement = MagnifierGeometry.placeLoupe(region.rect, 600, 400, screen)
                    if (placement is LoupePlacement.Safe) {
                        safeCount++
                        assertFalse(
                            "loupe ${placement.bounds} overlaps source ${region.rect} at ($x,$y) factor $factor",
                            placement.bounds.intersects(region.rect),
                        )
                        assertTrue(screen.contains(placement.bounds))
                    }
                }
            }
        }
        // Guards against the test passing because nothing was ever placed.
        assertTrue("no placement was ever found, so the invariant was never exercised", safeCount > 0)
    }

    @Test
    fun placeLoupe_honoursThePreferredCornerWhenItIsLegal() {
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            factor = 4f, screen = screen,
        )
        for (corner in LoupeCorner.entries) {
            val placement = MagnifierGeometry.placeLoupe(region.rect, 600, 400, screen, preferred = corner)
            // A centre anchor at 4x leaves all four corners free, so the remembered corner wins every
            // time. A window that relocates itself is disorienting for the user this feature is for.
            assertEquals(corner, (placement as LoupePlacement.Safe).corner)
        }
    }

    @Test
    fun placeLoupe_movesOffThePreferredCornerWhenItWouldOverlap() {
        // Anchor in the top-left, so the source region sits under the top-left corner.
        val region = MagnifierGeometry.sourceRegion(
            anchorX = 120, anchorY = 120,
            loupeWidth = 600, loupeHeight = 400,
            factor = MIN_FACTOR, screen = screen,
        )
        val placement = MagnifierGeometry.placeLoupe(
            region.rect, 600, 400, screen, preferred = LoupeCorner.TOP_LEFT,
        )
        val safe = placement as LoupePlacement.Safe
        assertFalse(safe.corner == LoupeCorner.TOP_LEFT)
        assertFalse(safe.bounds.intersects(region.rect))
    }

    @Test
    fun placeLoupe_loupeBiggerThanTheScreenIsReportedNotSquashed() {
        val placement = MagnifierGeometry.placeLoupe(
            source = PixelRect(0, 0, 10, 10),
            loupeWidth = 1200, loupeHeight = 400, screen = screen,
        )
        assertEquals(
            PlacementFailure.LOUPE_LARGER_THAN_SCREEN,
            (placement as LoupePlacement.Impossible).reason,
        )
    }

    @Test
    fun placeLoupe_degenerateScreenIsItsOwnFailure() {
        val placement = MagnifierGeometry.placeLoupe(
            source = PixelRect(0, 0, 10, 10),
            loupeWidth = 100, loupeHeight = 100, screen = PixelRect(0, 0, 0, 0),
        )
        assertEquals(
            PlacementFailure.SCREEN_DEGENERATE,
            (placement as LoupePlacement.Impossible).reason,
        )
    }

    @Test
    fun placeLoupe_marginWiderThanTheScreenClampsInsteadOfGoingOffDisplay() {
        val region = PixelRect(540, 1200, 560, 1220)
        val placement = MagnifierGeometry.placeLoupe(
            region, loupeWidth = 600, loupeHeight = 400, screen = screen, margin = 5000,
        )
        val safe = placement as LoupePlacement.Safe
        // A margin larger than the slack degrades to "flush against the edge", never to a window the
        // user cannot see or reach.
        assertTrue(screen.contains(safe.bounds))
    }

    // ---- the counter-intuitive direction: low magnification is the dangerous one ----

    @Test
    fun smallestSafeFactor_raisesTheFactorWhenALowOneCannotBePlaced() {
        // A loupe 1100px tall on a 2400px screen leaves only a 168px band between the top-corner and
        // bottom-corner rows. A centre anchor's source region spans that band at low magnification and
        // clears it only once the region is short enough to fit inside it.
        val tall = 1100
        val found = MagnifierGeometry.smallestSafeFactor(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 1000, loupeHeight = tall,
            screen = screen,
        )
        assertNotNull("no factor could place this loupe, so the raise was never exercised", found)
        val factor = found!!
        assertTrue("expected the constraint to force a raise above the minimum", factor > MIN_FACTOR)

        // The returned factor is genuinely safe...
        val atFound = MagnifierGeometry.sourceRegion(540, 1200, 1000, tall, factor, screen)
        assertTrue(
            MagnifierGeometry.placeLoupe(atFound.rect, 1000, tall, screen) is LoupePlacement.Safe,
        )
        // ...and it is the *smallest* one: the step below it is not.
        val below = factor - MagnifierGeometry.FACTOR_SCAN_STEP
        val atBelow = MagnifierGeometry.sourceRegion(540, 1200, 1000, tall, below, screen)
        assertTrue(
            "factor $below should have been unsafe, so $factor was not the smallest",
            MagnifierGeometry.placeLoupe(atBelow.rect, 1000, tall, screen) is LoupePlacement.Impossible,
        )
    }

    @Test
    fun smallestSafeFactor_neverAnswersBelowWhatWasAsked() {
        val found = MagnifierGeometry.smallestSafeFactor(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            screen = screen, from = 5.5f,
        )
        assertNotNull(found)
        assertTrue(found!! >= 5.5f)
    }

    @Test
    fun smallestSafeFactor_returnsNullWhenNoFactorWorks() {
        // A loupe that nearly fills the display covers every corner at every magnification. The honest
        // answer is "this cannot be placed", not a best-effort overlapping rectangle.
        val found = MagnifierGeometry.smallestSafeFactor(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 1000, loupeHeight = 2300,
            screen = screen,
        )
        assertNull(found)
    }

    // ---- resolve ----

    @Test
    fun resolve_ordinaryRequestIsReadyAtTheRequestedFactor() {
        val resolution = MagnifierGeometry.resolve(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            requestedFactor = 4f, screen = screen,
        )
        val ready = resolution as MagnifierResolution.Ready
        assertEquals(4f, ready.source.requestedFactor, delta)
        assertEquals(4f, ready.source.effectiveFactor, delta)
        assertFalse(ready.raisedFromRequested)
        assertFalse(ready.loupe.intersects(ready.source.rect))
    }

    @Test
    fun resolve_flagsTheRaiseSoTheUiCanExplainIt() {
        val resolution = MagnifierGeometry.resolve(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 1000, loupeHeight = 1100,
            requestedFactor = MIN_FACTOR, screen = screen,
        )
        val ready = resolution as MagnifierResolution.Ready
        assertTrue(ready.raisedFromRequested)
        assertTrue(ready.source.effectiveFactor > MIN_FACTOR)
        assertFalse(ready.loupe.intersects(ready.source.rect))
    }

    @Test
    fun resolve_sliderResolutionIsNotReportedAsARaise() {
        // 3.24x snaps to 3.3x because the slider works in tenths. That is not the feedback-loop
        // constraint pushing the factor up, and the UI must not apologise for it.
        val resolution = MagnifierGeometry.resolve(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 600, loupeHeight = 400,
            requestedFactor = 3.24f, screen = screen,
        )
        val ready = resolution as MagnifierResolution.Ready
        assertFalse(ready.raisedFromRequested)
    }

    @Test
    fun resolve_unplaceableConfigurationFailsWithAReason() {
        val resolution = MagnifierGeometry.resolve(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 1000, loupeHeight = 2300,
            requestedFactor = 4f, screen = screen,
        )
        assertEquals(
            PlacementFailure.SOURCE_COVERS_EVERY_CORNER,
            (resolution as MagnifierResolution.Failed).reason,
        )
    }

    @Test
    fun resolve_oversizedLoupeFailsAsOversizedNotAsCoverage() {
        val resolution = MagnifierGeometry.resolve(
            anchorX = 540, anchorY = 1200,
            loupeWidth = 2000, loupeHeight = 400,
            requestedFactor = 4f, screen = screen,
        )
        // The distinction matters to the user: one is fixed by making the loupe smaller, the other by
        // moving the anchor or accepting more magnification.
        assertEquals(
            PlacementFailure.LOUPE_LARGER_THAN_SCREEN,
            (resolution as MagnifierResolution.Failed).reason,
        )
    }

    @Test
    fun resolve_everyReadyResultIsFeedbackFreeAcrossTheScreen() {
        // The end-to-end form of the invariant, on the call the Android layer actually makes. If this
        // ever goes red, the magnifier is capturing itself somewhere on screen.
        var readyCount = 0
        for (x in 0..1080 step 45) {
            for (y in 0..2400 step 75) {
                val resolution = MagnifierGeometry.resolve(
                    anchorX = x, anchorY = y,
                    loupeWidth = 600, loupeHeight = 400,
                    requestedFactor = MIN_FACTOR, screen = screen,
                    preferred = LoupeCorner.BOTTOM_RIGHT,
                )
                if (resolution is MagnifierResolution.Ready) {
                    readyCount++
                    assertFalse(
                        "loupe ${resolution.loupe} overlaps source ${resolution.source.rect} at ($x,$y)",
                        resolution.loupe.intersects(resolution.source.rect),
                    )
                    assertTrue(screen.contains(resolution.loupe))
                    assertTrue(screen.contains(resolution.source.rect))
                }
            }
        }
        assertTrue("nothing resolved, so the invariant was never exercised", readyCount > 0)
    }

    @Test
    fun resolve_worksOnALandscapeScreenToo() {
        // Rotation swaps the screen rectangle, and the corner rows and columns swap with it. Cheap to
        // check, and the alternative is discovering it in a landscape game.
        val landscape = PixelRect(0, 0, 2400, 1080)
        val resolution = MagnifierGeometry.resolve(
            anchorX = 1200, anchorY = 540,
            loupeWidth = 600, loupeHeight = 400,
            requestedFactor = 3f, screen = landscape,
        )
        val ready = resolution as MagnifierResolution.Ready
        assertFalse(ready.loupe.intersects(ready.source.rect))
        assertTrue(landscape.contains(ready.loupe))
        assertTrue(landscape.contains(ready.source.rect))
    }

    // ---- corner parsing ----

    @Test
    fun of_unknownCornerIsNullNotACrash() {
        // The enum.of discipline: a corner written by a newer build falls back at the call site.
        assertEquals(LoupeCorner.TOP_LEFT, LoupeCorner.of("TOP_LEFT"))
        assertNull(LoupeCorner.of("CENTRE_FLOATING"))
        assertNull(LoupeCorner.of(null))
        assertNull(LoupeCorner.of(""))
    }
}
