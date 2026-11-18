package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.ComparisonVerdict
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.MetricDelta
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RecommendationAction
import com.gamecore.core.model.TestedAction
import com.gamecore.core.model.TestedActionCandidate
import com.gamecore.core.model.TestedActionResolution
import com.gamecore.core.model.TestedActionStatus
import com.gamecore.core.model.TestedActionTarget
import kotlin.math.abs

/**
 * Decides which single optimization action a benchmark capture may be attributed to.
 *
 * The rule is deliberately narrower than "the newest applied row". A capture can only be attributed
 * when exactly one confirmed change was applied after the previous run of the same profile and is
 * still effective at capture time. If two different changes happened in that window, the measured
 * difference belongs to both of them and naming either one would be a false claim. If a change was
 * only attempted, or was written without a read-back, it is not a confirmed optimization. A profile
 * field is not effective until a game session has started after it was saved, so it stays pending
 * until that session exists.
 *
 * Everything here is pure: the repository supplies rows and sessions, the result is persisted on
 * the benchmark row, and no later query can rewrite it.
 */
object BenchmarkAttribution {

    const val AVERAGE_FPS = "Average FPS"
    const val ONE_PERCENT_LOW_FPS = "1% low FPS"
    const val LOWEST_WINDOW_FPS = "Lowest window"
    const val JANK = "Jank"
    const val CPU = "CPU"
    const val TEMPERATURE = "Temperature"
    const val BATTERY_DRAIN = "Battery drain"

    /** The metrics an action is expected to move, most specific first. Empty for an unknown key. */
    fun metricsFor(actionKey: String): List<String> = when (RecommendationAction.of(actionKey)) {
        RecommendationAction.PIN_PEAK_REFRESH_RATE ->
            listOf(AVERAGE_FPS, ONE_PERCENT_LOW_FPS, LOWEST_WINDOW_FPS)
        RecommendationAction.PIN_LOWEST_REFRESH_RATE ->
            listOf(BATTERY_DRAIN, TEMPERATURE, AVERAGE_FPS)
        RecommendationAction.ENABLE_BATTERY_SAVER ->
            listOf(BATTERY_DRAIN, TEMPERATURE, CPU)
        RecommendationAction.KEEP_FULL_PERFORMANCE ->
            listOf(AVERAGE_FPS, ONE_PERCENT_LOW_FPS, LOWEST_WINDOW_FPS)
        RecommendationAction.DISABLE_ANIMATIONS ->
            listOf(JANK, CPU, AVERAGE_FPS)
        RecommendationAction.ENABLE_THERMAL_DOWNSHIFT ->
            listOf(TEMPERATURE, ONE_PERCENT_LOW_FPS, LOWEST_WINDOW_FPS, AVERAGE_FPS)
        RecommendationAction.SET_CPU_AFFINITY ->
            listOf(ONE_PERCENT_LOW_FPS, JANK, AVERAGE_FPS)
        RecommendationAction.FREE_RAM_ON_LAUNCH ->
            listOf(JANK, CPU, AVERAGE_FPS)
        null -> emptyList()
    }

    /** Whether the action is a device setting or a per-game profile field. */
    fun targetFor(actionKey: String): TestedActionTarget? =
        when (RecommendationAction.of(actionKey)?.targetKind()) {
            TargetKind.DEVICE -> TestedActionTarget.DEVICE
            TargetKind.PROFILE -> TestedActionTarget.PROFILE
            TargetKind.NONE, null -> null
        }

