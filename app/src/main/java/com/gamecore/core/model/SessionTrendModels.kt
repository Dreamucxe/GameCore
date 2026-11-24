package com.gamecore.core.model

import com.gamecore.core.common.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The ranges the Sessions screen's Trends section can ask for.
 *
 * The day count is the whole definition: [SessionTrends.window] turns it into local-midnight boundaries,
 * and nothing else in the feature is allowed to invent a different notion of "seven days". A rolling
 * 168-hour window would put the same clock time at each end and split days across the chart; calendar
 * days are what a user means by "the last week", and they survive a daylight-saving change because
 * [LocalDate.atStartOfDay] resolves each boundary in the user's own zone.
 */
enum class SessionTrendRange(val days: Int, val label: String) {
    WEEK(7, "7 days"),
    MONTH(30, "30 days"),
    QUARTER(90, "90 days"),
}

/** Which way a metric moved. A direction is never inferred from colour. */
enum class SessionTrendDirection { UP, DOWN, FLAT, UNKNOWN }

/**
 * One figure the Trends section can show, and whether a rise is good for the player.
 *
 * [betterDirection] is null for [PLAY_TIME] on purpose. More time in a game is not automatically better
 * than less, so the card states the change and leaves the judgement out. The other three have a direction
 * the user would recognise: higher FPS is better, lower temperature and lower drain are better.
 */
enum class SessionTrendMetric(
    val label: String,
    val shortLabel: String,
    val betterDirection: SessionTrendDirection?,
) {
    FRAME_RATE("Average FPS", "FPS", SessionTrendDirection.UP),
    TEMPERATURE("Temperature", "Temp", SessionTrendDirection.DOWN),
    BATTERY_DRAIN("Battery drain", "Battery", SessionTrendDirection.DOWN),
    PLAY_TIME("Play time", "Play time", null),
}

/** The current and previous calendar periods, resolved once and passed to the pure arithmetic. */
data class SessionTrendWindow(
    val startMillis: Long,
    val endMillis: Long,
    val previousStartMillis: Long,
    val previousEndMillis: Long,
    val days: List<LocalDate>,
) {
    val firstDay: LocalDate get() = days.first()
    val lastDay: LocalDate get() = days.last()
    val daysCount: Int get() = days.size
}

/**
 * One package's sessions on one local day, as SQLite aggregated them.
 *
 * Weighted sums rather than finished averages: the "All games" view has to combine several package rows
 * for the same day, and averaging their averages would let a ten-minute session count as much as a
 * three-hour one. Keeping the numerator and the weight lets [SessionTrends] do the one correct division
 * at the end.
 *
 * The drain fields are already filtered by [BatteryDrain]'s rules in SQL, and [drainSessionCount] is the
 * number of sessions that passed. A null rate with a non-zero count cannot occur; a non-zero count with
 * a null rate would be a bug worth failing a test over.
 */
data class SessionTrendAggregate(
    val day: LocalDate,
    val packageName: String,
    val sessionCount: Int,
    val playTimeMillis: Long,
    val frameRateWeightedSum: Double?,
    val frameRateWeightMillis: Long?,
    val frameRateSessionCount: Int,
    val temperatureWeightedSum: Double?,
    val temperatureWeightMillis: Long?,
    val temperatureSessionCount: Int,
    val drainRateSum: Double?,
    val drainSessionCount: Int,
    val chargingSessionCount: Int,
)

/** One session that held the best or worst average frame rate in the chosen range. */
data class SessionTrendSession(
    val id: Long,
    val packageName: String,
    val gameLabel: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val averageFrameRate: Float,
)

/** One day in the chart, with the metrics that day actually measured. Null is a gap, never a zero. */
data class SessionTrendDay(
    val date: LocalDate,
    val sessionCount: Int,
    val playTimeMillis: Long,
    val averageFrameRate: Double?,
    val averageTemperatureDeciCelsius: Double?,
    val averageDrainPercentPerHour: Double?,
)

