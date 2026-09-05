package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stored latency log says about a session once it comes back off disk.
 *
 * `LatencyLoggerTest` covers the arithmetic that fills these fields in; this covers the reading of them,
 * which is a separate risk. Every property here is derived, so a row of six integers turns into a
 * one-word verdict and a sentence with no further input — and that sentence is shown to a user weeks
 * later, next to nothing that would contradict it. The full strings are asserted rather than sampled
 * because the punctuation has already been wrong once: a session judged unsteady by jitter alone with no
 * spikes to name produced a run-on clause, which reads as carelessness about a claim it should be careful
 * about.
 *
 * The other thing pinned here is the rule the whole feature exists to respect: a TCP handshake that did
 * not complete is not a dropped packet, so no summary in any state may report packet loss. The only
 * permitted mention of packets is a denial.
 */
class LatencyLogTest {

    @Test
    fun `a log that kept nothing is unmeasured rather than perfect`() {
        // The zero row: the session ran with logging on and no probe ever went out — measurement off in
        // Settings, or no connection the whole time. Zero failures here is not a good connection.
        val log = LatencyLog.EMPTY
        assertEquals(0, log.attempts)
        assertEquals(LatencyVerdict.UNMEASURED, log.verdict)
        assertEquals(
            "No probe was sent. Latency measurement is off in Settings, or the device was not " +
                "connected while this game was open.",
            log.summary,
        )
    }

    @Test
    fun `attempts is what was sent, not what came back`() {
        val log = LatencyLog(completedProbes = 18, failedProbes = 5)
        assertEquals(23, log.attempts)
    }

    @Test
    fun `too few probes says so instead of returning a verdict`() {
        val two = LatencyLog(completedProbes = 2, jitterMillis = 4)
        assertEquals(LatencyVerdict.UNMEASURED, two.verdict)
        assertEquals(
            "Only 2 probes went out, which is too few to say anything about the connection.",
            two.summary,
        )

        // The singular reads properly too, which is the whole reason the count goes through Formatters.
        assertEquals(
            "Only 1 probe went out, which is too few to say anything about the connection.",
            LatencyLog(completedProbes = 1).summary,
        )
    }

    @Test
    fun `three clean probes is the least that can be called stable`() {
        assertEquals(LatencyVerdict.UNMEASURED, LatencyLog(completedProbes = 2, jitterMillis = 1).verdict)
        assertEquals(LatencyVerdict.STABLE, LatencyLog(completedProbes = 3, jitterMillis = 1).verdict)
        assertEquals(3, LatencyLog.MIN_PROBES_FOR_VERDICT)
    }

    @Test
    fun `steady is the same threshold the live readout uses`() {
        // Reusing ConnectionStability's figure is deliberate: a connection called unstable on the
        // dashboard and stable in its own session report would be two answers to one question.
        val threshold = ConnectionStability.UNSTABLE_JITTER_MILLIS
        assertEquals(
            LatencyVerdict.STABLE,
            LatencyLog(completedProbes = 10, jitterMillis = threshold).verdict,
        )
        assertEquals(
            LatencyVerdict.SPIKY,
            LatencyLog(completedProbes = 10, jitterMillis = threshold + 1).verdict,
        )
    }

    @Test
    fun `a session with no completed probe reads as no reply`() {
        val log = LatencyLog(failedProbes = 6, longestFailureRun = 6)
        assertEquals(LatencyVerdict.DOWN, log.verdict)
        assertEquals(
            "None of the 6 probes sent during this session completed. The connection was down, or " +
                "something was blocking outbound connections. This is not a packet-loss figure — " +
                "GameCore cannot measure one.",
            log.summary,
        )
    }

    @Test
    fun `an unreliable session names the failures and keeps the rest of what was measured`() {
        // The verdict is one word and there are three facts to report. Losing the spikes and the jitter
        // because the failures outranked them would make the sentence less true than the row behind it.
        val log = LatencyLog(
            completedProbes = 20,
            failedProbes = 4,
            spikes = 1,
            worstMillis = 900,
            jitterMillis = 30,
            longestFailureRun = 3,
        )
        assertEquals(LatencyVerdict.UNRELIABLE, log.verdict)
        assertEquals(
            "4 of 24 probes did not complete, 3 of them in a row. A refused handshake is not a " +
                "dropped packet, so this is not packet loss, but the connection was not answering " +
                "every time it was asked. 1 probe came back at least twice the average so far, the " +
                "worst at 900 ms. Round trips varied by about 30 ms between consecutive probes.",
            log.summary,
        )
    }

