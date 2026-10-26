package com.gamecore.domain.diagnostics

import com.gamecore.core.model.SessionEvent
import com.gamecore.core.model.SessionEventKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network verdict's rules, as events in and a verdict out.
 *
 * No samples are needed: [NetworkAttribution] takes its stutter list from [StutterAttribution], which
 * reads the `FRAME_OUTLIER` events the condition tracker already writes. That keeps the two cards on
 * one number, and makes every case here a list of timestamps.
 *
 * The invariants the feature rests on: a spike must be *near* the outlier to count, both lanes
 * spiking is not localised, and an empty or quiet record never becomes a device verdict.
 */
class NetworkAttributionTest {

    private fun outlier(at: Long): SessionEvent =
        SessionEvent(sessionId = 1L, elapsedMillis = at, kind = SessionEventKind.FRAME_OUTLIER)

    private fun probe(at: Long, lane: String): SessionEvent =
        SessionEvent(sessionId = 1L, elapsedMillis = at, kind = SessionEventKind.NETWORK_PROBE, detail = lane)

    private fun spike(at: Long, lane: String, millis: Int = 220): SessionEvent =
        SessionEvent(
            sessionId = 1L,
            elapsedMillis = at,
            kind = SessionEventKind.NETWORK_SPIKE,
            detail = lane,
            value = millis.toDouble(),
        )

    // ─────────────────────────────────────────────────────────── the two empty cases

    @Test
    fun `a session with no probes cannot attribute anything`() {
        val report = NetworkAttribution.attribute(
            events = listOf(outlier(10_000L)),
        )

        assertEquals(NetworkVerdict.UNKNOWN, report.verdict)
        assertFalse(report.hasProbes)
        assertEquals(1, report.stutterCount)
        assertEquals(0, report.correlatedStutters)
    }

    @Test
    fun `a session with no stutters has nothing to explain`() {
        val report = NetworkAttribution.attribute(
            events = listOf(probe(10_000L, "upstream"), spike(10_000L, "upstream")),
        )

        assertEquals(NetworkVerdict.UNKNOWN, report.verdict)
        assertFalse(report.hasStutters)
        assertEquals(1, report.probeCount)
    }

    // ─────────────────────────────────────────────────────────────── the verdicts

    @Test
    fun `an upstream spike at the outlier is attributed beyond the device`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.UPSTREAM, report.verdict)
        assertEquals(1, report.correlatedStutters)
        assertEquals(1, report.upstreamSpikes)
        assertEquals(0, report.localSpikes)
    }

    @Test
    fun `a router spike at the outlier is attributed to the local link`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L, NetworkAttribution.LANE_LOCAL),
                spike(10_000L, NetworkAttribution.LANE_LOCAL),
            ),
        )

        assertEquals(NetworkVerdict.LOCAL_LINK, report.verdict)
        assertEquals(1, report.localSpikes)
    }

    @Test
    fun `spikes on both lanes are not localised`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L, NetworkAttribution.LANE_LOCAL),
                probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L, NetworkAttribution.LANE_LOCAL),
                spike(10_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.NETWORK_CORRELATED, report.verdict)
        assertEquals(1, report.correlatedStutters)
    }

    @Test
    fun `a spike outside the window is not evidence`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(17_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(17_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.UNKNOWN, report.verdict)
        assertEquals(0, report.correlatedStutters)
        assertEquals(1, report.upstreamSpikes)
    }

    @Test
    fun `the window boundary is inclusive at the documented distance`() {
        val at = NetworkAttribution.CORRELATION_WINDOW_MILLIS
        val inside = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L + at, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L + at, NetworkAttribution.LANE_UPSTREAM),
            ),
        )
        val outside = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L + at + 1, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L + at + 1, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.UPSTREAM, inside.verdict)
        assertEquals(NetworkVerdict.UNKNOWN, outside.verdict)
    }

    @Test
    fun `a spike before the outlier counts too`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(8_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(8_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.UPSTREAM, report.verdict)
    }

    @Test
    fun `only the spikes that line up are counted`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                outlier(30_000L),
                probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(NetworkVerdict.UPSTREAM, report.verdict)
        assertEquals(2, report.stutterCount)
        assertEquals(1, report.correlatedStutters)
    }

    @Test
    fun `every outlier is counted even when the attribution card only explains the worst five`() {
        val events = listOf(
            outlier(10_000L),
            outlier(20_000L),
            outlier(30_000L),
            outlier(40_000L),
            outlier(50_000L),
            outlier(60_000L),
            probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
            spike(10_000L, NetworkAttribution.LANE_UPSTREAM),
        )

        val report = NetworkAttribution.attribute(
            events = events,
        )

        assertEquals(6, report.stutterCount)
        assertEquals(1, report.correlatedStutters)
        assertEquals(NetworkVerdict.UPSTREAM, report.verdict)
    }

    @Test
    fun `an unlabelled spike is not counted on either lane`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
                SessionEvent(1L, 10_000L, SessionEventKind.NETWORK_SPIKE),
            ),
        )

        assertEquals(NetworkVerdict.UNKNOWN, report.verdict)
        assertEquals(0, report.upstreamSpikes)
        assertEquals(0, report.localSpikes)
    }

    @Test
    fun `lane matching is case-insensitive and tolerant of the recorder's casing`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(10_000L, "UPSTREAM"),
                spike(10_000L, "Upstream"),
            ),
        )

        assertEquals(NetworkVerdict.UPSTREAM, report.verdict)
    }

    @Test
    fun `probe readings on both lanes are counted`() {
        val report = NetworkAttribution.attribute(
            events = listOf(
                outlier(10_000L),
                probe(9_000L, NetworkAttribution.LANE_UPSTREAM),
                probe(10_000L, NetworkAttribution.LANE_LOCAL),
                probe(20_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertEquals(3, report.probeCount)
        assertTrue(report.hasLocalLane)
        assertTrue(report.hasUpstreamLane)
    }

    // ────────────────────────────────────────────────────────────── the sentences

    @Test
    fun `no probes and no stutters is said as nothing measured`() {
        val sentence = NetworkAttribution.explainsAbsence(emptyList(), emptyList())

        assertEquals(
            "This session has no frame-time outliers and no network probes in its record. That is " +
                "not the same as a clean network: nothing was measured to compare.",
            sentence,
        )
    }

    @Test
    fun `stutters without probes are said to have nothing to compare against`() {
        val sentence = NetworkAttribution.explainsAbsence(
            emptyList(),
            listOf(outlier(10_000L)),
        )

        assertTrue(sentence!!.contains("no network probes"))
        assertTrue(sentence.contains("nothing to compare"))
    }

    @Test
    fun `quiet probes with no stutters say so rather than claiming a clean network`() {
        val sentence = NetworkAttribution.explainsAbsence(
            emptyList(),
            listOf(probe(10_000L, NetworkAttribution.LANE_UPSTREAM)),
        )

        assertTrue(sentence!!.contains("No frame-time outliers"))
        assertTrue(sentence.contains("1 network probe"))
    }

    @Test
    fun `there is no absence sentence when there is something to draw`() {
        val sentence = NetworkAttribution.explainsAbsence(
            emptyList(),
            listOf(
                outlier(10_000L),
                probe(10_000L, NetworkAttribution.LANE_UPSTREAM),
                spike(10_000L, NetworkAttribution.LANE_UPSTREAM),
            ),
        )

        assertNull(sentence)
    }

    @Test
    fun `the correlation window is three seconds`() {
        assertEquals(3_000L, NetworkAttribution.CORRELATION_WINDOW_MILLIS)
    }
}