/** The totals for one period. Every average is null when no session in it measured that metric. */
data class SessionTrendPeriod(
    val sessionCount: Int,
    val playTimeMillis: Long,
    val averageFrameRate: Double?,
    val frameRateSessionCount: Int,
    val averageTemperatureDeciCelsius: Double?,
    val temperatureSessionCount: Int,
    val averageDrainPercentPerHour: Double?,
    val drainSessionCount: Int,
    val chargingSessionCount: Int,
) {
    val averageSessionMillis: Long
        get() = if (sessionCount > 0) playTimeMillis / sessionCount else 0L

    /** 0..1, or null when there were no sessions to have a share of. */
    val chargingShare: Double?
        get() = if (sessionCount > 0) chargingSessionCount.toDouble() / sessionCount else null

    companion object {
        val EMPTY = SessionTrendPeriod(
            sessionCount = 0,
            playTimeMillis = 0L,
            averageFrameRate = null,
            frameRateSessionCount = 0,
            averageTemperatureDeciCelsius = null,
            temperatureSessionCount = 0,
            averageDrainPercentPerHour = null,
            drainSessionCount = 0,
            chargingSessionCount = 0,
        )
    }
}

/** One plotted day. A null value is a day that measured nothing for this metric; play time uses zero. */
data class SessionTrendPoint(
    val date: LocalDate,
    val value: Double?,
    val sessionCount: Int,
    val playTimeMillis: Long,
)

/**
 * A metric card's arithmetic and the sentences it produces.
 *
 * Keeping the numbers and the text together is deliberate: the delta's sign, its threshold for "no
 * material change" and the word "better" are one decision, and splitting them between a model and a
 * composable is how a card ends up saying "worse" about a fall in battery drain.
 */
data class SessionTrendSummary(
    val metric: SessionTrendMetric,
    /** In the metric's display unit: fps, °C, %/h, or minutes for play time. */
    val currentValue: Double?,
    val previousValue: Double?,
    val absoluteDelta: Double?,
    val relativeChange: Double?,
    val direction: SessionTrendDirection,
    val isImprovement: Boolean?,
    val valueText: String,
    /** Short line for the card: "▲ 2.4 fps better", "No material change", or null when uncomparable. */
    val deltaText: String?,
    /** The qualification under the delta: what it was compared with and how many sessions measured it. */
    val detail: String,
    /** The whole card as one spoken sentence, so a screen reader does not have to infer the judgement. */
    val spoken: String,
)

/**
 * Everything the Trends section draws for one range and one game.
 *
 * [current] and [previous] are both carried because the banner and every card need the previous period
 * for a delta. [days] always has exactly [SessionTrendWindow.daysCount] entries, including days with no
 * sessions, so the chart's horizontal spacing is the calendar rather than only the days that happened to
 * contain play.
 */
data class SessionTrendReport(
    val range: SessionTrendRange,
    val window: SessionTrendWindow,
    val current: SessionTrendPeriod,
    val previous: SessionTrendPeriod,
    val days: List<SessionTrendDay>,
    val best: SessionTrendSession?,
    val worst: SessionTrendSession?,
    val conditionChanges: List<String>,
) {
    val hasData: Boolean get() = current.sessionCount > 0

    fun summary(metric: SessionTrendMetric): SessionTrendSummary =
        SessionTrends.summary(this, metric)

    fun series(metric: SessionTrendMetric): List<SessionTrendPoint> =
        SessionTrends.series(this, metric)

    fun chartSummary(metric: SessionTrendMetric): String =
        SessionTrends.chartSummary(this, metric)

    /** Why a metric has no chart, in the terms of the metric rather than a generic "no data". */
    fun absenceReason(metric: SessionTrendMetric): String = when (metric) {
        SessionTrendMetric.FRAME_RATE ->
            "No session in this range recorded a frame rate. GameCore records one only when the game " +
                "exposes real frame timing."
        SessionTrendMetric.TEMPERATURE ->
            "No session in this range recorded a temperature reading."
        SessionTrendMetric.BATTERY_DRAIN ->
            "No session in this range was both on battery throughout and at least " +
                "${BatteryDrain.MIN_RELIABLE_MILLIS / 60_000} minutes long, so no drain rate can be quoted."
        SessionTrendMetric.PLAY_TIME ->
            "No game sessions were recorded in this range."
    }
}