    @Test
    fun `a single failure is not described as a run`() {
        val log = LatencyLog(completedProbes = 9, failedProbes = 1, jitterMillis = 8)
        assertFalse(log.summary.contains("in a row"))
        assertTrue(log.summary.startsWith("1 of 10 probes did not complete."))
    }

    @Test
    fun `spikes are reported with the worst reading behind them`() {
        val log = LatencyLog(completedProbes = 10, spikes = 2, worstMillis = 480, jitterMillis = 12)
        assertEquals(LatencyVerdict.SPIKY, log.verdict)
        assertEquals(
            "All 10 probes completed. 2 probes came back at least twice the average so far, the " +
                "worst at 480 ms. Round trips varied by about 12 ms between consecutive probes.",
            log.summary,
        )
    }

    @Test
    fun `a session judged unsteady by jitter alone still reads as a sentence`() {
        // The regression this file exists for. Nothing spiked, nothing failed, and the connection was
        // still swinging by 80 ms — so the summary has to say what was wrong without a spike count to
        // hang it on.
        val log = LatencyLog(completedProbes = 8, spikes = 0, worstMillis = 200, jitterMillis = 80)
        assertEquals(LatencyVerdict.SPIKY, log.verdict)
        assertEquals(
            "All 8 probes completed, but not evenly. Round trips varied by about 80 ms between " +
                "consecutive probes.",
            log.summary,
        )
    }

    @Test
    fun `a stable session says what it is stable against`() {
        val log = LatencyLog(completedProbes = 12, worstMillis = 44, jitterMillis = 6)
        assertEquals(LatencyVerdict.STABLE, log.verdict)
        assertEquals(
            "All 12 probes completed, none of them far above this session's own average. Round trips " +
                "varied by about 6 ms between consecutive probes.",
            log.summary,
        )
    }

    @Test
    fun `a log with no jitter to report leaves the steadiness clause off`() {
        // One completed probe among failures: there is no consecutive pair, so there is no figure. An
        // absent jitter must not become "varied by about 0 ms", which would read as perfect steadiness.
        val log = LatencyLog(completedProbes = 1, failedProbes = 3, longestFailureRun = 2)
        assertFalse(log.summary.contains("varied by"))
        assertFalse(log.summary.contains("0 ms"))
    }

    @Test
    fun `no verdict describes a failed handshake as a lost packet`() {
        val everyState = listOf(
            LatencyLog.EMPTY,
            LatencyLog(completedProbes = 2),
            LatencyLog(completedProbes = 12, jitterMillis = 6),
            LatencyLog(completedProbes = 12, spikes = 3, worstMillis = 700, jitterMillis = 90),
            LatencyLog(completedProbes = 12, failedProbes = 2, longestFailureRun = 2, jitterMillis = 20),
            LatencyLog(failedProbes = 9, longestFailureRun = 9),
        )
        // Every verdict is represented, so this is a guard over the whole type and not a sample of it.
        assertEquals(LatencyVerdict.entries.toSet(), everyState.map { it.verdict }.toSet())
        everyState.forEach { log ->
            val text = log.summary.lowercase()
            if (text.contains("packet")) {
                assertTrue(
                    "A summary that mentions packets must be denying a loss figure: ${log.summary}",
                    text.contains("not a packet-loss figure") ||
                        text.contains("not packet loss") ||
                        text.contains("not a dropped packet"),
                )
            }
            assertFalse(log.summary, text.contains("packets lost"))
            assertFalse(log.summary, text.contains("% loss"))
            assertFalse(log.summary, text.contains("ping"))
        }
    }

    @Test
    fun `every verdict has a label a screen can print`() {
        LatencyVerdict.entries.forEach { verdict ->
            assertTrue(verdict.name, verdict.label.isNotBlank())
        }
        assertEquals("No reply", LatencyVerdict.DOWN.label)
        assertEquals("Not measured", LatencyVerdict.UNMEASURED.label)
    }
}
