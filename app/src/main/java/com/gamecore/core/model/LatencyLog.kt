package com.gamecore.core.model

import com.gamecore.core.common.Formatters

/**
 * What the latency probes did across one whole session.
 *
 * [ConnectionStability] answers "how is the connection right now" from a burst of probes taken back to
 * back. This answers a different question — "how was the connection while I was playing" — and it has to
 * survive being written to a row and read back an hour later, so it stores counts and extremes rather
 * than the samples themselves. A two-hour session is a few hundred probes; keeping them all to recompute
 * an average that is already stored would be a table of its own for no new fact.
 *
 * Every field here is a count of something that happened or a reading that was taken. There is no rate,
 * no percentage of packets and no loss figure, because the probe is a TCP handshake: it either completes
 * or it does not, and a refused or filtered connection is not a dropped packet. [failedProbes] is the
 * honest form of that — probes that did not complete — and the wording is deliberate everywhere it is
 * read out.
 *
 * A null [jitterMillis] or [worstMillis] means no probe completed, not that the connection was perfect.
 * The record is built by `LatencyLogger`, which is where the arithmetic and the spike rule live.
 */
data class LatencyLog(
    val completedProbes: Int = 0,
    val failedProbes: Int = 0,
    val spikes: Int = 0,
    val worstMillis: Int? = null,
    val jitterMillis: Int? = null,
    val longestFailureRun: Int = 0,
) {
    /** Probes that were actually sent. A tick that did not probe is not an attempt and is not here. */
    val attempts: Int get() = completedProbes + failedProbes

    /**
     * What to say about the connection in one word, in the order that a problem outranks a reassurance.
     *
     * A failure is always reportable, and needs no minimum to be worth saying: one probe that did not
     * complete is a fact about the connection, whereas "stable" is a claim that needs evidence, so it is
     * withheld until [MIN_PROBES_FOR_VERDICT] probes have been sent. That asymmetry is the point — two
     * quiet probes are not proof of a good connection, but one refused handshake is proof of a bad
     * moment.
     */
    val verdict: LatencyVerdict
        get() = when {
            attempts == 0 -> LatencyVerdict.UNMEASURED
            completedProbes == 0 -> LatencyVerdict.DOWN
            failedProbes > 0 -> LatencyVerdict.UNRELIABLE
            attempts < MIN_PROBES_FOR_VERDICT -> LatencyVerdict.UNMEASURED
            spikes > 0 -> LatencyVerdict.SPIKY
            (jitterMillis ?: 0) > ConnectionStability.UNSTABLE_JITTER_MILLIS -> LatencyVerdict.SPIKY
            else -> LatencyVerdict.STABLE
        }

    /**
     * The sentence under the verdict, naming the figures it was reached from.
     *
     * Written out rather than left to the screen because the numbers only mean anything together: three
     * spikes out of six probes and three out of six hundred are the same count and different sessions.
     */
    val summary: String
        get() = when (verdict) {
            LatencyVerdict.UNMEASURED -> if (attempts == 0) {
                "No probe was sent. Latency measurement is off in Settings, or the device was not " +
                    "connected while this game was open."
            } else {
                "Only ${Formatters.count(attempts, "probe")} went out, which is too few to say " +
                    "anything about the connection."
            }

            LatencyVerdict.DOWN ->
                "None of the ${Formatters.count(attempts, "probe")} sent during this session " +
                    "completed. The connection was down, or something was blocking outbound " +
                    "connections. This is not a packet-loss figure — GameCore cannot measure one."

            LatencyVerdict.UNRELIABLE -> buildString {
                append("$failedProbes of ${Formatters.count(attempts, "probe")} did not complete")
                if (longestFailureRun > 1) append(", $longestFailureRun of them in a row")
                append(". A refused handshake is not a dropped packet, so this is not packet loss, ")
                append("but the connection was not answering every time it was asked.")
                append(spikeClause())
                append(steadinessClause())
            }

            LatencyVerdict.SPIKY -> buildString {
                append("All ${Formatters.count(attempts, "probe")} completed")
                append(if (spikes == 0) ", but not evenly." else ".")
                append(spikeClause())
                append(steadinessClause())
            }

            LatencyVerdict.STABLE ->
                "All ${Formatters.count(attempts, "probe")} completed, none of them far above this " +
                    "session's own average." + steadinessClause()
        }

    /** The spike count with its worst reading, or nothing at all when there were none. */
    private fun spikeClause(): String = when {
        spikes == 0 -> ""
        else -> " ${Formatters.count(spikes, "probe")} came back at least twice the average so far" +
            (worstMillis?.let { ", the worst at ${Formatters.millis(it)}" } ?: "") + "."
    }

    /** Jitter, phrased as what it feels like rather than as a statistic. */
    private fun steadinessClause(): String = when (val jitter = jitterMillis) {
        null -> ""
        else -> " Round trips varied by about ${Formatters.millis(jitter)} between consecutive probes."
    }

    companion object {
        /** Below this many probes, "stable" is not a claim this can support. */
        const val MIN_PROBES_FOR_VERDICT = 3

        val EMPTY = LatencyLog()
    }
}

/**
 * The one-word state of the connection across a session.
 *
 * [UNRELIABLE] rather than a loss figure, and [DOWN] rather than "offline": the app knows that its own
 * handshakes did not complete, which is not the same as knowing the network was gone.
 */
enum class LatencyVerdict(val label: String) {
    UNMEASURED("Not measured"),
    STABLE("Stable"),
    SPIKY("Spiky"),
    UNRELIABLE("Unreliable"),
    DOWN("No reply"),
}