/**
 * The pure arithmetic behind Session Trends.
 *
 * Nothing here touches a database, a clock or Android. The repository hands in rows already bounded by
 * `started_at` and the ViewModel hands in a range and a zone; every figure the cards and the chart show is
 * produced here, which is what makes the delta rules testable without a device.
 */
object SessionTrends {

    /** A change smaller than this is reported as "no material change", not as a trend. */
    private const val FPS_FLAT_THRESHOLD = 0.5
    private const val TEMPERATURE_FLAT_THRESHOLD = 0.1
    private const val DRAIN_FLAT_THRESHOLD = 0.05
    private const val PLAY_TIME_FLAT_THRESHOLD_MINUTES = 1.0

    /** Condition changes are only claimed when both periods have at least this many sessions. */
    const val MIN_SESSIONS_FOR_CONDITIONS = 2

    private const val TEMPERATURE_CONDITION_CHANGE_DECI = 20.0
    private const val DRAIN_CONDITION_CHANGE = 1.0
    private const val CHARGING_SHARE_CONDITION_CHANGE = 0.25
    private const val DURATION_CONDITION_RELATIVE_CHANGE = 0.25
    private const val DURATION_CONDITION_ABSOLUTE_MILLIS = 5 * 60 * 1000L
    private const val FREQUENCY_CONDITION_RELATIVE_CHANGE = 0.5
    private const val FREQUENCY_CONDITION_ABSOLUTE_PER_DAY = 0.25

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

    /**
     * The current period, the immediately preceding period, and every local day in the current one.
     *
     * `endMillis` is the start of tomorrow rather than "now": a session that started earlier today belongs
     * in today's bucket, and a query bounded at the current millisecond would drop a row written a moment
     * ago. No future session exists, so the open upper bound costs nothing.
     */
    fun window(
        range: SessionTrendRange,
        nowMillis: Long,
        zone: ZoneId,
    ): SessionTrendWindow {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val firstDay = today.minusDays(range.days - 1L)
        val previousFirstDay = firstDay.minusDays(range.days.toLong())
        val start = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return SessionTrendWindow(
            startMillis = start,
            endMillis = end,
            previousStartMillis = previousFirstDay.atStartOfDay(zone).toInstant().toEpochMilli(),
            previousEndMillis = start,
            days = (0 until range.days).map { firstDay.plusDays(it.toLong()) },
        )
    }

    /** Builds the report from already-bounded rows, the best/worst sessions, and the resolved window. */
    fun build(
        range: SessionTrendRange,
        window: SessionTrendWindow,
        currentRows: List<SessionTrendAggregate>,
        previousRows: List<SessionTrendAggregate>,
        best: SessionTrendSession?,
        worst: SessionTrendSession?,
    ): SessionTrendReport {
        val current = period(currentRows)
        val previous = period(previousRows)
        return SessionTrendReport(
            range = range,
            window = window,
            current = current,
            previous = previous,
            days = days(window, currentRows),
            best = best,
            worst = worst,
            conditionChanges = conditionChanges(current, previous, range),
        )
    }

    /** Folds package-day rows into one period, weighting each measured average by its own duration. */
    fun period(rows: List<SessionTrendAggregate>): SessionTrendPeriod {
        if (rows.isEmpty()) return SessionTrendPeriod.EMPTY
        val fpsWeight = rows.sumOf { it.frameRateWeightMillis ?: 0L }
        val fpsSum = rows.sumOf { it.frameRateWeightedSum ?: 0.0 }
        val temperatureWeight = rows.sumOf { it.temperatureWeightMillis ?: 0L }
        val temperatureSum = rows.sumOf { it.temperatureWeightedSum ?: 0.0 }
        val drainCount = rows.sumOf { it.drainSessionCount }
        val drainSum = rows.sumOf { it.drainRateSum ?: 0.0 }
        return SessionTrendPeriod(
            sessionCount = rows.sumOf { it.sessionCount },
            playTimeMillis = rows.sumOf { it.playTimeMillis },
            averageFrameRate = if (fpsWeight > 0) fpsSum / fpsWeight else null,
            frameRateSessionCount = rows.sumOf { it.frameRateSessionCount },
            averageTemperatureDeciCelsius = if (temperatureWeight > 0) {
                temperatureSum / temperatureWeight
            } else {
                null
            },
            temperatureSessionCount = rows.sumOf { it.temperatureSessionCount },
            averageDrainPercentPerHour = if (drainCount > 0) drainSum / drainCount else null,
            drainSessionCount = drainCount,
            chargingSessionCount = rows.sumOf { it.chargingSessionCount },
        )
    }

