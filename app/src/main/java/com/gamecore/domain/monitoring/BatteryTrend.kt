package com.gamecore.domain.monitoring

import com.gamecore.core.model.BatteryDrain
import com.gamecore.core.model.BatteryReading

/**
 * How confident GameCore is about a live drain figure, as a closed set.
 *
 * A boolean would collapse the two states the user needs told apart: a rate that cannot be quoted
 * because the phone is plugged in, and one that cannot be quoted *yet*. The first is permanent until
 * they unplug and the second resolves on its own, and a dashboard that shows a dash for both is
 * telling the user their phone is broken.
 */
enum class DrainConfidence {
    /** Nothing has been sampled since GameCore started looking. */
    NO_READING,

    /** A charger is attached, so there is no drain to measure. */
    CHARGING,

    /** Sampling, but for less than [BatteryDrain.MIN_RELIABLE_MILLIS] so far. */
    MEASURING,

    /** A rate may be quoted. */
    MEASURED,
}

/**
 * Battery level over the window GameCore has actually been watching, as an immutable value.
 *
 * §21 asks for a drain rate outside a session as well as inside one, and this is the whole of it —
 * there is no battery sampler. Readings are folded in from [PerformanceMonitor]'s existing loop, so
 * enabling this costs nothing and, more importantly, adds nothing that keeps running when no screen
 * and no service wants a sample. A background poller that existed only to watch the battery would be
 * the exact background drain §26 forbids, in an app that exists to save it.
 *
 * The arithmetic is delegated to [BatteryDrain] rather than done here, so the five-minute floor and
 * the "a charger invalidates the rate" rule have one implementation shared with the session report.
 * What this type adds is the three things a *window* has to get right and a single interval does not:
 *
 *  1. **A charger restarts the window.** Not just flags it: a phone that charged for an hour and has
 *     been unplugged for ten minutes should quote the ten minutes, and a permanently-poisoned
 *     `wasCharging` flag would make it quote nothing until the app was restarted.
 *
 *  2. **A gap in readings restarts it too.** The sample loop stops when nothing is subscribed, which
 *     is the point of it. Fold a reading taken an hour after the last one into the same window and the
 *     elapsed time is honest while the figure is not — the phone may have been charged to full and
 *     drained again inside the gap, entirely unobserved. [MAX_GAP_MILLIS] is the limit.
 *
 *  3. **A level that rises is not negative drain.** Fuel gauges recover a point when the device cools,
 *     and a phone off charge at 71% that reads 72% has not gained an hour of life. This is handled by
 *     doing nothing — [BatteryDrain.pointsLost] floors at zero, so the window reports no measurable
 *     drain and keeps accumulating rather than throwing away a good long window over gauge jitter.
 */
data class BatteryTrend(
    /** Level at the start of the window, or -1 before the first reading. */
    val startPercent: Int = -1,
    val startedAtMillis: Long = 0L,
    /** The most recent level, or -1 before the first reading. */
    val latestPercent: Int = -1,
    val latestAtMillis: Long = 0L,
    /** True when the last reading had a charger attached. */
    val isCharging: Boolean = false,
) {

    val hasReading: Boolean get() = latestPercent >= 0

    /** How long the current window covers. Zero before the first reading. */
    val observedMillis: Long
        get() = if (hasReading) (latestAtMillis - startedAtMillis).coerceAtLeast(0L) else 0L

    val confidence: DrainConfidence
        get() = when {
            !hasReading -> DrainConfidence.NO_READING
            isCharging -> DrainConfidence.CHARGING
            observedMillis >= BatteryDrain.MIN_RELIABLE_MILLIS -> DrainConfidence.MEASURED
            else -> DrainConfidence.MEASURING
        }

    /**
     * The window as a [BatteryDrain], or null when there is nothing to describe.
     *
     * Returned while [confidence] is still [DrainConfidence.MEASURING], because the measurement — two
     * levels and an interval — is real and worth showing even when the extrapolated rate is not.
     * `BatteryDrain.percentPerHour` is the field that withholds itself, and it does that on its own.
     */
    fun drain(): BatteryDrain? {
        if (!hasReading || isCharging) return null
        return BatteryDrain(
            startPercent = startPercent,
            endPercent = latestPercent,
            elapsedMillis = observedMillis,
            // Always false: a charging reading restarts the window rather than poisoning it, so no
            // window this method can see contains one.
            wasCharging = false,
        )
    }

    /**
     * Folds one reading in.
     *
     * Pure and total, and the only clock is [nowMillis] — which is the sample's own capture time, not
     * `System.currentTimeMillis()`, so a snapshot that took two seconds to assemble is dated when it
     * was taken.
     */
    fun update(reading: BatteryReading, nowMillis: Long): BatteryTrend {
        val restarts = !hasReading ||
            reading.isCharging ||
            isCharging ||
            nowMillis - latestAtMillis > MAX_GAP_MILLIS ||
            nowMillis < latestAtMillis
        if (restarts) return anchoredAt(reading, nowMillis)
        return copy(latestPercent = reading.levelPercent, latestAtMillis = nowMillis)
    }

    private fun anchoredAt(reading: BatteryReading, nowMillis: Long) = BatteryTrend(
        startPercent = reading.levelPercent,
        startedAtMillis = nowMillis,
        latestPercent = reading.levelPercent,
        latestAtMillis = nowMillis,
        isCharging = reading.isCharging,
    )

    companion object {
        val INITIAL = BatteryTrend()

        /**
         * The longest silence a window survives.
         *
         * Two minutes: comfortably longer than any sample interval the settings allow, including the
         * fifteen-second maximum and a screen-off pause, and far shorter than any gap in which a
         * meaningful amount of charging could have happened unseen.
         */
        const val MAX_GAP_MILLIS = 120_000L
    }
}