    /**
     * Resolves the one action a capture may test.
     *
     * [records] should already be limited to the window after the previous run, because an action
     * that was already in the baseline belongs to the baseline, not to this comparison.
     */
    fun resolve(
        records: List<LearningRecord>,
        gamePackage: String?,
        goal: OptimizationGoal,
        sessions: List<GameSession>,
        atMillis: Long,
    ): TestedActionResolution {
        val forGame = records.filter { it.gamePackage == gamePackage }
        val candidates = mutableListOf<TestedActionCandidate>()
        var uncertain = false

        for (record in forGame.sortedBy { it.createdAtMillis }) {
            when (record.decision) {
                LearningDecision.APPLIED -> {
                    val action = RecommendationAction.of(record.actionKey)
                    if (action == null) {
                        candidates += candidate(
                            record,
                            TestedActionStatus.UNKNOWN_ACTION,
                            "This action key is not recognised by this build.",
                        )
                        continue
                    }
                    val metrics = metricsFor(record.actionKey)
                    val target = targetFor(record.actionKey)
                    if (metrics.isEmpty() || target == null) {
                        candidates += candidate(
                            record,
                            TestedActionStatus.UNKNOWN_ACTION,
                            "This action has no metric this build can attribute.",
                        )
                        continue
                    }
                    if (record.goal != goal) {
                        candidates += candidate(
                            record,
                            TestedActionStatus.WRONG_GOAL,
                            "The change was applied under a different optimization goal.",
                        )
                        continue
                    }
                    val confirmedAt = when (target) {
                        TestedActionTarget.DEVICE -> record.createdAtMillis
                        TestedActionTarget.PROFILE -> {
                            val session = effectSession(record, sessions, atMillis)
                            if (session == null) {
                                candidates += candidate(
                                    record,
                                    TestedActionStatus.PENDING_GAME_START,
                                    "The profile change has not taken effect in a game session yet.",
                                )
                                continue
                            }
                            session.startedAtMillis
                        }
                    }
                    candidates += candidate(
                        record = record,
                        status = TestedActionStatus.ELIGIBLE,
                        reason = "Confirmed and effective before this capture.",
                        testedAction = TestedAction(
                            appliedRecordId = record.id,
                            actionKey = record.actionKey,
                            title = action.title,
                            gamePackage = record.gamePackage,
                            goal = record.goal,
                            appliedAtMillis = record.createdAtMillis,
                            metricNames = metrics,
                            target = target,
                            effectConfirmedAtMillis = confirmedAt,
                        ),
                    )
                }

                LearningDecision.ATTEMPTED -> candidates += candidate(
                    record,
                    TestedActionStatus.FAILED,
                    "The change was refused, blocked or did not take effect.",
                )

                LearningDecision.UNCONFIRMED -> {
                    uncertain = true
                    candidates += candidate(
                        record,
                        TestedActionStatus.UNCONFIRMED,
                        "The change was written but could not be read back, so its effect is unknown.",
                    )
                }

                LearningDecision.UNKNOWN -> {
                    uncertain = true
                    candidates += candidate(
                        record,
                        TestedActionStatus.UNCONFIRMED,
                        "A row from another build has an unrecognised decision.",
                    )
                }

                LearningDecision.GENERATED,
                LearningDecision.IGNORED,
                LearningDecision.TESTED,
                -> Unit
            }
        }

        // A later application of the same setting supersedes an earlier one. Re-applying the same
        // action is not a second change; a different action in the same group is.
        val superseded = mutableSetOf<Long>()
        candidates.filter { it.status == TestedActionStatus.ELIGIBLE }
            .groupBy { conflictGroup(it.actionKey) }
            .values
            .forEach { group ->
                val newest = group.maxWithOrNull(
                    compareBy<TestedActionCandidate> { it.testedAction?.appliedAtMillis ?: Long.MIN_VALUE }
                        .thenBy { it.recordId },
                )
                group.filter { it.recordId != newest?.recordId }
                    .forEach { superseded += it.recordId }
            }
        val marked = candidates.map { candidate ->
            if (candidate.recordId in superseded) {
                candidate.copy(
                    status = TestedActionStatus.SUPERSEDED,
                    reason = "A later change to the same setting replaced this one.",
                    testedAction = null,
                )
            } else {
                candidate
            }
        }

        val eligible = marked.filter { it.status == TestedActionStatus.ELIGIBLE }
        return when {
            uncertain -> TestedActionResolution(
                testedAction = null,
                candidates = marked,
                reason = "A change could not be confirmed, so this capture cannot be attributed " +
                    "to one action.",
            )

            eligible.size > 1 -> TestedActionResolution(
                testedAction = null,
                candidates = marked.map {
                    if (it.status == TestedActionStatus.ELIGIBLE) {
                        it.copy(
                            status = TestedActionStatus.AMBIGUOUS,
                            reason = "More than one confirmed change was applied since the " +
                                "previous run.",
                            testedAction = null,
                        )
                    } else {
                        it
                    }
                },
                reason = "More than one confirmed change was applied since the previous run, " +
                    "so this capture cannot be attributed to one action.",
            )

            eligible.size == 1 -> TestedActionResolution(
                testedAction = eligible.single().testedAction,
                candidates = marked,
            )

            else -> TestedActionResolution(
                testedAction = null,
                candidates = marked,
                reason = when {
                    marked.any { it.status == TestedActionStatus.PENDING_GAME_START } ->
                        "The profile change has not taken effect in a game session yet."
                    marked.any { it.status == TestedActionStatus.SUPERSEDED } ->
                        "The only change in this window was replaced by a later one."
                    else -> "No confirmed optimization was applied since the previous run."
                },
            )
        }
    }

    /**
     * The relevant measured delta for a tested action, and the outcome it supports.
     *
     * The comparison's FPS verdict is used only for the average-FPS metric. A battery or thermal
     * action is judged by its own metric, with a conservative floor below which the result is
     * neutral rather than a claimed improvement or regression. Null means no relevant metric was
     * measured, so the run teaches the engine nothing about this action.
     */
    fun evidence(testedAction: TestedAction, comparison: BenchmarkComparison): TestedActionResult? {
        if (comparison.verdict == ComparisonVerdict.NOT_COMPARABLE) return null
        val delta = testedAction.metricNames.asSequence()
            .mapNotNull { name -> comparison.deltas.firstOrNull { it.label == name } }
            .firstOrNull()
            ?: return null
        val outcome = when {
            delta.label == AVERAGE_FPS -> when (comparison.verdict) {
                ComparisonVerdict.IMPROVED -> LearningOutcome.IMPROVED
                ComparisonVerdict.REGRESSED -> LearningOutcome.REGRESSED
                ComparisonVerdict.NEUTRAL -> LearningOutcome.NEUTRAL
                ComparisonVerdict.INCONCLUSIVE -> LearningOutcome.INCONCLUSIVE
                ComparisonVerdict.NOT_COMPARABLE -> return null
            }

            comparison.verdict == ComparisonVerdict.INCONCLUSIVE -> LearningOutcome.INCONCLUSIVE
            !isMeaningful(delta) -> LearningOutcome.NEUTRAL
            delta.isImprovement -> LearningOutcome.IMPROVED
            else -> LearningOutcome.REGRESSED
        }
        return TestedActionResult(
            outcome = outcome,
            metricName = delta.label,
            beforeValue = delta.before,
            afterValue = delta.after,
            verdict = comparison.verdict,
        )
    }