    /** One entry per day in the window, in order, with absent metrics left null. */
    fun days(
        window: SessionTrendWindow,
        rows: List<SessionTrendAggregate>,
    ): List<SessionTrendDay> {
        val byDay = rows.groupBy { it.day }
        return window.days.map { date ->
            val dayRows = byDay[date].orEmpty()
            val fpsWeight = dayRows.sumOf { it.frameRateWeightMillis ?: 0L }
            val fpsSum = dayRows.sumOf { it.frameRateWeightedSum ?: 0.0 }
            val temperatureWeight = dayRows.sumOf { it.temperatureWeightMillis ?: 0L }
            val temperatureSum = dayRows.sumOf { it.temperatureWeightedSum ?: 0.0 }
            val drainCount = dayRows.sumOf { it.drainSessionCount }
            val drainSum = dayRows.sumOf { it.drainRateSum ?: 0.0 }
            SessionTrendDay(
                date = date,
                sessionCount = dayRows.sumOf { it.sessionCount },
                playTimeMillis = dayRows.sumOf { it.playTimeMillis },
                averageFrameRate = if (fpsWeight > 0) fpsSum / fpsWeight else null,
                averageTemperatureDeciCelsius = if (temperatureWeight > 0) {
                    temperatureSum / temperatureWeight
                } else {
                    null
                },
                averageDrainPercentPerHour = if (drainCount > 0) drainSum / drainCount else null,
            )
        }
    }

    /**
     * The conditions banner.
     *
     * Temperature and drain describe the device; session length, frequency and charging describe how the
     * user played. All five can move between two periods without the game changing, which is exactly why
     * the banner exists: it gives the deltas below a competing explanation instead of letting the screen
     * imply the game caused them.
     *
     * The two-session floor matters more than any threshold here. One short session can move every one of
     * these figures by an arbitrary amount, and a banner that appears after a single play is noise.
     */
    fun conditionChanges(
        current: SessionTrendPeriod,
        previous: SessionTrendPeriod,
        range: SessionTrendRange,
    ): List<String> {
        if (current.sessionCount < MIN_SESSIONS_FOR_CONDITIONS ||
            previous.sessionCount < MIN_SESSIONS_FOR_CONDITIONS
        ) {
            return emptyList()
        }
        val changes = mutableListOf<String>()

        val currentTemperature = current.averageTemperatureDeciCelsius
        val previousTemperature = previous.averageTemperatureDeciCelsius
        if (currentTemperature != null && previousTemperature != null) {
            val delta = currentTemperature - previousTemperature
            if (abs(delta) >= TEMPERATURE_CONDITION_CHANGE_DECI) {
                changes += "average temperature ${directionWord(delta)} " +
                    formatTemperature(abs(delta) / 10.0)
            }
        }

        val currentDrain = current.averageDrainPercentPerHour
        val previousDrain = previous.averageDrainPercentPerHour
        if (currentDrain != null && previousDrain != null) {
            val delta = currentDrain - previousDrain
            if (abs(delta) >= DRAIN_CONDITION_CHANGE) {
                changes += "battery drain ${directionWord(delta)} ${formatDrain(abs(delta))}"
            }
        }

        val currentCharge = current.chargingShare
        val previousCharge = previous.chargingShare
        if (currentCharge != null && previousCharge != null) {
            val delta = currentCharge - previousCharge
            if (abs(delta) >= CHARGING_SHARE_CONDITION_CHANGE) {
                changes += "charging in ${formatShare(currentCharge)} of sessions, " +
                    "${directionWord(delta)} from ${formatShare(previousCharge)}"
            }
        }

        val currentDuration = current.averageSessionMillis
        val previousDuration = previous.averageSessionMillis
        if (previousDuration > 0) {
            val relative = (currentDuration - previousDuration).toDouble() / previousDuration
            if (abs(relative) >= DURATION_CONDITION_RELATIVE_CHANGE &&
                abs(currentDuration - previousDuration) >= DURATION_CONDITION_ABSOLUTE_MILLIS
            ) {
                changes += "sessions ${if (relative > 0) "longer" else "shorter"} " +
                    "${formatPercent(abs(relative))} on average"
            }
        }

        val currentPerDay = current.sessionCount.toDouble() / range.days
        val previousPerDay = previous.sessionCount.toDouble() / range.days
        if (previousPerDay > 0) {
            val relative = (currentPerDay - previousPerDay) / previousPerDay
            if (abs(relative) >= FREQUENCY_CONDITION_RELATIVE_CHANGE &&
                abs(currentPerDay - previousPerDay) >= FREQUENCY_CONDITION_ABSOLUTE_PER_DAY
            ) {
                changes += "session frequency ${if (relative > 0) "higher" else "lower"} " +
                    "by ${formatPercent(abs(relative))}"
            }
        }

        return changes
    }

