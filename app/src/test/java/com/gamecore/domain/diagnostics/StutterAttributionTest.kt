package com.gamecore.domain.diagnostics

import com.gamecore.core.model.FrameRateSample
import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import com.gamecore.core.model.SessionSample
import com.gamecore.core.model.ThermalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attribution rules, tested without a device, a database or a clock.
 *
 * Every case is a rule from [StutterAttribution] expressed as input -> output. The windows and
 * confidence thresholds are the product, so changing one is a spec change, not a refactoring.
 */
class StutterAttributionTest {

    private fun sample(
        elapsedMillis: Long,
        cpuPercent: Float? = null,
        temperature: Int? = null,
        frameRate: FrameRateSample? = null,
    ): SessionSample = SessionSample(
        sessionId = 7L,
        elapsedMillis = elapsedMillis,
        cpuPercent = cpuPercent,
        memoryPercent = null,
        batteryPercent = null,
        temperatureDeciCelsius = temperature,
        refreshRate = null,
        frameRate = frameRate?.averageFps,
        latencyMillis = null,
    )

    private fun frame(p99: Float? = null, jankFrames: Int = 0, frameCount: Int = 100, onePercentLow: Float? = null): FrameRateSample =
        FrameRateSample(
            averageFps = 60f,
            onePercentLowFps = onePercentLow,
            frameCount = frameCount,
            windowMillis = 1_000L,
            jankFrames = jankFrames,
            p95FrameMillis = p99?.let { it * 0.8f },
            p99FrameMillis = p99,
        )

    private fun event(
        kind: SessionEventKind,
        elapsedMillis: Long,
        detail: String? = null,
        value: Double? = null,
    ): SessionEvent = SessionEvent(
        sessionId = 7L,
        elapsedMillis = elapsedMillis,
        kind = kind,
        detail = detail,
        value = value,
    )

    @Test
    fun `a thermal warning before a spike produces a thermal cause with evidence`() {
        val samples = listOf(sample(55_000L, cpuPercent = 30f, temperature = 400))
        val events = listOf(
            event(SessionEventKind.THERMAL_WARNING, 50_000L),
            event(SessionEventKind.FRAME_OUTLIER, 58_000L, value = 68.0),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertEquals(1, report.findings.size)
        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.THERMAL, causes.single().kind)
        assertEquals(StutterConfidence.MEDIUM, causes.single().confidence)
        assertTrue(causes.single().evidence.contains("throttling"))
        assertTrue(causes.single().evidence.contains("8 s"))
    }