    /**
     * A change made after the tested action and before the capture ended.
     *
     * Any such change means the measured result belongs to more than the snapshot, so attribution
     * must be refused. Failed attempts are excluded because they did not change the device; an
     * unconfirmed write is not, because it may have.
     */
    fun contamination(
        records: List<LearningRecord>,
        testedAction: TestedAction,
        gamePackage: String?,
        endedAtMillis: Long,
    ): LearningRecord? = records.firstOrNull { record ->
        record.createdAtMillis > testedAction.appliedAtMillis &&
            record.createdAtMillis <= endedAtMillis &&
            affectsBenchmark(record, gamePackage) &&
            when (record.decision) {
                LearningDecision.APPLIED,
                LearningDecision.UNCONFIRMED,
                LearningDecision.UNKNOWN,
                -> true
                else -> false
            }
    }

    /**
     * Whether a learning row can affect this benchmark's device state.
     *
     * A device action is device-wide even when it was recorded for another game; a profile field
     * only affects the game it was saved for.
     */
    fun affectsBenchmark(record: LearningRecord, gamePackage: String?): Boolean =
        record.gamePackage == null ||
            record.gamePackage == gamePackage ||
            targetFor(record.actionKey) == TestedActionTarget.DEVICE

    private fun effectSession(
        record: LearningRecord,
        sessions: List<GameSession>,
        atMillis: Long,
    ): GameSession? = sessions.asSequence()
        .filter { it.packageName == record.gamePackage }
        .filter { it.startedAtMillis > record.createdAtMillis && it.startedAtMillis <= atMillis }
        .filter { it.endedAtMillis == null || it.endedAtMillis >= atMillis }
        .maxByOrNull { it.startedAtMillis }

    /** True when two keys change the same setting and a later one replaces the earlier one. */
    fun sameSetting(firstActionKey: String, secondActionKey: String): Boolean =
        conflictGroup(firstActionKey) == conflictGroup(secondActionKey)

    private fun conflictGroup(actionKey: String): String = when (actionKey) {        RecommendationAction.PIN_PEAK_REFRESH_RATE.key,
        RecommendationAction.PIN_LOWEST_REFRESH_RATE.key,
        -> "refresh_rate"
        RecommendationAction.KEEP_FULL_PERFORMANCE.key -> "full_performance"
        RecommendationAction.ENABLE_THERMAL_DOWNSHIFT.key -> "thermal_downshift"
        RecommendationAction.SET_CPU_AFFINITY.key -> "cpu_affinity"
        RecommendationAction.FREE_RAM_ON_LAUNCH.key -> "free_ram_on_launch"
        RecommendationAction.DISABLE_ANIMATIONS.key -> "animations"
        RecommendationAction.ENABLE_BATTERY_SAVER.key -> "battery_saver"
        else -> actionKey
    }

    private fun isMeaningful(delta: MetricDelta): Boolean = when (delta.label) {
        AVERAGE_FPS, ONE_PERCENT_LOW_FPS, LOWEST_WINDOW_FPS -> abs(delta.delta) >= 1f
        JANK, CPU -> abs(delta.delta) >= 0.5f
        TEMPERATURE -> abs(delta.delta) >= 5f
        BATTERY_DRAIN -> abs(delta.delta) >= 0.2f
        else -> abs(delta.delta) > 0f
    }

    private fun candidate(
        record: LearningRecord,
        status: TestedActionStatus,
        reason: String,
        testedAction: TestedAction? = null,
    ) = TestedActionCandidate(
        recordId = record.id,
        actionKey = record.actionKey,
        title = RecommendationAction.of(record.actionKey)?.title ?: record.actionKey,
        status = status,
        reason = reason,
        testedAction = testedAction,
    )
}

/** What a stored comparison says about the exact action a run tested. */
data class TestedActionResult(
    val outcome: LearningOutcome,
    val metricName: String,
    val beforeValue: Float,
    val afterValue: Float,
    val verdict: ComparisonVerdict,
)

/**
 * The two target kinds a [RecommendationAction] can have.
 *
 * Kept private to this file so the public model does not gain another enum; the mapping itself is
 * what the snapshot records.
 */
private enum class TargetKind { DEVICE, PROFILE, NONE }

private fun RecommendationAction.targetKind(): TargetKind = when {
    optimizationAction != null -> TargetKind.DEVICE
    profileField != null -> TargetKind.PROFILE
    else -> TargetKind.NONE
}
