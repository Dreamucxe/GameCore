package com.gamecore.core.model

import com.gamecore.domain.monitoring.StatReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §7 Overlay settings screen's claims, proved off-device.
 *
 * Four groups of these matter more than the rest and are worth saying out loud:
 *
 *  - **[overlayWindowState]** is the one input behind both the chip word and the switch's enabled state.
 *    The audit's temperature bug was a word and a colour derived separately that then disagreed; the
 *    tests below pin the precedence so a chip can never read "Down" beside a switch that is locked, or
 *    "Blocked" over a window the service says is on screen.
 *  - **[overlayPositionSummary]** must keep refusing to name a corner. The position is raw pixels and
 *    this layer has no display to measure them against, so a test asserts the output contains no
 *    direction words — that is the simplification someone will eventually be tempted to make.
 *  - **[intervalTenths]/[intervalMillis]/[intervalLabel]** are a round trip across a factor of ten, which
 *    is exactly the arithmetic that drifts. They are held to each other and to [OverlayConfig]'s own
 *    bounds rather than to numbers retyped here.
 *  - **[pillPreviewNote]** and [absentStatsNote] are §0's no-fake-data rule applied to a preview. The
 *    tests check that a stat this device cannot read is counted and named, and that a stat merely waiting
 *    for the first sample is not — the two render identically and mean the opposite.
 */
class OverlayLogicTest {

    // ------------------------------------------------------------------------------- window state

    @Test
    fun `a window the service reports as up reads Up`() {
        val state = overlayWindowState(
            isVisible = true,
            hasPermission = true,
            isDrivenByProfile = false,
        )
        assertEquals(OverlayWindowState.UP, state)
        assertEquals("Up", state.label)
    }

    @Test
    fun `a visible window is never described as blocked, whatever a stale permission read says`() {
        // The service is drawing it. A permission read that disagrees is the stale one.
        assertEquals(
            OverlayWindowState.UP,
            overlayWindowState(isVisible = true, hasPermission = false, isDrivenByProfile = true),
        )
    }

    @Test
    fun `a missing permission outranks a profile, because it is the more fundamental answer`() {
        assertEquals(
            OverlayWindowState.BLOCKED,
            overlayWindowState(isVisible = false, hasPermission = false, isDrivenByProfile = true),
        )
    }

    @Test
    fun `a profile in charge reads Locked, so the disabled switch is never the only signal`() {
        val state = overlayWindowState(
            isVisible = false,
            hasPermission = true,
            isDrivenByProfile = true,
        )
        assertEquals(OverlayWindowState.LOCKED, state)
        assertEquals("Locked", state.label)
    }

    @Test
    fun `an ordinary switched-off window reads Down`() {
        assertEquals(
            OverlayWindowState.DOWN,
            overlayWindowState(isVisible = false, hasPermission = true, isDrivenByProfile = false),
        )
    }

    @Test
    fun `every window state carries a word`() {
        OverlayWindowState.entries.forEach { state ->
            assertTrue(state.name, state.label.isNotBlank())
        }
    }

    // ---------------------------------------------------------------------------- the header line

    @Test
    fun `the subtitle says Loading before the store has been read`() {
        assertEquals("Loading", overlaySubtitle(isLoaded = false, hasPermission = true, visibleWindows = 2))
    }

    @Test
    fun `a missing permission is reported instead of a count of zero`() {
        val subtitle = overlaySubtitle(isLoaded = true, hasPermission = false, visibleWindows = 0)
        assertFalse(subtitle.contains("Nothing on screen"))
        assertTrue(subtitle.contains("draw over other apps"))
    }

    @Test
    fun `nothing showing with the permission granted says so plainly`() {
        assertEquals(
            "Nothing on screen",
            overlaySubtitle(isLoaded = true, hasPermission = true, visibleWindows = 0),
        )
    }

    @Test
    fun `one window is singular and two are plural`() {
        assertEquals(
            "1 window on screen",
            overlaySubtitle(isLoaded = true, hasPermission = true, visibleWindows = 1),
        )
        assertEquals(
            "2 windows on screen",
            overlaySubtitle(isLoaded = true, hasPermission = true, visibleWindows = 2),
        )
    }

    @Test
    fun `a negative count cannot happen but does not print one either`() {
        assertEquals(
            "Nothing on screen",
            overlaySubtitle(isLoaded = true, hasPermission = true, visibleWindows = -1),
        )
    }

    // --------------------------------------------------------------------------------- positions

