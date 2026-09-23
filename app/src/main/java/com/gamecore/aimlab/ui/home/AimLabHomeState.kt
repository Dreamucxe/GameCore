package com.gamecore.aimlab.ui.home

import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.RecordMetric
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode

/**
 * Everything the Aim Lab home screen draws, already reduced from the repository's flows.
 *
 * The screen is dumb: it renders this and calls back. Every figure here is derived from stored sessions and
 * personal records — never invented — and [hasHistory] is the single switch the screen reads to decide
 * between the summary strip and the inviting empty banner. When there is no history the screen must show the
 * banner rather than a strip of zeroes (§1/§30).
 *
 * [loading] is the first frame before the flows have emitted, kept distinct from "loaded and empty": the
 * empty banner is a real, considered state and must not flash up for the split second before the database
 * answers.
 *
 * [isCompact] is the user's own density setting, carried here rather than read from a theme local so the
 * screen stays testable and so Aim Lab spaces its cards exactly as the rest of the app does (§8: the tab
 * keeps all of its content and takes the design tokens). It is a display preference and nothing else — it
 * changes no Aim Lab behaviour, no orientation handling and no dormancy rule.
 */
data class AimLabHomeState(
    val loading: Boolean = true,
    val summary: AimLabSummary = AimLabSummary.NONE,
    val isCompact: Boolean = false,
) {
    /** True once at least one valid session has been recorded — the gate between the strip and the banner. */
    val hasHistory: Boolean get() = summary.totalSessions > 0
}

/**
 * The real, computed summary at the top of the home screen.
 *
 * Every field is a fact about the stored history:
 *  - [totalSessions] / [totalTrainingMillis] are counted and summed across the recorded sessions.
 *  - [lastSession] is the most recently started one, for "last session" and its "when".
 *  - the four bests are pulled from [PersonalRecord]s, so they honour the same record separation and
 *    higher/lower-is-better direction the record book uses. A best that has never been set is null, and the
 *    screen renders null as the words "Not set" — never a zero, and never a bare em-dash, which a screen
 *    reader does not announce.
 *
 * Reaction is the one where *lower* wins, so [bestReactionMillis] is the smallest fastest-reaction value on
 * record; the others are the largest.
 */
data class AimLabSummary(
    val totalSessions: Int = 0,
    val totalTrainingMillis: Long = 0L,
    val lastSession: SessionSummary? = null,
    val bestReactionMillis: Long? = null,
    val bestAccuracyPercent: Int? = null,
    val bestTrackingScore: Int? = null,
    val bestFlickScore: Int? = null,
) {
    companion object {
        val NONE = AimLabSummary()

        /**
         * Folds the observed sessions and records into the summary.
         *
         * Totals come from the sessions list (so training time is the sum of real durations, not an
         * estimate); the bests come from the records list, which is already the correct per-key best. The
         * accuracy record is stored as a 0..1 fraction, so it is scaled to a whole percent here to match how
         * the rest of the app shows accuracy. [count] is the repository's authoritative `sessionCount`; the
         * displayed total takes the larger of it and the observed list size so the two can never disagree in
         * a way that reads as a bug.
         */
        fun from(
            sessions: List<SessionSummary>,
            records: List<PersonalRecord>,
            count: Int,
        ): AimLabSummary {
            if (count <= 0 && sessions.isEmpty() && records.isEmpty()) return NONE

            val totalMillis = sessions.sumOf { it.durationMillis }
            val last = sessions.maxByOrNull { it.startedAtMillis }

            val bestReaction = records
                .filter { it.metric == RecordMetric.FASTEST_REACTION }
                .minByOrNull { it.value }
                ?.value
                ?.toLong()

            val bestAccuracy = records
                .filter { it.metric == RecordMetric.ACCURACY }
                .maxByOrNull { it.value }
                ?.value
                ?.let { (it * 100f).toInt() }

            val bestTracking = records
                .filter { it.metric == RecordMetric.TRACKING_SCORE }
                .maxByOrNull { it.value }
                ?.value
                ?.toInt()

            // Flick score is the SCORE metric filed under the flick mode, kept distinct from the reaction
            // and movement modes that also produce a SCORE record.
            val bestFlick = records
                .filter { it.metric == RecordMetric.SCORE && it.mode == TrainingMode.FLICK }
                .maxByOrNull { it.value }
                ?.value
                ?.toInt()

            return AimLabSummary(
                totalSessions = count.coerceAtLeast(sessions.size),
                totalTrainingMillis = totalMillis,
                lastSession = last,
                bestReactionMillis = bestReaction,
                bestAccuracyPercent = bestAccuracy,
                bestTrackingScore = bestTracking,
                bestFlickScore = bestFlick,
            )
        }
    }
}
