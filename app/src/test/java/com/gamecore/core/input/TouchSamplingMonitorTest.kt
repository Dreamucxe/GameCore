package com.gamecore.core.input

import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.common.RestrictionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn a session of delivered touch samples into the Touch Sampling Monitor's claims.
 *
 * Pinned in a unit test because every figure here is a different kind of statement and the screen's whole
 * value is that it does not blur them. A delivered rate is a throughput and must not be reported over a
 * fraction of a second; an observed sample rate is a cadence that only exists once the framework batches
 * sub-frame samples, and is honestly withheld otherwise; and a hardware touch latency is a number Android
 * simply does not expose, so it must never resolve to one. A regression in any of these would be invisible
 * on a device and would show up as a confident, wrong sentence about someone's input hardware.
 */
class TouchSamplingMonitorTest {

    @Test
    fun `an empty monitor claims no figure and never a latency number`() {
        val monitor = TouchSamplingMonitor.EMPTY
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, monitor.deliveredEventRatePerSecond)
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, monitor.observedSampleRateHz)
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, monitor.inputProcessingDelayMillis)
        assertRestricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, monitor.hardwareTouchLatency)
    }

    @Test
    fun `the delivered rate is the sample count over the capture window`() {
        // Five samples spanning exactly one second: 5 samples / 1 s = 5 per second, regardless of how the
        // framework happened to batch them.
        val rate = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 250, 500, 750, 1_000), nowMillis = 1_000)
            .deliveredEventRatePerSecond
        assertEquals(5f, value(rate), 0.001f)
        assertEquals(Precision.SAMPLED, (rate as Observed.Value<Float>).precision)
    }

    @Test
    fun `no delivered rate is claimed before a full second of capture`() {
        val rate = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 250, 500, 750), nowMillis = 750)
            .deliveredEventRatePerSecond
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, rate)
    }

    @Test
    fun `the delivered-rate window opens at exactly one second, not a millisecond before`() {
        // 999 ms of span is still short of the floor and claims nothing.
        val justUnder = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 999), nowMillis = 999)
            .deliveredEventRatePerSecond
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, justUnder)

        // Exactly 1000 ms is the boundary and does report.
        val atBoundary = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 1_000), nowMillis = 1_000)
            .deliveredEventRatePerSecond
        assertEquals(2f, value(atBoundary), 0.001f)
    }

    @Test
    fun `the observed sample rate is the cadence the batched timestamps imply`() {
        // One event carrying five sub-frame samples 4 ms apart: four gaps of 4 ms, a mean of 4 ms, so a
        // cadence of 250 Hz. This is what "the framework batched samples" looks like in the data.
        val hz = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 4, 8, 12, 16), nowMillis = 16)
            .observedSampleRateHz
        assertEquals(250f, value(hz), 0.001f)
        assertEquals(Precision.SAMPLED, (hz as Observed.Value<Float>).precision)
    }

    @Test
    fun `the observed sample rate is withheld for movement rather than guessed at rest`() {
        // A single-sample event has moved but not enough events have gone by to conclude the device never
        // batches, so it asks for movement rather than declaring the rate absent.
        val early = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0), nowMillis = 0)
            .observedSampleRateHz
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, early)
    }

    @Test
    fun `a device that never batches samples cannot observe a sample rate`() {
        // One sample per delivered event, over enough events to judge: the timestamps only reveal the
        // delivery rate, so a sub-frame sampling rate is honestly reported as absent, not invented.
        var monitor = TouchSamplingMonitor.EMPTY
        repeat(TouchSamplingMonitor.MIN_EVENTS_TO_JUDGE_BATCHING) { i ->
            val t = i * 16L
            monitor = monitor.record(longArrayOf(t), nowMillis = t)
        }
        assertRestricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, monitor.observedSampleRateHz)
    }

    @Test
    fun `no-batching is not declared until enough events have been seen to judge it`() {
        // One event short of the threshold is still "keep moving", not "not available" — the difference
        // between an early frame and a device that genuinely delivers one sample per event.
        var monitor = TouchSamplingMonitor.EMPTY
        repeat(TouchSamplingMonitor.MIN_EVENTS_TO_JUDGE_BATCHING - 1) { i ->
            val t = i * 16L
            monitor = monitor.record(longArrayOf(t), nowMillis = t)
        }
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, monitor.observedSampleRateHz)
    }

    @Test
    fun `a finger held still falls back to asking for movement`() {
        // Batched samples were seen, so a rate was measurable; then a clock tick with no new sample lands
        // well past the idle gap and the reading steps back to "move to measure" rather than holding a
        // stale cadence.
        val moving = TouchSamplingMonitor.EMPTY.record(longArrayOf(0, 4, 8, 12, 16), nowMillis = 16)
        assertTrue(moving.observedSampleRateHz is Observed.Value)

        val idle = moving.record(longArrayOf(), nowMillis = 16 + TouchSamplingMonitor.IDLE_GAP_MILLIS + 50)
        assertRestricted(RestrictionReason.AWAITING_SECOND_SAMPLE, idle.observedSampleRateHz)
    }

    @Test
    fun `hardware touch latency is always restricted and never resolves to a figure`() {
        // The refusal must survive any amount of real data — there is no sample sequence that unlocks a
        // touch-to-photon number, because the framework never exposes the physical touch moment.
        val busy = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 4, 8, 12, 16), nowMillis = 20)
            .record(longArrayOf(20, 24, 28), nowMillis = 30)
        assertFalse(busy.hardwareTouchLatency is Observed.Value)
        assertRestricted(RestrictionReason.NOT_PRESENT_ON_DEVICE, busy.hardwareTouchLatency)
        // The wording is the point, so it is asserted rather than left to a reviewer: it must deny a
        // touch-to-photon claim, not report one.
        val detail = (busy.hardwareTouchLatency as Observed.Restricted).detail
        assertTrue(detail.contains("touch-to-photon"))
        assertTrue(detail.contains("framework"))
    }

    @Test
    fun `input processing delay is an estimate of now minus the sample timestamp`() {
        // The newest sample is timestamped 10; it was processed at 25, so the queue delay estimate is
        // 15 ms — labelled an estimate, and never dressed up as latency.
        val delay = TouchSamplingMonitor.EMPTY
            .record(longArrayOf(0, 10), nowMillis = 25)
            .inputProcessingDelayMillis
        assertEquals(15f, value(delay), 0.001f)
        assertEquals(Precision.ESTIMATED, (delay as Observed.Value<Float>).precision)
    }

    private fun value(observed: Observed<Float>): Float {
        assertTrue("expected a real reading but was $observed", observed is Observed.Value)
        return (observed as Observed.Value<Float>).value
    }

    private fun assertRestricted(reason: RestrictionReason, observed: Observed<*>) {
        assertTrue("expected Restricted but was $observed", observed is Observed.Restricted)
        assertEquals(reason, (observed as Observed.Restricted).reason)
    }
}