    /** Every day in the current window as a chart point. Gaps for measured metrics, zeros for play time. */
    fun series(
        report: SessionTrendReport,
        metric: SessionTrendMetric,
    ): List<SessionTrendPoint> = report.days.map { day ->
        SessionTrendPoint(
            date = day.date,
            value = when (metric) {
                SessionTrendMetric.FRAME_RATE -> day.averageFrameRate
                SessionTrendMetric.TEMPERATURE ->
                    day.averageTemperatureDeciCelsius?.div(10.0)
                SessionTrendMetric.BATTERY_DRAIN -> day.averageDrainPercentPerHour
                // A day with no session really did have zero play time; it is not a missing reading.
                SessionTrendMetric.PLAY_TIME -> day.playTimeMillis / 60_000.0
            },
            sessionCount = day.sessionCount,
            playTimeMillis = day.playTimeMillis,
        )
    }

    /** The card for one metric: value, delta, qualification and the judgement in words. */
    fun summary(
        report: SessionTrendReport,
        metric: SessionTrendMetric,
    ): SessionTrendSummary {
        val currentRaw = rawValue(metric, report.current)
        val previousRaw = rawValue(metric, report.previous)
        val delta = if (currentRaw != null && previousRaw != null) currentRaw - previousRaw else null
        val relative = if (delta != null && previousRaw != null && previousRaw != 0.0) {
            delta / previousRaw
        } else {
            null
        }
        val direction = directionOf(metric, delta)
        val improvement = when {
            direction == SessionTrendDirection.UNKNOWN ||
                direction == SessionTrendDirection.FLAT -> null
            metric.betterDirection == null -> null
            else -> direction == metric.betterDirection
        }

        val valueText = when {
            currentRaw == null -> "—"
            metric == SessionTrendMetric.PLAY_TIME ->
                Formatters.durationTotal(report.current.playTimeMillis)
            currentRaw.isNaN() -> "—"
            else -> formatValueText(metric, currentRaw)
        }
        val deltaText = when {
            currentRaw == null -> null
            previousRaw == null -> null
            direction == SessionTrendDirection.FLAT -> "No material change"
            else -> shortDeltaText(metric, abs(delta ?: 0.0), direction, improvement)
        }
        val detail = detailText(metric, report, currentRaw != null, previousRaw != null)
        val spoken = buildString {
            append(metric.label)
            append(": ")
            append(valueText)
            if (deltaText != null) {
                append(". ")
                append(spokenDelta(metric, delta, direction, improvement, report.range))
            }
            append(". ")
            append(detail)
        }
        return SessionTrendSummary(
            metric = metric,
            currentValue = currentRaw,
            previousValue = previousRaw,
            absoluteDelta = delta,
            relativeChange = relative,
            direction = direction,
            isImprovement = improvement,
            valueText = valueText,
            deltaText = deltaText,
            detail = detail,
            spoken = spoken,
        )
    }

    /** One day's value as the table reads it, or null when that day measured nothing for the metric. */
    fun pointText(
        metric: SessionTrendMetric,
        point: SessionTrendPoint,
    ): String? = point.value?.let { value ->
        if (metric == SessionTrendMetric.PLAY_TIME) {
            Formatters.durationTotal(point.playTimeMillis)
        } else {
            formatValueText(metric, value)
        }
    }

