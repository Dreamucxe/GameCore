package com.gamecore.domain.monitoring

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.LatencyProbe
import com.gamecore.core.model.LatencyVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that turn a session's handshakes into a claim about the connection.
 *
 * Pinned in a unit test because every one of these is a judgement, not a reading. Whether 180 ms is a
 * spike depends on what the rest of the session looked like; whether "stable" may be said depends on how
 * many probes there were to say it from; and what a probe that never completed is called is the line this
 * whole feature is built around — a TCP connect either completes or it does not, and a refused connection
 * is not a dropped packet. A regression in any of them would be invisible on a device and would show up
 * as a confident sentence about somebody's network.
 */
class LatencyLoggerTest {

    @Test
    fun `an empty logger has attempted nothing and claims nothing`() {
        val log = LatencyLogger.EMPTY.log
        assertEquals(0, log.attempts)
        assertEquals(0, log.completedProbes)
        assertEquals(0, log.failedProbes)
        assertNull(log.worstMillis)
        assertNull(log.jitterMillis)
        assertEquals(LatencyVerdict.UNMEASURED, log.verdict)
    }

    @Test
    fun `nothing is a spike until there is a baseline to be a spike against`() {
        // 900 ms as the very first probe is not a spike, because there is nothing yet for it to be a
        // spike relative to — it is simply this session's only reading, and a slow one.
        val first = LatencyLogger.EMPTY.record(probe(900)).log
        assertEquals(0, first.spikes)
        assertEquals(900, first.worstMillis)

        // Still nothing after two more: the rule needs MIN_PROBES_BEFORE_SPIKE completed probes first.
        val third = LatencyLogger.EMPTY
            .record(probe(20))
            .record(probe(900))
            .record(probe(20))
            .log
        assertEquals(0, third.spikes)
    }

    @Test
    fun `a probe far above the session's own average is a spike`() {
        val log = LatencyLogger.EMPTY
            .record(probe(20))
            .record(probe(22))
            .record(probe(21))
            .record(probe(300))
            .log
        assertEquals(1, log.spikes)
        assertEquals(300, log.worstMillis)
        assertEquals(LatencyVerdict.SPIKY, log.verdict)
    }

    @Test
    fun `a fast connection's small wobble is not a spike`() {
        // 9 ms is more than twice the 4 ms average and is nobody's spike. The absolute floor is what
        // stops a good connection from being reported as a hundred events.
        val log = LatencyLogger.EMPTY
            .record(probe(4))
            .record(probe(4))
            .record(probe(4))
            .record(probe(9))
            .log
        assertEquals(0, log.spikes)
        assertEquals(LatencyVerdict.STABLE, log.verdict)
    }

    @Test
    fun `a connection that degrades and stays degraded stops being called a spike`() {
        // Four good probes then twenty bad ones. The first few of the bad ones are genuine spikes
        // against what came before, and then the average catches up and the rest are simply what this
        // connection is now. The alternative — every probe after the step counted as a spike — would
        // report twenty events for one change.
        var logger = LatencyLogger.EMPTY
        repeat(4) { logger = logger.record(probe(20)) }
        repeat(20) { logger = logger.record(probe(200)) }
        val log = logger.log
        assertEquals(4, log.spikes)
        assertEquals(200, log.worstMillis)
        assertEquals(24, log.completedProbes)
    }

    @Test
    fun `probes that did not complete are counted as that and nothing else`() {
        val log = LatencyLogger.EMPTY
            .record(probe(30))
            .record(failed())
            .record(probe(32))
            .record(failed())
            .log
        assertEquals(2, log.completedProbes)
        assertEquals(2, log.failedProbes)
        assertEquals(4, log.attempts)
        assertEquals(LatencyVerdict.UNRELIABLE, log.verdict)
        // The wording is the point of the feature, so it is asserted rather than left to a reviewer.
        // The sentence has to deny packet loss, not report one — a test for the mere presence of the
        // phrase would pass on "2 probes lost".
        assertTrue(log.summary.contains("not packet loss"))
        assertTrue(log.summary.contains("did not complete"))
    }