    @Test
    fun `an unmoved pill is described as being where it starts out`() {
        val defaults = OverlayConfig()
        val summary = overlayPositionSummary(
            x = defaults.pillX,
            y = defaults.pillY,
            defaultX = defaults.pillX,
            defaultY = defaults.pillY,
        )
        assertTrue(summary, summary.contains("starts out"))
        assertTrue(summary, summary.contains("${defaults.pillY}"))
        assertTrue(isAtDefaultPosition(defaults.pillX, defaults.pillY, defaults.pillX, defaults.pillY))
    }

    @Test
    fun `a dragged window reports the figures it was dragged to`() {
        val summary = overlayPositionSummary(x = 640, y = 128, defaultX = 0, defaultY = 200)
        assertEquals("Dragged to 640, 128", summary)
        assertFalse(isAtDefaultPosition(640, 128, 0, 200))
    }

    @Test
    fun `one axis moved is still a move`() {
        assertFalse(isAtDefaultPosition(x = 0, y = 640, defaultX = 0, defaultY = 200))
    }

    @Test
    fun `the position summary never names a corner, because it has no screen to measure`() {
        val words = listOf("top", "bottom", "left", "right", "corner", "centre", "center")
        val summaries = listOf(
            overlayPositionSummary(0, 200, 0, 200),
            overlayPositionSummary(1_080, 2_400, 0, 200),
            overlayPositionSummary(0, 0, 0, 400),
        )
        summaries.forEach { summary ->
            words.forEach { word ->
                assertFalse("$summary must not say \"$word\"", summary.lowercase().contains(word))
            }
        }
    }

    // ------------------------------------------------------------------------- the update interval

    @Test
    fun `the interval slider spans exactly the config's own bounds`() {
        assertEquals(intervalTenths(OverlayConfig.MIN_INTERVAL_MILLIS), INTERVAL_TENTHS.first)
        assertEquals(intervalTenths(OverlayConfig.MAX_INTERVAL_MILLIS), INTERVAL_TENTHS.last)
        assertEquals(5, INTERVAL_TENTHS.first)
        assertEquals(100, INTERVAL_TENTHS.last)
    }

    @Test
    fun `tenths and millis round-trip across the whole slider`() {
        INTERVAL_TENTHS.forEach { tenths ->
            assertEquals(tenths, intervalTenths(intervalMillis(tenths)))
        }
    }

    @Test
    fun `a tenths value past either end is clamped to the config's range`() {
        assertEquals(OverlayConfig.MIN_INTERVAL_MILLIS, intervalMillis(0))
        assertEquals(OverlayConfig.MIN_INTERVAL_MILLIS, intervalMillis(-30))
        assertEquals(OverlayConfig.MAX_INTERVAL_MILLIS, intervalMillis(1_000))
    }

    @Test
    fun `sub-second intervals are shown in milliseconds, because half a second is a choice`() {
        assertEquals("500 ms", intervalLabel(500L))
        assertEquals("900 ms", intervalLabel(900L))
    }

    @Test
    fun `a whole number of seconds drops the decimal`() {
        assertEquals("1 s", intervalLabel(1_000L))
        assertEquals("10 s", intervalLabel(10_000L))
        assertEquals("1 s", intervalLabel(OverlayConfig.DEFAULT_INTERVAL_MILLIS))
    }

    @Test
    fun `a fractional interval keeps one decimal place`() {
        assertEquals("1.5 s", intervalLabel(1_500L))
        assertEquals("2.5 s", intervalLabel(2_500L))
    }

    @Test
    fun `every position on the interval slider produces a label`() {
        INTERVAL_TENTHS.forEach { tenths ->
            val label = intervalLabel(intervalMillis(tenths))
            assertTrue(label, label.isNotBlank())
            assertTrue(label, label.endsWith(" s") || label.endsWith(" ms"))
        }
    }

    // ------------------------------------------------------------------------------ style summaries

    @Test
    fun `the pill style summary names every one of the four style settings`() {
        val summary = pillStyleSummary(
            OverlayConfig(textSizeSp = 14, opacityPercent = 70, cornerRadiusDp = 8, showLabels = true),
        )
        assertTrue(summary, summary.contains("14 sp"))
        assertTrue(summary, summary.contains("70%"))
        assertTrue(summary, summary.contains("8 dp"))
        assertTrue(summary, summary.contains("with labels"))
    }

    @Test
    fun `a vertical pill is described as a column and a horizontal one as a strip`() {
        assertTrue(pillStyleSummary(OverlayConfig(isVertical = true)).startsWith("A column"))
        assertTrue(pillStyleSummary(OverlayConfig(isVertical = false)).startsWith("A strip"))
    }