    /** One end of the chart's vertical scale, in the metric's own unit. */
    fun axisText(metric: SessionTrendMetric, value: Double): String = when (metric) {
        SessionTrendMetric.FRAME_RATE -> String.format(Locale.US, "%.0f fps", value)
        SessionTrendMetric.TEMPERATURE -> formatTemperature(value)
        SessionTrendMetric.BATTERY_DRAIN -> String.format(Locale.US, "%.1f%%/h", value)
        SessionTrendMetric.PLAY_TIME ->
            Formatters.durationTotal((value * 60_000.0).roundToLong())
    }

    /** A screen-reader summary of the chart, naming the high, the low and how many days had a reading. */
    fun chartSummary(
        report: SessionTrendReport,
        metric: SessionTrendMetric,
    ): String {
        val points = series(report, metric)
        val known = points.mapNotNull { point -> point.value?.let { point to it } }
        if (known.isEmpty()) {
            return "No ${metric.label.lowercase(Locale.US)} readings in the last ${report.range.days} days."
        }
        val high = known.maxByOrNull { it.second } ?: known.first()
        val low = known.minByOrNull { it.second } ?: known.first()
        val first = points.first().date
        val last = points.last().date
        val highText = formatPointValue(metric, high.second, high.first.playTimeMillis)
        val lowText = formatPointValue(metric, low.second, low.first.playTimeMillis)
        val rangeText = "${formatDate(first)} to ${formatDate(last)}"
        val same = abs(high.second - low.second) < 1e-9
        return if (same) {
            "Daily ${metric.label.lowercase(Locale.US)} from $rangeText. " +
                "Every day with a reading was $highText. " +
                "${known.size} of ${report.range.days} days have a reading."
        } else {
            "Daily ${metric.label.lowercase(Locale.US)} from $rangeText. " +
                "Highest $highText on ${formatDate(high.first.date)}; " +
                "lowest $lowText on ${formatDate(low.first.date)}. " +
                "${known.size} of ${report.range.days} days have a reading."
        }
    }

    private fun rawValue(metric: SessionTrendMetric, period: SessionTrendPeriod): Double? =
        when (metric) {
            SessionTrendMetric.FRAME_RATE -> period.averageFrameRate
            SessionTrendMetric.TEMPERATURE ->
                period.averageTemperatureDeciCelsius?.div(10.0)
            SessionTrendMetric.BATTERY_DRAIN ->
                period.averageDrainPercentPerHour?.toDouble()
            SessionTrendMetric.PLAY_TIME -> period.playTimeMillis / 60_000.0
        }

    private fun directionOf(
        metric: SessionTrendMetric,
        delta: Double?,
    ): SessionTrendDirection {
        if (delta == null) return SessionTrendDirection.UNKNOWN
        val threshold = when (metric) {
            SessionTrendMetric.FRAME_RATE -> FPS_FLAT_THRESHOLD
            SessionTrendMetric.TEMPERATURE -> TEMPERATURE_FLAT_THRESHOLD
            SessionTrendMetric.BATTERY_DRAIN -> DRAIN_FLAT_THRESHOLD
            SessionTrendMetric.PLAY_TIME -> PLAY_TIME_FLAT_THRESHOLD_MINUTES
        }
        return when {
            abs(delta) < threshold -> SessionTrendDirection.FLAT
            delta > 0 -> SessionTrendDirection.UP
            else -> SessionTrendDirection.DOWN
        }
    }

    private fun shortDeltaText(
        metric: SessionTrendMetric,
        magnitude: Double,
        direction: SessionTrendDirection,
        improvement: Boolean?,
    ): String {
        val arrow = if (direction == SessionTrendDirection.UP) "▲" else "▼"
        val amount = when (metric) {
            SessionTrendMetric.FRAME_RATE -> String.format(Locale.US, "%.1f fps", magnitude)
            SessionTrendMetric.TEMPERATURE -> formatTemperature(magnitude)
            SessionTrendMetric.BATTERY_DRAIN -> formatDrain(magnitude)
            SessionTrendMetric.PLAY_TIME -> durationOfMinutes(magnitude)
        }
        val judgement = when {
            metric.betterDirection == null -> "vs previous"
            improvement == true -> "better"
            improvement == false -> "worse"
            else -> "vs previous"
        }
        return "$arrow $amount $judgement"
    }

