package com.gamecore.core.overlay

import com.gamecore.core.model.FloatingButtonConfig
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.TurboGaugeChoice
import com.gamecore.domain.monitoring.StatReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turbo layout's three pure pieces: how wide the wings are, how wide the column inside one is, and how
 * much of a gauge a reading fills.
 *
 * The geometry is the part of the redesign that can silently break a control: a wing wider than half the
 * screen pushes the other one off a window that cannot scroll sideways, and a content column below the
 * action-grid floor makes the tiles too small to hit even though every action is technically present. The
 * gauge arithmetic is here for the other kind of silence: a reading with no value must not draw a zero.
 *
 * The drawing itself is checked on a device, not here. What a test can hold is the rules the drawing is
 * built on, and these are the ones with a wrong answer rather than a taste question.
 */
class OverlayTurboPanelTest {

    @Test
    fun `two wings never take more room than the screen has`() {
        for (screenWidthDp in 2..2400) {
            val wing = turboWingWidth(screenWidthDp)
            assertTrue("two $wing dp wings on a $screenWidthDp dp screen", wing * 2 <= screenWidthDp)
        }
    }

    @Test
    fun `a wider screen never gets a narrower wing`() {
        var previous = 0
        for (screenWidthDp in 0..2400) {
            val wing = turboWingWidth(screenWidthDp)
            assertTrue("$wing dp at $screenWidthDp dp, after $previous", wing >= previous)
            previous = wing
        }
    }

    @Test
    fun `the content column is never wider than its wing`() {
        for (screenWidthDp in 2..2400) {
            val wing = turboWingWidth(screenWidthDp)
            val content = turboWingContentWidth(wing)
            assertTrue("$content dp inside a $wing dp wing", content in 1..wing)
        }
    }

    /**
     * The promise the floor is for: a wing the size model allows still lays out at least two action
     * tiles, because the column inside it keeps [FloatingButtonConfig.MIN_PANEL_WIDTH_DP] plus the panel's
     * own insets even when that means using the whole plate.
     */
    @Test
    fun `every wing keeps room for the guaranteed two-column action grid`() {
        for (screenWidthDp in 288..2400) {
            val content = turboWingContentWidth(turboWingWidth(screenWidthDp))
            assertTrue(
                "$content dp at $screenWidthDp dp does not fit two tiles",
                actionsPerRow(content) >= MIN_ACTIONS_PER_ROW,
            )
        }
    }

    /** A narrow wing keeps the whole plate, because the bent edge is what gives way, not the controls. */
    @Test
    fun `a narrow wing uses its full width for content`() {
        val wing = turboWingWidth(360)
        assertEquals(FloatingButtonConfig.MIN_PANEL_WIDTH_DP, wing)
        assertEquals(wing, turboWingContentWidth(wing))
    }

    /** A wide wing leaves a band for the bent edge instead of filling the plate. */
    @Test
    fun `a wide wing leaves the inner edge its light band`() {
        val wing = turboWingWidth(800)
        assertTrue("wing $wing", wing > FloatingButtonConfig.MIN_PANEL_WIDTH_DP)
        assertTrue(turboWingContentWidth(wing) < wing)
    }

    /**
     * The two silhouettes are reflections of each other: every left point has a right point with x
     * subtracted from one, and the same y. A right wing drawn from a second hand-written list would drift
     * from the left one the first time either was adjusted.
     */
    @Test
    fun `the right edge is the left edge mirrored`() {
        for (narrow in listOf(true, false)) {
            val left = turboWingEdgeFractions(isLeft = true, narrow = narrow)
            val right = turboWingEdgeFractions(isLeft = false, narrow = narrow)
            assertEquals(left.size, right.size)
            left.zip(right).forEach { (leftPoint, rightPoint) ->
                assertEquals(1f - leftPoint.first, rightPoint.first, 0.0001f)
                assertEquals(leftPoint.second, rightPoint.second, 0.0001f)
            }
        }
    }

    /** Every edge point is on the plate and the edge runs from the top to the bottom without doubling back. */
    @Test
    fun `the bent edge stays on the plate and always moves downward`() {
        for (narrow in listOf(true, false)) {
            val edge = turboWingEdgeFractions(isLeft = true, narrow = narrow)
            assertEquals(0f, edge.first().second, 0.0001f)
            assertEquals(1f, edge.last().second, 0.0001f)
            var previousY = -1f
            edge.forEach { (x, y) ->
                assertTrue("x=$x is off the plate", x in 0f..1f)
                assertTrue("y=$y went backwards", y >= previousY)
                previousY = y
            }
        }
    }

    @Test
    fun `a percentage reading fills the ring by its percentage`() {
        assertEquals(0.42f, gaugeFraction(TurboGaugeChoice.CPU, reading(HudStat.CPU_USAGE, "42")), 0.0001f)
        assertEquals(0.08f, gaugeFraction(TurboGaugeChoice.GPU, reading(HudStat.GPU_USAGE, "8")), 0.0001f)
        assertEquals(0.73f, gaugeFraction(TurboGaugeChoice.RAM, reading(HudStat.RAM_USAGE, "73")), 0.0001f)
    }

    /** Temperature is the one non-percentage reading, and its scale is the gauge's own. */
    @Test
    fun `a temperature reading uses the gauge's hundred-degree scale`() {
        assertEquals(
            0.55f,
            gaugeFraction(TurboGaugeChoice.TEMPERATURE, reading(HudStat.CPU_TEMPERATURE, "55")),
            0.0001f,
        )
        assertEquals(
            1f,
            gaugeFraction(TurboGaugeChoice.TEMPERATURE, reading(HudStat.CPU_TEMPERATURE, "140")),
            0.0001f,
        )
    }

    /**
     * No value means no fill. This is the one assertion that protects the "never invent a reading" rule:
     * a gauge that drew 0% for an unavailable sensor would be claiming the device measured zero.
     */
    @Test
    fun `an unavailable reading fills nothing`() {
        assertEquals(0f, gaugeFraction(TurboGaugeChoice.CPU, null), 0.0001f)
        val unavailable = StatReading(HudStat.GPU_USAGE, value = null, reason = "No GPU reading.")
        assertEquals(0f, gaugeFraction(TurboGaugeChoice.GPU, unavailable), 0.0001f)
    }

    /** The spoken description carries the reason too, rather than only the word n/a. */
    @Test
    fun `a gauge with no reading describes why`() {
        val unavailable = StatReading(HudStat.CPU_TEMPERATURE, value = null, reason = "No thermal zone.")
        val description = gaugeDescription(TurboGaugeChoice.TEMPERATURE, unavailable)
        assertTrue(description, description.contains("No thermal zone."))
        assertTrue(description, description.contains("Heat ring"))
    }

    private fun reading(stat: HudStat, value: String): StatReading = StatReading(stat, value)
}