    @Test
    fun `labels off is stated rather than left out`() {
        val summary = pillStyleSummary(OverlayConfig(showLabels = false))
        assertTrue(summary, summary.contains("figures only"))
    }

    @Test
    fun `zero corner rounding is named as a square rather than reported as a figure`() {
        val summary = pillStyleSummary(OverlayConfig(cornerRadiusDp = 0))
        assertTrue(summary, summary.contains("square corners"))
        assertFalse(summary, summary.contains("0 dp"))
    }

    @Test
    fun `the pill style summary holds at both ends of every range`() {
        val low = OverlayConfig(
            textSizeSp = OverlayConfig.TEXT_SIZE_RANGE.first,
            opacityPercent = OverlayConfig.OPACITY_RANGE.first,
            cornerRadiusDp = OverlayConfig.CORNER_RANGE.first,
        ).normalised()
        val high = OverlayConfig(
            textSizeSp = OverlayConfig.TEXT_SIZE_RANGE.last,
            opacityPercent = OverlayConfig.OPACITY_RANGE.last,
            cornerRadiusDp = OverlayConfig.CORNER_RANGE.last,
        ).normalised()
        assertTrue(pillStyleSummary(low).contains("8 sp"))
        assertTrue(pillStyleSummary(low).contains("20%"))
        assertTrue(pillStyleSummary(high).contains("20 sp"))
        assertTrue(pillStyleSummary(high).contains("32 dp"))
    }

    @Test
    fun `the button size summary names the size and both opacities`() {
        val summary = buttonSizeSummary(
            FloatingButtonConfig(sizeDp = 60, opacityPercent = 90, idleOpacityPercent = 25),
        )
        assertTrue(summary, summary.contains("60 dp"))
        assertTrue(summary, summary.contains("90%"))
        assertTrue(summary, summary.contains("25%"))
    }

    @Test
    fun `the button size summary describes the defaults it ships with`() {
        val defaults = FloatingButtonConfig()
        val summary = buttonSizeSummary(defaults)
        assertTrue(summary, summary.contains("${defaults.sizeDp} dp"))
        assertTrue(summary, summary.contains("${defaults.idleOpacityPercent}%"))
    }

    // ------------------------------------------------------------------------------- the panel width

    @Test
    fun `resetting a panel already at the default is offered as nothing to do`() {
        assertNull(panelWidthResetNote(FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP))
    }

    @Test
    fun `a changed panel width gets a note naming the width it goes back to`() {
        val note = panelWidthResetNote(FloatingButtonConfig.MAX_PANEL_WIDTH_DP)
        assertTrue(note.orEmpty(), note!!.contains("${FloatingButtonConfig.DEFAULT_PANEL_WIDTH_DP} dp"))
    }

    // ----------------------------------------------------------------------------------- the stats

    @Test
    fun `an empty pill's subtitle offers the room it has rather than a count of zero`() {
        val subtitle = pillStatsSubtitle(0)
        assertFalse(subtitle, subtitle.startsWith("0 of"))
        assertTrue(subtitle, subtitle.contains("${OverlayConfig.MAX_STATS}"))
    }

    @Test
    fun `the stats subtitle counts against the model's own maximum`() {
        assertEquals("4 of ${OverlayConfig.MAX_STATS}, in drawing order", pillStatsSubtitle(4))
        assertEquals(
            "${OverlayConfig.MAX_STATS} of ${OverlayConfig.MAX_STATS}, in drawing order",
            pillStatsSubtitle(OverlayConfig.MAX_STATS),
        )
    }

    @Test
    fun `the default stat set fits the pill's allowance`() {
        assertTrue(HudStat.DEFAULT_SET.size <= OverlayConfig.MAX_STATS)
        assertEquals(
            "${HudStat.DEFAULT_SET.size} of ${OverlayConfig.MAX_STATS}, in drawing order",
            pillStatsSubtitle(OverlayConfig().normalised().stats.size),
        )
    }

    // ---------------------------------------------------------------------------- the live preview

    @Test
    fun `the preview sentence says it is a preview, so a reading heard alone is not mistaken for live`() {
        val sentence = pillPreviewSentence(listOf("CPU 42%", "RAM 61%"))
        assertTrue(sentence.orEmpty(), sentence!!.lowercase().contains("preview"))
        assertTrue(sentence, sentence.contains("CPU 42%"))
        assertTrue(sentence, sentence.contains("RAM 61%"))
    }

    @Test
    fun `an empty pill has no preview sentence rather than an empty one`() {
        assertNull(pillPreviewSentence(emptyList()))
    }

