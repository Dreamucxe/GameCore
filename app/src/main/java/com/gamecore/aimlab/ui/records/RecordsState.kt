package com.gamecore.aimlab.ui.records

import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.RecordMetric
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.core.common.Formatters
import kotlin.math.roundToInt

/**
 * Everything the personal-records screen draws, in one value.
 *
 * The screen has exactly one source of truth — [records], the rows the repository actually stored — and the
 * rule that shapes this whole file is that **a record exists only because a session beat something**. A
 * [PersonalRecord] is written by `PersonalRecordBook.challenge` and by nothing else, so there is no such
 * thing here as a record with no session behind it. Consequently:
 *
 *  - a metric the user has never scored on has no row at all, rather than a row reading "0" or "—";
 *  - a mode never trained is not a group with an empty body;
 *  - an empty [records] means the empty state, not a table of dashes (§1/§30).
 *
 * The filters follow the same rule. [modesWithRecords] and [difficultiesWithRecords] are read off the stored
 * rows, so the screen can only offer a filter that has something behind it — a "Gyro" chip that filters to
 * nothing would be the UI claiming a category it cannot fill. Both filters are "no filter" when null, which
 * is the honest default and not a synthesised "All" member of either enum.
 */
data class RecordsState(
    val loading: Boolean = true,
    val records: List<PersonalRecord> = emptyList(),
    val modeFilter: TrainingMode? = null,
    val difficultyFilter: Difficulty? = null,
    val confirmingReset: Boolean = false,
    val resetting: Boolean = false,
    val reset: Boolean = false,
) {

    /** Whether the user has set any record at all. What decides table versus empty state. */
    val hasAny: Boolean get() = records.isNotEmpty()

    /** Whether either filter is narrowing the list. Drives the "showing a subset" note. */
    val filtered: Boolean get() = modeFilter != null || difficultyFilter != null

    /**
     * The modes that actually have a record, in the enum's own order.
     *
     * Derived from the rows rather than from `TrainingMode.entries`, so the filter never offers a mode the
     * user has no record in. `FREE_PRACTICE` cannot appear because it is unscored and the record book returns
     * no candidates for it — it is excluded by the data, not by a special case here.
     */
    val modesWithRecords: List<TrainingMode>
        get() = TrainingMode.entries.filter { mode -> records.any { it.mode == mode } }

    /** The difficulties that actually have a record, in the enum's own order. */
    val difficultiesWithRecords: List<Difficulty>
        get() = Difficulty.entries.filter { level -> records.any { it.difficulty == level } }

    /** The rows left after the filters, ordered so a group reads the same way every time. */
    val visible: List<PersonalRecord>
        get() = records
            .filter { modeFilter == null || it.mode == modeFilter }
            .filter { difficultyFilter == null || it.difficulty == difficultyFilter }
            .sortedWith(
                // Metric first, then difficulty, then weapon: a stable, meaningful order. Sorting by value
                // would be meaningless, because a score and a reaction time are not comparable quantities.
                compareBy<PersonalRecord>(
                    { it.metric.ordinal },
                    { it.difficulty.ordinal },
                    { it.weaponName.orEmpty() },
                ),
            )

    /** The visible rows grouped by mode, in the enum's order, with no empty group. */
    val groups: List<RecordGroup>
        get() = visible
            .groupBy { it.mode }
            .entries
            .sortedBy { it.key.ordinal }
            .map { RecordGroup(mode = it.key, records = it.value) }

    /**
     * True when there are records but the filters hide all of them.
     *
     * A different situation from having none, and it gets a different message: the first is "train to set
     * one", the second is "widen the filter".
     */
    val noMatches: Boolean get() = hasAny && visible.isEmpty()

    /** Whether the mode filter is worth drawing. One mode is not a choice. */
    val offersModeFilter: Boolean get() = modesWithRecords.size > 1

    /** Whether the difficulty filter is worth drawing. */
    val offersDifficultyFilter: Boolean get() = difficultiesWithRecords.size > 1

    /** The option lists, with the leading null meaning "no filter" rather than a fabricated enum member. */
    val modeOptions: List<TrainingMode?> get() = listOf<TrainingMode?>(null) + modesWithRecords
    val difficultyOptions: List<Difficulty?> get() = listOf<Difficulty?>(null) + difficultiesWithRecords
}

