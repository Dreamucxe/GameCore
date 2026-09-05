package com.gamecore.domain.monitoring

import com.gamecore.core.common.Observed
import com.gamecore.core.model.LatencyLog
import com.gamecore.core.model.LatencyProbe
import kotlin.math.abs

/**
 * Folds one session's probe outcomes into a [LatencyLog], one probe at a time.
 *
 * Immutable and pure, like `MetricHistory` and `WriteLedger`: [record] returns a new logger and the caller
 * keeps the result, so the whole rule set is testable without a device, a socket or a clock. `LatencyLog`
 * is what this produces and all the UI ever sees; the running totals needed to produce it stay here,
 * because a record read back from a row must be self-consistent — a stored `completedProbes = 40` beside a
 * stored `totalMillis = 0` would compute an average of zero and report a lie.
 *
 * One [record] call is one probe that was actually sent. That is the whole reason this exists rather than
 * a fold over `SessionSample.latencyMillis`: a sample carries the last probe's result on every tick
 * between probes, so folding samples counts one handshake five times, and it flattens a probe that failed
 * and a tick that never probed into the same null.
 */
data class LatencyLogger(
    private val completedProbes: Int = 0,
    private val failedProbes: Int = 0,
    private val totalMillis: Long = 0L,
    private val worstMillis: Int? = null,
    private val spikes: Int = 0,
    private val jitterTotalMillis: Long = 0L,
    private val jitterSamples: Int = 0,
    private val longestFailureRun: Int = 0,
    private val currentFailureRun: Int = 0,
    private val lastMillis: Int? = null,
) {
    /** The record as it will be stored and read out. Cheap enough to take after every probe. */
    val log: LatencyLog
        get() = LatencyLog(
            completedProbes = completedProbes,
            failedProbes = failedProbes,
            spikes = spikes,
            worstMillis = worstMillis,
            jitterMillis = if (jitterSamples == 0) null else (jitterTotalMillis / jitterSamples).toInt(),
            longestFailureRun = longestFailureRun,
        )

    /**
     * Takes one probe outcome.
     *
     * [Observed.Restricted] is returned unchanged and counted as nothing, because it is not a probe: it is
     * the app saying it did not try — measurement switched off, or no connection to measure. Counting
     * those as failures would turn a setting the user chose into a report that their network is broken.
     */
    fun record(probe: Observed<LatencyProbe>): LatencyLogger = when (probe) {
        is Observed.Value -> completed(probe.value.millis)
        is Observed.Failed -> failed()
        is Observed.Restricted -> this
    }

    /**
     * A probe that came back.
     *
     * The spike test is against the session's own average so far, not a fixed threshold: 180 ms is a spike
     * on a connection that has been sitting at 30 ms and is Tuesday on a connection that has been sitting
     * at 190 ms. Both conditions have to hold — at least [SPIKE_FACTOR] times the running average *and*
     * [SPIKE_FLOOR_MILLIS] above it — so that a 4 ms average does not make every 9 ms probe an event.
     * Nothing is called a spike until [MIN_PROBES_BEFORE_SPIKE] probes have set a baseline worth
     * comparing to.
     *
     * The spike itself goes into the average like any other probe. That is deliberate: a connection that
     * degrades and stays degraded registers as a handful of spikes and then as a higher average, which is
     * what it is, rather than as hundreds of spikes for the rest of the session.
     */
    private fun completed(millis: Int): LatencyLogger {
        val baseline = if (completedProbes == 0) null else totalMillis.toDouble() / completedProbes
        val isSpike = baseline != null &&
            completedProbes >= MIN_PROBES_BEFORE_SPIKE &&
            millis >= baseline * SPIKE_FACTOR &&
            millis - baseline >= SPIKE_FLOOR_MILLIS
        // Jitter pairs two probes that ran back to back. A failure in between breaks the chain, because
        // the spread across a gap that contains a timeout is not the same measurement as the spread
        // between two consecutive round trips — and the failure is already counted in its own right.
        val gap = lastMillis?.let { abs(millis - it).toLong() }
        return copy(
            completedProbes = completedProbes + 1,
            totalMillis = totalMillis + millis,
            worstMillis = maxOf(worstMillis ?: millis, millis),
            spikes = if (isSpike) spikes + 1 else spikes,
            jitterTotalMillis = jitterTotalMillis + (gap ?: 0L),
            jitterSamples = if (gap == null) jitterSamples else jitterSamples + 1,
            currentFailureRun = 0,
            lastMillis = millis,
        )
    }

    /** A probe that did not complete. Never called a lost packet — see [LatencyLog]. */
    private fun failed(): LatencyLogger {
        val run = currentFailureRun + 1
        return copy(
            failedProbes = failedProbes + 1,
            longestFailureRun = maxOf(longestFailureRun, run),
            currentFailureRun = run,
            lastMillis = null,
        )
    }

    companion object {
        /** Completed probes needed before any of them can be judged against the rest. */
        const val MIN_PROBES_BEFORE_SPIKE = 3

        /** How many times the running average a probe has to be. */
        const val SPIKE_FACTOR = 2.0

        /** And how far above it in absolute terms, so a fast connection is not all spikes. */
        const val SPIKE_FLOOR_MILLIS = 40

        val EMPTY = LatencyLogger()
    }
}
