package com.gamecore.domain.intelligence

import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.LearningSummary

/**
 * Turns locally recorded outcomes into a confidence adjustment, and nothing else.
 *
 * This is the whole of "learning" in Smart Intelligence: no online model, no gradient, no hidden
 * weights. Every recommendation carries a key, every applied/tested action is recorded against that
 * key, and this object counts what actually happened. A recommendation whose measured outcome
 * regressed loses confidence; one that improved gains it. The cap is deliberately small because a
 * handful of sessions on one device is weak evidence, and a strong adjustment would let three lucky
 * runs drown out the hardware measurements.
 *
 * One bad benchmark must not block an action. A regression is often a thermal fluke, a background
 * update or a different part of the game, so a single regression costs confidence and nothing more.
 * [LearningSummary.blocked] is only set when the record shows a repeated, consistent pattern.
 */
object LearningEngine {

    /** The most confidence a good history can add. */
    const val MAX_BOOST = 18

    /** The most confidence a bad history can take away before the block rule applies. */
    const val MAX_PENALTY = 24

    /** Confidence added per measured improvement. */
    const val BOOST_PER_IMPROVEMENT = 6

    /** Confidence taken per measured regression. */
    const val PENALTY_PER_REGRESSION = 12

    /** At least this many measured regressions before an action can be blocked at all. */
    const val BLOCK_REGRESSIONS = 3

    /**
     * How many more regressions than improvements the block rule needs.
     *
     * Three regressions and one improvement is still a bad pattern; three and two is not yet
     * decisive, so it is shown as low confidence instead of refused.
     */
    const val BLOCK_MARGIN = 2

    /** Summarizes every action that has at least one recorded event. */
    fun summarizeAll(records: List<LearningRecord>): Map<String, LearningSummary> =
        records.groupBy { it.actionKey }
            .mapValues { (actionKey, forAction) -> summarize(forAction, actionKey) }
            .filterValues {
                it.generatedCount + it.appliedCount + it.attemptedCount +
                    it.testedCount + it.ignoredCount > 0
            }

    /**
     * Summarizes one action's history.
     *
     * [actionKey] is passed separately so a caller can summarize an action with no records at all
     * and get an honest zero summary rather than a missing entry.
     */
    fun summarize(records: List<LearningRecord>, actionKey: String): LearningSummary {
        val mine = records.filter { it.actionKey == actionKey }
        val generated = mine.count { it.decision == LearningDecision.GENERATED }
        val applied = mine.count { it.decision == LearningDecision.APPLIED }
        val attempted = mine.count { it.decision == LearningDecision.ATTEMPTED }
        val ignored = mine.count { it.decision == LearningDecision.IGNORED }
        val tested = mine.count { it.decision == LearningDecision.TESTED }
        val improved = mine.count { it.outcome == LearningOutcome.IMPROVED }
        val neutral = mine.count { it.outcome == LearningOutcome.NEUTRAL }
        val regressed = mine.count { it.outcome == LearningOutcome.REGRESSED }
        val inconclusive = mine.count { it.outcome == LearningOutcome.INCONCLUSIVE }
        // Generated, applied and dismissed rows intentionally have no outcome. Only an explicit
        // test whose result could not be classified is an "unknown outcome" worth naming.
        val unknown = mine.count {
            it.decision == LearningDecision.TESTED && it.outcome == LearningOutcome.UNKNOWN
        }

        val rawAdjustment = improved * BOOST_PER_IMPROVEMENT - regressed * PENALTY_PER_REGRESSION
        val adjustment = rawAdjustment.coerceIn(-MAX_PENALTY, MAX_BOOST)
        val blocked = regressed >= BLOCK_REGRESSIONS &&
            regressed >= improved + BLOCK_MARGIN

        return LearningSummary(
            actionKey = actionKey,
            generatedCount = generated,
            appliedCount = applied,
            attemptedCount = attempted,
            ignoredCount = ignored,
            testedCount = tested,
            improvedCount = improved,
            neutralCount = neutral,
            regressedCount = regressed,
            inconclusiveCount = inconclusive,
            confidenceAdjustment = adjustment,
            blocked = blocked,
            explanation = explain(
                generated = generated,
                applied = applied,
                attempted = attempted,
                ignored = ignored,
                tested = tested,
                improved = improved,
                neutral = neutral,
                regressed = regressed,
                inconclusive = inconclusive,
                unknown = unknown,
                blocked = blocked,
            ),
        )
    }

    private fun explain(
        generated: Int,
        applied: Int,
        attempted: Int,
        ignored: Int,
        tested: Int,
        improved: Int,
        neutral: Int,
        regressed: Int,
        inconclusive: Int,
        unknown: Int,
        blocked: Boolean,
    ): String {
        if (applied + attempted + ignored + tested == 0) {
            return if (generated > 0) {
                "Generated $generated time${plural(generated)}, with no user decision or measured " +
                    "outcome yet. Confidence still comes only from the device readings."
            } else {
                "No outcomes recorded for this action yet. Its confidence comes only from the " +
                    "device readings."
            }
        }
        val parts = buildList {
            if (generated > 0) add("generated $generated time${plural(generated)}")
            if (improved > 0) add("$improved measured improvement${plural(improved)}")
            if (neutral > 0) add("$neutral neutral result${plural(neutral)}")
            if (regressed > 0) add("$regressed measured regression${plural(regressed)}")
            if (inconclusive > 0) add("$inconclusive inconclusive run${plural(inconclusive)}")
            if (unknown > 0) add("$unknown unclassified outcome${plural(unknown)}")
            if (applied > 0) add("$applied application${plural(applied)}")
            if (attempted > 0) {
                add("$attempted unconfirmed attempt${plural(attempted)}")
            }
            if (tested > 0) add("$tested explicit test${plural(tested)}")
            if (ignored > 0) add("$ignored dismissal${plural(ignored)}")
        }
        val body = parts.joinToString(", ")
        return when {
            blocked ->
                "Recorded history: $body. Repeated measured regressions outweigh any measured " +
                    "improvement, so this action is not recommended for this device until the " +
                    "history changes or a new test contradicts it."
            regressed > improved && regressed > 0 ->
                "Recorded history: $body. The negative results lower confidence but do not block " +
                    "the action; one bad run can be heat or background load."
            improved > 0 ->
                "Recorded history: $body. The measured improvements raise confidence within the cap."
            else ->
                "Recorded history: $body. Neutral or inconclusive results leave confidence where the " +
                    "device readings put it."
        }
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}