    @Test
    fun `the longest unbroken run of failures is kept, not the last one`() {
        val log = LatencyLogger.EMPTY
            .record(failed())
            .record(failed())
            .record(failed())
            .record(probe(40))
            .record(failed())
            .log
        assertEquals(3, log.longestFailureRun)
        assertEquals(4, log.failedProbes)
        assertEquals(1, log.completedProbes)
    }

    @Test
    fun `one failure is enough to withhold stable, and one probe is not enough to grant it`() {
        // Three clean probes earn "stable"; the same three plus one refused handshake do not. No
        // minimum applies to the failure — one probe that did not complete is a fact about the
        // connection, while "stable" is a claim that needs a few probes behind it.
        val clean = LatencyLogger.EMPTY.record(probe(30)).record(probe(31)).record(probe(30))
        assertEquals(LatencyVerdict.STABLE, clean.log.verdict)
        assertEquals(LatencyVerdict.UNRELIABLE, clean.record(failed()).log.verdict)

        // One probe, however good it was, is below MIN_PROBES_FOR_VERDICT and buys no verdict at all.
        assertEquals(LatencyVerdict.UNMEASURED, LatencyLogger.EMPTY.record(probe(30)).log.verdict)
    }

    @Test
    fun `a session where no probe completed reads as no reply rather than as unreliable`() {
        var logger = LatencyLogger.EMPTY
        repeat(6) { logger = logger.record(failed()) }
        val log = logger.log
        assertEquals(LatencyVerdict.DOWN, log.verdict)
        assertEquals(6, log.longestFailureRun)
        assertNull(log.worstMillis)
    }

    @Test
    fun `jitter is the mean gap between consecutive probes`() {
        // 20 → 40 → 30: gaps of 20 and 10, so a mean of 15.
        val log = LatencyLogger.EMPTY
            .record(probe(20))
            .record(probe(40))
            .record(probe(30))
            .log
        assertEquals(15, log.jitterMillis)
    }

    @Test
    fun `a single completed probe has no jitter to report`() {
        assertNull(LatencyLogger.EMPTY.record(probe(30)).log.jitterMillis)
    }

    @Test
    fun `a failure breaks the jitter chain rather than being measured across`() {
        // 20, then nothing, then 500. The 480 ms difference spans a timeout, which is a longer interval
        // than the one jitter describes, so it is not counted — the failure is already reported as a
        // failure and does not need to inflate a second figure as well.
        val log = LatencyLogger.EMPTY
            .record(probe(20))
            .record(failed())
            .record(probe(500))
            .log
        assertNull(log.jitterMillis)
        assertEquals(1, log.failedProbes)
    }

    @Test
    fun `a restricted reading is not an attempt`() {
        // Latency measurement switched off, or no connection to measure. The app did not try, so there
        // is nothing to report either way — counting these as failures would turn the user's own
        // setting into a verdict about their network.
        val log = LatencyLogger.EMPTY
            .record(Observed.samplingDisabled("Latency measurement is switched off in Settings."))
            .record(Observed.notPresent("No network connection to measure."))
            .log
        assertEquals(0, log.attempts)
        assertEquals(LatencyVerdict.UNMEASURED, log.verdict)
    }

    @Test
    fun `high jitter alone is enough to say the connection was not steady`() {
        // No probe is twice the average and none failed, but the round trip is swinging by 80 ms, which
        // is what an unstable connection feels like in a game.
        val log = LatencyLogger.EMPTY
            .record(probe(100))
            .record(probe(180))
            .record(probe(100))
            .record(probe(180))
            .log
        assertEquals(0, log.spikes)
        assertEquals(80, log.jitterMillis)
        assertEquals(LatencyVerdict.SPIKY, log.verdict)
    }

    private fun probe(millis: Int): Observed<LatencyProbe> = Observed.of(
        LatencyProbe(millis = millis, host = "1.1.1.1"),
        DataSource.SOCKET_PROBE,
        Precision.SAMPLED,
    )

    private fun failed(): Observed<LatencyProbe> =
        Observed.Failed("The probe to 1.1.1.1 did not complete", "timeout")
}
