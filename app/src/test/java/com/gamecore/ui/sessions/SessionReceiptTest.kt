package com.gamecore.ui.sessions

import com.gamecore.ui.components.Readout
import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.SessionSample
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receipt model, built from the already-formatted screen state.
 *
 * The renderer's pixels are not tested here — this is the data that feeds the renderer, and if the
 * data is right the bitmap will be right. The tests cover: the right headline/figures propagate,
 * the event list is time-ordered and capped at eight, and the empty/pre-timeline cases are honest.
 */
class SessionReceiptTest {

    private val sample = SessionSample(
        sessionId = 7L,
        elapsedMillis = 0L,
        cpuPercent = null,
        memoryPercent = null,
        batteryPercent = null,
        temperatureDeciCelsius = null,
        refreshRate = null,
        frameRate = null,
        latencyMillis = null,
    )

    private fun readout(label: String, value: String, detail: String? = null): Readout = Readout(
        label = label,
        value = value,
        detail = detail,
        tone = Tone.Neutral,
    )

    private fun event(kind: SessionEventKind, elapsed: Long, detail: String? = null): SessionEvent = SessionEvent(
        sessionId = 7L,
        elapsedMillis = elapsed,
        kind = kind,
        detail = detail,
    )

    @Test
    fun `headline and figures propagate to the receipt`() {
        val state = SessionReportUiState(
            title = "Test Game",
            subtitle = "Today · 14:30 – 15:05",
            headline = listOf(readout("Duration", "35m")),
            figures = listOf(
                readout("Battery drain", "5.2% / hour"),
                readout("Processor use", "42%"),
            ),
        )
        val detail = SessionReportDetail(samples = listOf(sample), events = listOf(event(SessionEventKind.PROFILE_APPLIED, 0L)))

        val receipt = buildReceipt(state, detail)

        assertEquals("Test Game", receipt.gameTitle)
        assertEquals("Today · 14:30 – 15:05", receipt.subtitle)
        assertEquals(1, receipt.headline.size)
        assertEquals("Duration", receipt.headline[0].label)
        assertEquals(2, receipt.figures.size)
        assertEquals("Battery drain", receipt.figures[0].label)
        assertEquals("Processor use", receipt.figures[1].label)
    }

    @Test
    fun `events are time-ordered and capped at eight`() {
        val state = SessionReportUiState(
            title = "Game",
            subtitle = "Sub",
            headline = emptyList(),
            figures = emptyList(),
        )
        val events = (1..10).map { i -> event(SessionEventKind.FRAME_OUTLIER, i * 10_000L.toLong()) }
        val detail = SessionReportDetail(samples = emptyList(), events = events)

        val receipt = buildReceipt(state, detail)

        assertEquals(SessionReceipt.MAX_EVENTS, receipt.topEvents.size)
        assertEquals(10_000L, receipt.topEvents[0].elapsedMillis)
        assertEquals(80_000L, receipt.topEvents.last().elapsedMillis)
    }

    @Test
    fun `only events up to the cap are kept when there are more`() {
        val state = SessionReportUiState(title = "G", subtitle = "S", headline = emptyList(), figures = emptyList())
        val events = (1..12).map { i -> event(SessionEventKind.FRAME_OUTLIER, i * 10_000L.toLong()) }
        val detail = SessionReportDetail(samples = emptyList(), events = events)

        val receipt = buildReceipt(state, detail)

        assertEquals(8, receipt.topEvents.size)
        assertEquals(10_000L, receipt.topEvents[0].elapsedMillis)
        assertEquals(80_000L, receipt.topEvents.last().elapsedMillis)
    }

    @Test
    fun `absent figures are not omitted — the screen decides what is absent`() {
        val state = SessionReportUiState(
            title = "G", subtitle = "S",
            headline = emptyList(),
            figures = listOf(
                readout("Processor use", "42%"),
                readout("Memory in use", ABSENT),
            ),
        )
        val detail = SessionReportDetail(samples = emptyList(), events = emptyList())

        val receipt = buildReceipt(state, detail)

        // The receipt shows what the screen shows; the screen already replaced absent with the reason.
        assertEquals(2, receipt.figures.size)
        assertEquals(ABSENT, receipt.figures[1].value)
    }

    @Test
    fun `empty timeline produces empty topEvents and honest provenance`() {
        val state = SessionReportUiState(title = "G", subtitle = "S", headline = emptyList(), figures = emptyList())
        val detail = SessionReportDetail(samples = listOf(sample), events = emptyList())

        val receipt = buildReceipt(state, detail)

        assertTrue(receipt.topEvents.isEmpty())
        assertTrue(receipt.provenance.contains("GameCore"))
    }
}