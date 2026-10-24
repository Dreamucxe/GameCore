package com.gamecore.domain.gaming

import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event rules, tested without a device, a database or a clock.
 *
 * Every assertion is about a transition or a refusal to emit one. The tracker's whole job is to keep
 * the timeline small and meaningful, so "no event" is as important a result as "an event", and each
 * case below says which reading is supposed to be silent and why.
 */
class SessionConditionTrackerTest {

    private fun tracker() = SessionConditionTracker()

    private fun frame(
        p99: Float? = null,
        jankFrames: Int = 0,
        frameCount: Int = 100,
        onePercentLow: Float? = null,
    ) = FrameRateSample(
        averageFps = 60f,
        onePercentLowFps = onePercentLow,
        frameCount = frameCount,
        windowMillis = 1_000L,
        jankFrames = jankFrames,
        p95FrameMillis = p99?.let { it * 0.8f },
        p99FrameMillis = p99,
    )

    @Test
    fun `a session that opens already throttled records a warning at its start`() {
        val events = tracker().eventsFor(
            sessionId = 7L,
            elapsedMillis = 0L,
            thermalStatus = ThermalStatus.SEVERE,
            refreshCapped = null,
            powerSaver = false,
            frameRate = null,
        )

        assertEquals(1, events.size)
        assertEquals(SessionEventKind.THERMAL_WARNING, events.single().kind)
        assertEquals(7L, events.single().sessionId)
        assertEquals(ThermalStatus.SEVERE.level.toDouble(), events.single().value)
    }

    @Test
    fun `a normal opening records nothing`() {
        val events = tracker().eventsFor(
            sessionId = 7L,
            elapsedMillis = 0L,
            thermalStatus = ThermalStatus.NONE,
            refreshCapped = false,
            powerSaver = false,
            frameRate = null,
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `thermal warning and recovery are emitted on the transitions, not every sample`() {
        val tracker = tracker()

        val opening = tracker.eventsFor(7L, 0L, ThermalStatus.NONE, null, false, null)
        val warning = tracker.eventsFor(7L, 60_000L, ThermalStatus.SEVERE, null, false, null)
        val stillHot = tracker.eventsFor(7L, 62_000L, ThermalStatus.SEVERE, null, false, null)
        val recovered = tracker.eventsFor(7L, 120_000L, ThermalStatus.LIGHT, null, false, null)
        val stillCool = tracker.eventsFor(7L, 122_000L, ThermalStatus.LIGHT, null, false, null)

        assertTrue(opening.isEmpty())
        assertEquals(SessionEventKind.THERMAL_WARNING, warning.single().kind)
        assertTrue(stillHot.isEmpty())
        assertEquals(SessionEventKind.THERMAL_RECOVERED, recovered.single().kind)
        assertTrue(stillCool.isEmpty())
    }

    @Test
    fun `refresh cap is emitted once per transition in each direction`() {
        val tracker = tracker()

        tracker.eventsFor(7L, 0L, null, false, false, null)
        val capped = tracker.eventsFor(7L, 30_000L, null, true, false, null)
        val stillCapped = tracker.eventsFor(7L, 32_000L, null, true, false, null)
        val restored = tracker.eventsFor(7L, 90_000L, null, false, false, null)

        assertEquals(SessionEventKind.REFRESH_CAPPED, capped.single().kind)
        assertTrue(stillCapped.isEmpty())
        assertEquals(SessionEventKind.REFRESH_RESTORED, restored.single().kind)
    }

    @Test
    fun `the opening refresh state is only recorded by the session row, not as an event`() {
        val events = tracker().eventsFor(7L, 0L, null, true, false, null)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `power saver changes are recorded, and its opening state is not`() {
        val tracker = tracker()

        val opening = tracker.eventsFor(7L, 0L, null, null, true, null)
        val off = tracker.eventsFor(7L, 10_000L, null, null, false, null)
        val on = tracker.eventsFor(7L, 20_000L, null, null, true, null)

        assertTrue(opening.isEmpty())
        assertEquals(SessionEventKind.POWER_SAVER_OFF, off.single().kind)
        assertEquals(SessionEventKind.POWER_SAVER_ON, on.single().kind)
    }

    @Test
    fun `a bad p99 tail is a frame outlier with the duration as its value`() {
        val event = tracker().eventsFor(
            sessionId = 7L,
            elapsedMillis = 15_000L,
            thermalStatus = null,
            refreshCapped = null,
            powerSaver = false,
            frameRate = frame(p99 = 68f, jankFrames = 22, onePercentLow = 21f),
        ).single()

        assertEquals(SessionEventKind.FRAME_OUTLIER, event.kind)
        assertEquals(68.0, event.value!!, 0.01)
        assertTrue(event.detail!!.contains("p99 68 ms"))
        assertTrue(event.detail!!.contains("22% jank"))
    }

    @Test
    fun `a high jank share is an outlier even when p99 is absent`() {
        val event = tracker().eventsFor(
            sessionId = 7L,
            elapsedMillis = 15_000L,
            thermalStatus = null,
            refreshCapped = null,
            powerSaver = false,
            frameRate = frame(p99 = null, jankFrames = 20),
        ).single()

        assertEquals(SessionEventKind.FRAME_OUTLIER, event.kind)
    }

    @Test
    fun `an unreliable frame sample is ignored`() {
        val events = tracker().eventsFor(
            sessionId = 7L,
            elapsedMillis = 15_000L,
            thermalStatus = null,
            refreshCapped = null,
            powerSaver = false,
            frameRate = frame(p99 = 120f, jankFrames = 50, frameCount = 10),
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun `one bad minute produces one outlier, not one per sample`() {
        val tracker = tracker()

        val first = tracker.eventsFor(7L, 15_000L, null, null, false, frame(p99 = 68f))
        val second = tracker.eventsFor(7L, 17_000L, null, null, false, frame(p99 = 70f))
        val later = tracker.eventsFor(7L, 26_000L, null, null, false, frame(p99 = 72f))

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, later.size)
    }
}
