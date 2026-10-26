package com.gamecore.core.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickDriftAnalyzerTest {

    private val thresholds = DriftThresholds(offset = 0.08f, jitter = 0.02f, minSamples = 20)

    @Test
    fun `an empty run is not enough data, not stable`() {
        val result = StickDriftAnalyzer.analyze(emptyList(), 10_000L, thresholds)

        assertEquals(DriftVerdict.NOT_ENOUGH_DATA, result.verdict)
        assertEquals(0, result.sampleCount)
        assertFalse(result.isConclusive)
    }

    @Test
    fun `a quiet centred run is stable`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> noise(i) to noise(i + 1) },
            requestedDurationMillis = 6_000L,
            thresholds = thresholds,
        )

        assertEquals(DriftVerdict.STABLE, result.verdict)
        assertEquals(300, result.sampleCount)
        assertTrue(result.offsetMagnitude < 0.005f)
        assertTrue(result.jitter < 0.005f)
        assertEquals(100f, result.stabilityPercent, 0.5f)
        assertTrue(result.isConclusive)
    }

    @Test
    fun `a resting mean outside the threshold is a drift verdict`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> 0.12f + noise(i) to noise(i + 1) },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.DRIFT, result.verdict)
        assertEquals(0.12f, result.offsetMagnitude, 0.002f)
        assertTrue(result.detail.contains("exceeds"))
    }

    @Test
    fun `a centred but restless stick is unstable rather than drifting`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> (if (i % 2 == 0) 0.04f else -0.04f) to 0f },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.UNSTABLE, result.verdict)
        assertTrue(result.jitter > thresholds.jitter)
        assertTrue(result.offsetMagnitude < thresholds.offset)
    }

    @Test
    fun `a deliberate push invalidates the run instead of being called drift`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> (if (i % 3 == 0) 0.8f else 0f) to 0f },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.MOVED, result.verdict)
        assertEquals(300, result.sampleCount)
        assertFalse(result.isConclusive)
    }

    @Test
    fun `one isolated spike is tolerated as a dropped sample`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> (if (i == 150) 0.9f else 0.001f) to 0f },
            6_000L,
            thresholds.copy(jitter = 0.2f),
        )

        assertFalse("one sample is not a push", result.verdict == DriftVerdict.MOVED)
    }

    @Test
    fun `too few samples is inconclusive even over a long span`() {
        val result = StickDriftAnalyzer.analyze(
            samples(10, interval = 500L) { 0f to 0f },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.NOT_ENOUGH_DATA, result.verdict)
    }

    @Test
    fun `a run that lost most of its duration is inconclusive`() {
        val result = StickDriftAnalyzer.analyze(
            samples(30, interval = 20L) { 0f to 0f },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.NOT_ENOUGH_DATA, result.verdict)
        assertTrue(result.detail.contains("readings"))
    }

    @Test
    fun `the device flat radius is a floor on the offset threshold`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { 0.09f to 0f },
            6_000L,
            thresholds.copy(offset = 0.05f),
            declaredFlat = 0.10f,
        )

        assertEquals("inside the driver's own dead zone", DriftVerdict.STABLE, result.verdict)
        assertEquals(0.10f, result.effectiveOffset, 0.0001f)
    }

    @Test
    fun `the device fuzz is a floor on the jitter threshold`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { i -> (if (i % 2 == 0) 0.01f else -0.01f) to 0f },
            6_000L,
            thresholds.copy(jitter = 0.005f),
            declaredFuzz = 0.02f,
        )

        assertEquals(DriftVerdict.STABLE, result.verdict)
        assertEquals(0.02f, result.effectiveJitter, 0.0001f)
    }

    @Test
    fun `a reading exactly on the threshold is inside it`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { 0.08f to 0f },
            6_000L,
            thresholds,
        )

        assertEquals(DriftVerdict.STABLE, result.verdict)
        assertEquals(0.08f, result.offsetMagnitude, 0.0001f)
    }

    @Test
    fun `samples are ordered by time before the span is measured`() {
        val stable = samples(300) { 0f to 0f }
        val shuffled = stable.sortedByDescending { it.timestampMillis }
        val result = StickDriftAnalyzer.analyze(shuffled, 6_000L, thresholds)

        assertEquals(DriftVerdict.STABLE, result.verdict)
        assertEquals(5_980L, result.spanMillis)
    }

    @Test
    fun `non-finite readings are discarded rather than analysed`() {
        val poisoned = samples(300) { i -> if (i == 4) Float.NaN to 0f else 0f to 0f }
        val result = StickDriftAnalyzer.analyze(poisoned, 6_000L, thresholds)

        assertEquals(299, result.sampleCount)
        assertEquals(DriftVerdict.STABLE, result.verdict)
    }

    @Test
    fun `the applied thresholds are reported so a result can be reproduced`() {
        val result = StickDriftAnalyzer.analyze(
            samples(300) { 0f to 0f },
            6_000L,
            thresholds.copy(offset = 0.12f, jitter = 0.03f),
            declaredFlat = 0.04f,
            declaredFuzz = 0.01f,
        )

        assertEquals(0.12f, result.effectiveOffset, 0.0001f)
        assertEquals(0.03f, result.effectiveJitter, 0.0001f)
        assertEquals(0.04f, result.declaredFlat, 0.0001f)
        assertEquals(0.01f, result.declaredFuzz, 0.0001f)
        assertEquals(0.12f, result.configuredOffset, 0.0001f)
        assertEquals(0.03f, result.configuredJitter, 0.0001f)
    }

    private fun noise(index: Int): Float = ((index % 5) - 2) * 0.001f

    private fun samples(
        count: Int,
        interval: Long = 20L,
        value: (Int) -> Pair<Float, Float>,
    ): List<DriftSample> = (0 until count).map { index ->
        val (x, y) = value(index)
        DriftSample(timestampMillis = index * interval, x = x, y = y)
    }
}