    private fun spokenDelta(
        metric: SessionTrendMetric,
        delta: Double?,
        direction: SessionTrendDirection,
        improvement: Boolean?,
        range: SessionTrendRange,
    ): String {
        if (delta == null || direction == SessionTrendDirection.UNKNOWN) {
            return "No previous ${range.days} days to compare with"
        }
        val previous = "the previous ${range.days} days"
        if (direction == SessionTrendDirection.FLAT) {
            return "No material change from $previous"
        }
        val magnitude = abs(delta)
        val amount = when (metric) {
            SessionTrendMetric.FRAME_RATE -> String.format(Locale.US, "%.1f fps", magnitude)
            SessionTrendMetric.TEMPERATURE -> formatTemperature(magnitude)
            SessionTrendMetric.BATTERY_DRAIN -> formatDrain(magnitude)
            SessionTrendMetric.PLAY_TIME -> durationOfMinutes(magnitude)
        }
        val movement = if (direction == SessionTrendDirection.UP) "Up" else "Down"
        val judgement = when {
            metric.betterDirection == null -> ""
            improvement == true -> ", which is better"
            improvement == false -> ", which is worse"
            else -> ""
        }
        return "$movement $amount$judgement compared with $previous"
    }

    private fun detailText(
        metric: SessionTrendMetric,
        report: SessionTrendReport,
        hasCurrent: Boolean,
        hasPrevious: Boolean,
    ): String {
        val period = report.current
        val basis = when (metric) {
            SessionTrendMetric.FRAME_RATE -> when {
                !hasCurrent -> "No session in this range recorded a frame rate."
                else -> "Averaged over " +
                    Formatters.count(period.frameRateSessionCount, "session") +
                    " that recorded a frame rate."
            }
            SessionTrendMetric.TEMPERATURE -> when {
                !hasCurrent -> "No session in this range recorded a temperature reading."
                else -> "Averaged over " +
                    Formatters.count(period.temperatureSessionCount, "session") +
                    " with a temperature reading."
            }
            SessionTrendMetric.BATTERY_DRAIN -> when {
                !hasCurrent -> "No session was long enough, and on battery throughout, to measure a rate."
                else -> "Averaged over " +
                    Formatters.count(period.drainSessionCount, "session") +
                    " where a rate could be measured."
            }
            SessionTrendMetric.PLAY_TIME -> "Across " +
                Formatters.count(period.sessionCount, "session") +
                "."
        }
        if (!hasCurrent) return basis
        if (!hasPrevious) {
            return "$basis There is no previous ${report.range.days}-day reading to compare with."
        }
        return "$basis Compared with the previous ${report.range.days} days."
    }

    private fun formatValueText(metric: SessionTrendMetric, value: Double): String = when (metric) {
        SessionTrendMetric.FRAME_RATE -> String.format(Locale.US, "%.1f fps", value)
        SessionTrendMetric.TEMPERATURE -> formatTemperature(value)
        SessionTrendMetric.BATTERY_DRAIN -> formatDrain(value)
        SessionTrendMetric.PLAY_TIME -> durationOfMinutes(value)
    }

    private fun formatPointValue(
        metric: SessionTrendMetric,
        value: Double,
        playTimeMillis: Long,
    ): String = if (metric == SessionTrendMetric.PLAY_TIME) {
        Formatters.durationTotal(playTimeMillis)
    } else {
        formatValueText(metric, value)
    }

    private fun formatTemperature(value: Double): String =
        String.format(Locale.US, "%.1f°C", value)

    private fun formatDrain(value: Double): String =
        String.format(Locale.US, "%.1f%%/h", value)

    private fun durationOfMinutes(minutes: Double): String =
        Formatters.durationCoarse((minutes * 60_000.0).roundToLong())

    private fun formatShare(value: Double): String =
        Formatters.percentValue((value * 100).toFloat(), decimals = 0)

    private fun formatPercent(value: Double): String =
        String.format(Locale.US, "%.0f%%", value * 100)

    private fun directionWord(delta: Double): String = if (delta > 0) "up" else "down"

    private fun formatDate(date: LocalDate): String = date.format(dayFormatter)
}