    @Test
    fun `a thermal warning too far back does not explain the spike`() {
        val samples = listOf(sample(120_000L))
        val events = listOf(
            event(SessionEventKind.THERMAL_WARNING, 10_000L),
            event(SessionEventKind.FRAME_OUTLIER, 120_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertEquals(1, report.findings.size)
        assertTrue(report.findings.single().causes.none { it.kind == StutterCauseKind.THERMAL })
    }

    @Test
    fun `a thermal recovery before the spike clears the cause`() {
        val samples = listOf(sample(80_000L))
        val events = listOf(
            event(SessionEventKind.THERMAL_WARNING, 40_000L),
            event(SessionEventKind.THERMAL_RECOVERED, 60_000L),
            event(SessionEventKind.FRAME_OUTLIER, 80_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.single().causes.none { it.kind == StutterCauseKind.THERMAL })
    }

    @Test
    fun `a refresh cap still active at the spike produces a LOW confidence cause`() {
        val samples = listOf(sample(35_000L))
        val events = listOf(
            event(SessionEventKind.REFRESH_CAPPED, 30_000L),
            event(SessionEventKind.FRAME_OUTLIER, 35_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertEquals(1, report.findings.size)
        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.REFRESH_CAP, causes.single().kind)
        assertEquals(StutterConfidence.LOW, causes.single().confidence)
    }

    @Test
    fun `a refresh cap restored before the spike is not a cause`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(
            event(SessionEventKind.REFRESH_CAPPED, 30_000L),
            event(SessionEventKind.REFRESH_RESTORED, 40_000L),
            event(SessionEventKind.FRAME_OUTLIER, 50_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.single().causes.none { it.kind == StutterCauseKind.REFRESH_CAP })
    }

    @Test
    fun `power saver on before the spike produces a cause`() {
        val samples = listOf(sample(25_000L))
        val events = listOf(
            event(SessionEventKind.POWER_SAVER_ON, 10_000L),
            event(SessionEventKind.FRAME_OUTLIER, 25_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.POWER_SAVER, causes.single().kind)
    }

    @Test
    fun `a failed profile apply at launch produces a cause`() {
        val samples = listOf(sample(100_000L))
        val events = listOf(
            event(SessionEventKind.PROFILE_APPLY_FAILED, 0L),
            event(SessionEventKind.FRAME_OUTLIER, 100_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.PROFILE_FAILED, causes.single().kind)
    }

    @Test
    fun `a network spike near the frame spike produces a network cause`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(
            event(SessionEventKind.NETWORK_SPIKE, 48_000L, detail = "upstream"),
            event(SessionEventKind.FRAME_OUTLIER, 50_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.NETWORK, causes.single().kind)
        assertEquals(StutterConfidence.MEDIUM, causes.single().confidence)
    }

    @Test
    fun `a network spike too far away is not a cause`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(
            event(SessionEventKind.NETWORK_SPIKE, 20_000L),
            event(SessionEventKind.FRAME_OUTLIER, 50_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.single().causes.none { it.kind == StutterCauseKind.NETWORK })
    }

    @Test
    fun `no network events means no network cause`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(
            event(SessionEventKind.FRAME_OUTLIER, 50_000L),
        )

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.single().causes.none { it.kind == StutterCauseKind.NETWORK })
    }

    @Test
    fun `high cpu load produces a LOW confidence CPU cause`() {
        val samples = listOf(sample(50_000L, cpuPercent = 90f))
        val events = listOf(event(SessionEventKind.FRAME_OUTLIER, 50_000L))

        val report = StutterAttribution.attribute(samples, events)

        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.CPU_LOAD, causes.single().kind)
        assertEquals(StutterConfidence.LOW, causes.single().confidence)
    }

    @Test
    fun `high temperature produces a LOW confidence temperature cause`() {
        val samples = listOf(sample(50_000L, temperature = 460))
        val events = listOf(event(SessionEventKind.FRAME_OUTLIER, 50_000L))

        val report = StutterAttribution.attribute(samples, events)

        val causes = report.findings.single().causes
        assertEquals(1, causes.size)
        assertEquals(StutterCauseKind.TEMPERATURE, causes.single().kind)
        assertEquals(StutterConfidence.LOW, causes.single().confidence)
    }

    @Test
    fun `no matching events produces an unexplained finding`() {
        val samples = listOf(sample(50_000L, cpuPercent = 20f, temperature = 300))
        val events = listOf(event(SessionEventKind.FRAME_OUTLIER, 50_000L))

        val report = StutterAttribution.attribute(samples, events)

        assertEquals(1, report.findings.size)
        assertTrue(report.findings.single().isUnexplained)
    }

    @Test
    fun `no frame outliers at all returns an empty report`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(event(SessionEventKind.THERMAL_WARNING, 30_000L))

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.isEmpty())
        assertFalse(report.hasOutliers)
    }

    @Test
    fun `no frame timing at all marks hasFrameTiming false`() {
        val samples = listOf(sample(50_000L))
        val events = listOf(event(SessionEventKind.THERMAL_WARNING, 30_000L))

        val report = StutterAttribution.attribute(samples, events)

        assertFalse(report.hasFrameTiming)
    }

    @Test
    fun `empty inputs return empty report and honest absence text`() {
        val report = StutterAttribution.attribute(emptyList(), emptyList())

        assertTrue(report.findings.isEmpty())
        assertFalse(report.hasFrameTiming)
        val text = StutterAttribution.explainsAbsence(emptyList(), emptyList())
        assertTrue(text!!.contains("no reliable per-frame timing"))
    }

    @Test
    fun `session with frame timing but no outliers returns empty findings and an honest sentence`() {
        val samples = listOf(sample(50_000L, frameRate = frame()))
        val events = emptyList<SessionEvent>()

        val report = StutterAttribution.attribute(samples, events)

        assertTrue(report.findings.isEmpty())
        assertTrue(report.hasFrameTiming)
        val text = StutterAttribution.explainsAbsence(samples, events)
        assertTrue(text!!.contains("no frame-time spikes were detected"))
    }

    @Test
    fun `session predating the timeline returns empty findings and an honest sentence`() {
        val samples = listOf(sample(50_000L, frameRate = frame()))
        val events = emptyList<SessionEvent>()

        val report = StutterAttribution.attribute(samples, events)

        val text = StutterAttribution.explainsAbsence(samples, events)
        assertTrue(text!!.contains("recorded before GameCore kept"))
    }

    @Test
    fun `only the worst five outliers are kept`() {
        val samples = List(20) { i -> sample((i + 1) * 10_000L) }
        val events = (0..19).map { i -> event(SessionEventKind.FRAME_OUTLIER, (i + 1) * 10_000L.toLong(), value = (50 + i * 2).toDouble()) }

        val report = StutterAttribution.attribute(samples, events)

        assertEquals(StutterAttribution.MAX_FINDINGS, report.findings.size)
        // The worst five (last five in elapsed order because they are already sorted by time)
        assertEquals(70_000L, report.findings[0].elapsedMillis)
        assertEquals(110_000L, report.findings.last().elapsedMillis)
    }
}