    @Test
    fun `the preview caption says the readings are real when every stat reads`() {
        val note = pillPreviewNote(statCount = 4, absentCount = 0)
        assertTrue(note, note.contains("Live readings"))
        assertFalse(note, note.contains("placeholder"))
    }

    @Test
    fun `the preview caption counts the stats this device cannot read`() {
        val note = pillPreviewNote(statCount = 5, absentCount = 2)
        assertTrue(note, note.contains("2 of 5"))
        assertTrue(note, note.contains("placeholder"))
    }

    @Test
    fun `a pill where nothing reads says so rather than counting all of them`() {
        val note = pillPreviewNote(statCount = 3, absentCount = 3)
        assertTrue(note, note.contains("None of them"))
    }

    @Test
    fun `an empty pill's caption says there is nothing to preview`() {
        val note = pillPreviewNote(statCount = 0, absentCount = 0)
        assertTrue(note, note.contains("nothing to preview"))
    }

    @Test
    fun `absent stats are named rather than counted, because taking them off is the user's job`() {
        val note = absentStatsNote(
            labels = listOf("frame rate", "latency"),
            placeholder = StatReading.PLACEHOLDER,
        )
        assertTrue(note.orEmpty(), note!!.contains("frame rate and latency"))
        assertTrue(note, note.contains(StatReading.PLACEHOLDER))
    }

    @Test
    fun `no absent stats means no banner at all`() {
        assertNull(absentStatsNote(emptyList(), StatReading.PLACEHOLDER))
    }

    @Test
    fun `a list of words reads as a sentence rather than as a log line`() {
        assertEquals("", joinWords(emptyList()))
        assertEquals("cpu", joinWords(listOf("cpu")))
        assertEquals("cpu and ram", joinWords(listOf("cpu", "ram")))
        assertEquals("cpu, ram and battery", joinWords(listOf("cpu", "ram", "battery")))
    }

    // ------------------------------------------------------------------------------- the quick apps

    @Test
    fun `an empty quick-launch row is an absence rather than a count of zero`() {
        assertEquals("None chosen", quickAppsSummary(0))
    }

    @Test
    fun `a partly filled quick-launch row counts against the model's own maximum`() {
        assertEquals("3 of ${FloatingButtonConfig.MAX_QUICK_APPS}", quickAppsSummary(3))
    }

    @Test
    fun `a full quick-launch row says it is full, because the next tap would do nothing`() {
        val summary = quickAppsSummary(FloatingButtonConfig.MAX_QUICK_APPS)
        assertTrue(summary, summary.contains("full"))
    }

    // ------------------------------------------------------------------------- slider bounds in words

    @Test
    fun `a slider's range is stated in the unit the value is in`() {
        assertEquals("8 to 20 sp", sliderRange(OverlayConfig.TEXT_SIZE_RANGE, "sp"))
        assertEquals("0 to 32 dp", sliderRange(OverlayConfig.CORNER_RANGE, "dp"))
        assertEquals("36 to 72", sliderRange(FloatingButtonConfig.SIZE_RANGE))
    }

    @Test
    fun `a percent sign binds to its figure at both ends of the range`() {
        assertEquals("20% to 100%", sliderRange(OverlayConfig.OPACITY_RANGE, "%"))
        assertEquals("10% to 100%", sliderRange(FloatingButtonConfig.IDLE_OPACITY_RANGE, "%"))
    }

    @Test
    fun `a slider's description carries its bounds, so the value has a scale to be read against`() {
        val description = sliderDescription(
            "How solid it is while the panel is open.",
            FloatingButtonConfig.OPACITY_RANGE,
            "%",
        )
        assertTrue(description, description.startsWith("How solid it is"))
        assertTrue(description, description.contains("30% to 100%"))
    }

    @Test
    fun `the ranges the sliders span are the model's own, not numbers retyped in the UI`() {
        // The model's KDoc warns that a range typed a second time is a slider whose top end clamps
        // somewhere else. These are the four the pill card spans and the four the button card does.
        assertEquals(8..20, OverlayConfig.TEXT_SIZE_RANGE)
        assertEquals(20..100, OverlayConfig.OPACITY_RANGE)
        assertEquals(0..32, OverlayConfig.CORNER_RANGE)
        assertEquals(36..72, FloatingButtonConfig.SIZE_RANGE)
        assertEquals(30..100, FloatingButtonConfig.OPACITY_RANGE)
        assertEquals(10..100, FloatingButtonConfig.IDLE_OPACITY_RANGE)
        assertEquals(144..480, FloatingButtonConfig.PANEL_WIDTH_RANGE)
    }
}