/** One mode's records. Only ever constructed from rows that exist, so it is never empty. */
data class RecordGroup(
    val mode: TrainingMode,
    val records: List<PersonalRecord>,
)

/**
 * Turns a record's raw [Float] into the string for its metric — and says which way "better" runs.
 *
 * A record's value is a bare float whose meaning depends entirely on its [RecordMetric]: 212.0 is a reaction
 * time in milliseconds, 0.82 is an accuracy fraction, 96000.0 is a session length in millis, and 1840.0 is a
 * score. Formatting them all one way would misreport three of the four, so each metric is formatted through
 * the existing [Formatters] function for its own unit.
 *
 * [improvement] is where [RecordMetric.higherIsBetter] is honoured. `FASTEST_REACTION` is the metric where a
 * *lower* value wins, so the margin over the beaten record is `previous - value` there and `value - previous`
 * everywhere else. Getting that backwards would print "18 ms faster" as a regression on the one metric the
 * user most wants to see falling.
 */
object RecordFormat {

    /** The record's value, in the unit its metric is actually measured in. */
    fun value(metric: RecordMetric, value: Float): String = when (metric) {
        RecordMetric.ACCURACY -> Formatters.percent(value, 0)
        RecordMetric.FASTEST_REACTION -> Formatters.millis(value.roundToInt())
        RecordMetric.LONGEST_SESSION -> Formatters.durationCoarse(value.toLong())
        RecordMetric.SCORE,
        RecordMetric.TRACKING_SCORE,
        RecordMetric.RECOIL_SCORE,
        RecordMetric.GYRO_SCORE,
        -> value.roundToInt().toString()
    }

    /**
     * How much [value] beat [previous] by, worded for the metric's direction — or null if it did not.
     *
     * The margin is measured in the metric's own direction, so it is positive for a genuine improvement on
     * every metric including the lower-is-better one. A stored row whose margin is not positive is not
     * described as an improvement at all: null comes back and the caller shows the beaten value plainly
     * rather than asserting progress the numbers do not support.
     */
    fun improvement(metric: RecordMetric, value: Float, previous: Float): String? {
        val margin = if (metric.higherIsBetter) value - previous else previous - value
        if (margin <= 0f) return null
        return when (metric) {
            RecordMetric.ACCURACY -> "${Formatters.percent(margin, 1)} higher"
            // The one lower-is-better metric: the value fell, and falling is the improvement.
            RecordMetric.FASTEST_REACTION -> "${Formatters.millis(margin.roundToInt())} faster"
            RecordMetric.LONGEST_SESSION -> "${Formatters.durationCoarse(margin.toLong())} longer"
            RecordMetric.SCORE,
            RecordMetric.TRACKING_SCORE,
            RecordMetric.RECOIL_SCORE,
            RecordMetric.GYRO_SCORE,
            -> "${margin.roundToInt()} higher"
        }
    }

    /**
     * What "better" means for this metric, in words, for the line that explains a group.
     *
     * Stated rather than assumed, because the screen shows one metric where the smaller number is the
     * achievement alongside five where the bigger one is.
     */
    fun direction(metric: RecordMetric): String =
        if (metric.higherIsBetter) "higher is better" else "lower is better"

    /** When the record was set: the day in the user's terms, and the time it happened. */
    fun achieved(epochMillis: Long): String =
        "${Formatters.relativeDay(epochMillis)} · ${Formatters.clockTime(epochMillis)}"
}
