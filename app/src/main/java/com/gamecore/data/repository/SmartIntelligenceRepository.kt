package com.gamecore.data.repository

import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkProgress
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.DeviceIntelligenceReport
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningOutcome
import com.gamecore.core.model.LearningOutcomeRecord
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.LearningSummary
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.SmartRecommendation
import com.gamecore.core.model.TestedActionResolution
import com.gamecore.data.database.SmartIntelligenceDao
import com.gamecore.data.database.SmartIntelligenceMappers
import com.gamecore.domain.intelligence.BenchmarkAttribution
import com.gamecore.domain.intelligence.BenchmarkCaptureGate
import com.gamecore.domain.intelligence.BenchmarkMath
import com.gamecore.domain.intelligence.DeviceIntelligence
import com.gamecore.domain.intelligence.LearningEngine
import com.gamecore.domain.intelligence.RecommendationEngine
import com.gamecore.domain.intelligence.SmartBenchmarkRunner
import com.gamecore.domain.intelligence.SmartDeviceScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one entry point the Smart Intelligence UI uses.
 *
 * It owns no arithmetic itself: the scan comes from [SmartDeviceScanner], the summary and the
 * statistical comparison from [BenchmarkMath], the confidence adjustment from [LearningEngine] and
 * the recommendation rules from [RecommendationEngine]. This class exists to keep those pure
 * pieces out of the database and to make sure a benchmark row and the recommendation that cites it
 * are written from the same capture.
 *
 * Persistence is deliberately limited to two things: completed benchmark summaries and the local
 * decision/outcome log. A device scan is a reading of the present and is regenerated on demand;
 * storing every scan would turn a snapshot into a history of the user's battery, memory and storage
 * with no feature that needs it.
 */
@Singleton
class SmartIntelligenceRepository @Inject constructor(
    private val dao: SmartIntelligenceDao,
    private val scanner: SmartDeviceScanner,
    private val runner: SmartBenchmarkRunner,
    private val profiles: GameProfileRepository,
    private val sessions: SessionRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * One capture at a time, enforced in the repository rather than only by the screen's button.
     * Two overlapping captures would share a baseline and could claim the same applied action.
     */
    private val captureGate = BenchmarkCaptureGate()

    /** Serialises outcome recording so two taps cannot insert two TESTED rows for one run. */
    private val outcomeGate = Mutex()

    // ------------------------------------------------------------------------------------ device

    /** The current facts, in one snapshot. Callers that need the report use [deviceReport]. */
    suspend fun scanDevice(): DeviceScan = scanner.scan()

    /** The current snapshot turned into readable findings. */
    suspend fun deviceReport(): DeviceIntelligenceReport =
        DeviceIntelligence.buildReport(scanner.scan())

    /**
     * The same report from a scan the caller already took.
     *
     * The screen scans once and uses the snapshot for both the report and the recommendations, so
     * the two can never describe different moments.
     */
    fun deviceReport(scan: DeviceScan): DeviceIntelligenceReport =
        DeviceIntelligence.buildReport(scan)

    // -------------------------------------------------------------------------------- benchmarks

    fun observeBenchmarks(limit: Int = DEFAULT_LIMIT): Flow<List<BenchmarkRun>> =
        dao.observeBenchmarks(limit).map { rows ->
            rows.map(SmartIntelligenceMappers::toModel)
        }

    fun observeBenchmarksFor(
        packageName: String,
        limit: Int = DEFAULT_LIMIT,
    ): Flow<List<BenchmarkRun>> =
        dao.observeBenchmarksFor(packageName, limit).map { rows ->
            rows.map(SmartIntelligenceMappers::toModel)
        }

    suspend fun latestBenchmark(gamePackage: String?): BenchmarkRun? = withContext(io) {
        dao.latestBenchmark(gamePackage)?.let(SmartIntelligenceMappers::toModel)
    }

    suspend fun benchmark(id: Long): BenchmarkRun? = withContext(io) {
        dao.benchmark(id)?.let(SmartIntelligenceMappers::toModel)
    }

    suspend fun benchmarkCount(): Int = withContext(io) { dao.benchmarkCount() }

    /**
     * Captures a real run, summarizes it, and stores it only if the capture itself succeeded.
     *
     * [onProgress] is invoked on the IO dispatcher while the capture runs; a ViewModel should move
     * the value into its own state flow rather than touching Compose state directly. The baseline is
     * the most recent run of the same game and profile, which is what [compare] expects when the
     * user asks whether the last change helped.
     *
     * Before the capture starts, the one confirmed action the run may test is resolved and copied
     * into [BenchmarkRun.testedAction]. That snapshot is written with the row; nothing about the
     * attribution is recomputed from the learning log later. A second capture is refused while one
     * is running, so two sessions cannot share a baseline or a tested action.
     */
    suspend fun runBenchmark(
        profile: BenchmarkProfile,
        durationMillis: Long = profile.defaultDurationMillis,
        gamePackage: String?,
        gameLabel: String?,
        goal: OptimizationGoal,
        onProgress: (BenchmarkProgress) -> Unit,
    ): BenchmarkRun = withContext(io) {
        if (!captureGate.tryEnter()) {
            throw IllegalStateException("A benchmark capture is already running.")
        }
        try {
            val baseline = dao.latestBenchmarkFor(gamePackage, profile.name)
            val resolution = if (baseline == null) {
                TestedActionResolution(
                    reason = "There is no earlier run of this profile to compare with.",
                )
            } else {
                resolveTestedAction(
                    baselineEndedAtMillis = baseline.endedAtMillis,
                    gamePackage = gamePackage,
                    goal = goal,
                    atMillis = System.currentTimeMillis(),
                )
            }
            val capture = runner.capture(
                profile = profile,
                durationMillis = durationMillis,
                gamePackage = gamePackage,
                onProgress = onProgress,
            )
            val run = BenchmarkRun(
                profile = profile,
                gamePackage = gamePackage,
                gameLabel = gameLabel,
                goal = goal,
                startedAtMillis = capture.startedAtMillis,
                endedAtMillis = capture.endedAtMillis,
                summary = BenchmarkMath.summarize(capture.points, profile),
                baselineRunId = baseline?.id,
                testedAction = resolution.testedAction,
            )
            val id = dao.insertBenchmark(
                SmartIntelligenceMappers.toEntity(run, capture.fpsWindows),
            )
            run.copy(id = id)
        } finally {
            captureGate.leave()
        }
    }

    /**
     * What a capture started now would be attributed to, for the screen to show before it runs.
     *
     * This is a preview only. [runBenchmark] resolves the snapshot again inside the capture gate;
     * if the state changed in between, the stored row is the one that decides, not this value.
     */
    suspend fun previewTestedAction(
        profile: BenchmarkProfile,
        gamePackage: String?,
        goal: OptimizationGoal,
    ): TestedActionResolution = withContext(io) {
        val baseline = dao.latestBenchmarkFor(gamePackage, profile.name)
            ?: return@withContext TestedActionResolution(
                reason = "This will be the first run of this profile, so it cannot be attributed " +
                    "to a change yet.",
            )
        resolveTestedAction(
            baselineEndedAtMillis = baseline.endedAtMillis,
            gamePackage = gamePackage,
            goal = goal,
            atMillis = System.currentTimeMillis(),
        )
    }

    /**
     * Reads the window after the previous run and asks the pure rules for the single action, if any.
     *
     * The window is what isolates the change: an action already present in the baseline belongs to
     * the baseline, and two changes in the same window are ambiguous rather than one being chosen.
     */
    private suspend fun resolveTestedAction(
        baselineEndedAtMillis: Long,
        gamePackage: String?,
        goal: OptimizationGoal,
        atMillis: Long,
    ): TestedActionResolution {
        val records = dao.learningBetween(gamePackage, baselineEndedAtMillis, atMillis)
            .map(SmartIntelligenceMappers::toModel)
        val sessions = sessions.snapshot()
        return BenchmarkAttribution.resolve(records, gamePackage, goal, sessions, atMillis)
    }

    /**
     * Compares two stored captures with the same statistical rules the engine uses everywhere.
     *
     * Null means one of the rows no longer exists — a deleted run, not an inconclusive result.
     * An incomparable pair returns [BenchmarkComparison] with [BenchmarkComparison.verdict] set to
     * `NOT_COMPARABLE` and a sentence saying why.
     */
    suspend fun compare(baselineId: Long, candidateId: Long): BenchmarkComparison? = withContext(io) {
        val baselineRow = dao.benchmark(baselineId) ?: return@withContext null
        val candidateRow = dao.benchmark(candidateId) ?: return@withContext null
        BenchmarkMath.compare(
            baseline = SmartIntelligenceMappers.toModel(baselineRow),
            baselineWindows = SmartIntelligenceMappers.windows(baselineRow),
            candidate = SmartIntelligenceMappers.toModel(candidateRow),
            candidateWindows = SmartIntelligenceMappers.windows(candidateRow),
        )
    }

    /** The comparison a stored run recorded when it was captured, if it has a baseline. */
    suspend fun compareToBaseline(candidateId: Long): BenchmarkComparison? = withContext(io) {
        val candidate = dao.benchmark(candidateId) ?: return@withContext null
        val baselineId = candidate.baselineRunId ?: return@withContext null
        val baseline = dao.benchmark(baselineId) ?: return@withContext null
        BenchmarkMath.compare(
            baseline = SmartIntelligenceMappers.toModel(baseline),
            baselineWindows = SmartIntelligenceMappers.windows(baseline),
            candidate = SmartIntelligenceMappers.toModel(candidate),
            candidateWindows = SmartIntelligenceMappers.windows(candidate),
        )
    }

    /**
     * Attributes a stored comparison to the exact action its benchmark row says it tested.
     *
     * The identity comes only from [BenchmarkRun.testedAction], which was snapshotted when the
     * capture started. This method never asks for "the latest applied action": it re-reads that one
     * application row, checks that it is still a confirmed application of the same action, game,
     * goal and time, and refuses if another change landed in the same window or during the capture.
     * The metric is chosen from the action's own ordered relevance list, not from whatever happens
     * to be the first delta.
     *
     * Null means the run cannot honestly teach the engine about one action: no snapshot (including
     * every legacy row), a cleared learning history, an ambiguous window, a change during capture,
     * no comparable verdict, or no relevant measured metric. The comparison itself remains readable.
     */
    suspend fun recordComparisonOutcome(candidateId: Long): LearningOutcomeRecord? = withContext(io) {
        outcomeGate.withLock {
            val candidateRow = dao.benchmark(candidateId) ?: return@withLock null
            val candidate = SmartIntelligenceMappers.toModel(candidateRow)
            val baselineId = candidate.baselineRunId ?: return@withLock null
            val baselineRow = dao.benchmark(baselineId) ?: return@withLock null
            val baseline = SmartIntelligenceMappers.toModel(baselineRow)
            val comparison = BenchmarkMath.compare(
                baseline = baseline,
                baselineWindows = SmartIntelligenceMappers.windows(baselineRow),
                candidate = candidate,
                candidateWindows = SmartIntelligenceMappers.windows(candidateRow),
            )
            // Re-inspecting a stored run is allowed; inserting its evidence twice is not. The query
            // is by the run id, not by metric values, so two genuinely different runs with the same
            // average FPS are still two outcomes.
            dao.outcomeForRun(candidateId)?.let { existing ->
                return@withLock LearningOutcomeRecord(
                    id = existing.id,
                    actionKey = existing.actionKey,
                    outcome = LearningOutcome.entries.firstOrNull { it.name == existing.outcome }
                        ?: LearningOutcome.UNKNOWN,
                    metricName = existing.metricName,
                    beforeValue = existing.beforeValue,
                    afterValue = existing.afterValue,
                    verdict = comparison.verdict,
                    alreadyRecorded = true,
                )
            }
            val testedAction = candidate.testedAction ?: return@withLock null
            if (candidate.goal != testedAction.goal) return@withLock null
            if (candidate.gamePackage != testedAction.gamePackage) return@withLock null

            // The snapshot is only an identity; the row behind it must still be the confirmed
            // application it claimed to be. A cleared learning history therefore leaves the
            // benchmark readable but makes it unattributable.
            val appliedRow = dao.learningRecord(testedAction.appliedRecordId)
                ?: return@withLock null
            val applied = SmartIntelligenceMappers.toModel(appliedRow)
            if (applied.decision != LearningDecision.APPLIED ||
                applied.actionKey != testedAction.actionKey ||
                applied.gamePackage != testedAction.gamePackage ||
                applied.goal != testedAction.goal ||
                applied.createdAtMillis != testedAction.appliedAtMillis
            ) {
                return@withLock null
            }

            // Re-check the pre-capture window. Another confirmed change to a different setting, or
            // an unconfirmed write that may have moved the device, makes the comparison ambiguous.
            val window = dao.learningAfter(baseline.endedAtMillis, candidate.startedAtMillis)
                .map(SmartIntelligenceMappers::toModel)
                .filter { BenchmarkAttribution.affectsBenchmark(it, candidate.gamePackage) }
            val anotherChange = window.any { record ->
                record.id != testedAction.appliedRecordId &&
                    record.decision == LearningDecision.APPLIED &&
                    record.actionKey != testedAction.actionKey &&
                    !BenchmarkAttribution.sameSetting(record.actionKey, testedAction.actionKey)
            }
            val uncertain = window.any {
                it.decision == LearningDecision.UNCONFIRMED ||
                    it.decision == LearningDecision.UNKNOWN
            }
            if (anotherChange || uncertain) return@withLock null

            // A change made while the capture was running contaminates the whole window, even if
            // it was a re-application of the same action.
            val after = dao.learningAfter(testedAction.appliedAtMillis, candidate.endedAtMillis)
                .map(SmartIntelligenceMappers::toModel)
            if (BenchmarkAttribution.contamination(
                    records = after,
                    testedAction = testedAction,
                    gamePackage = candidate.gamePackage,
                    endedAtMillis = candidate.endedAtMillis,
                ) != null
            ) {
                return@withLock null
            }

            val evidence = BenchmarkAttribution.evidence(testedAction, comparison)
                ?: return@withLock null
            val record = LearningRecord(
                actionKey = testedAction.actionKey,
                gamePackage = candidate.gamePackage,
                goal = testedAction.goal,
                decision = LearningDecision.TESTED,
                outcome = evidence.outcome,
                metricName = evidence.metricName,
                beforeValue = evidence.beforeValue,
                afterValue = evidence.afterValue,
                note = comparison.verdict.label,
                createdAtMillis = System.currentTimeMillis(),
                sourceRunId = candidateId,
            )
            val id = dao.insertLearning(SmartIntelligenceMappers.toEntity(record))
            LearningOutcomeRecord(
                id = id,
                actionKey = testedAction.actionKey,
                outcome = evidence.outcome,
                metricName = evidence.metricName,
                beforeValue = evidence.beforeValue,
                afterValue = evidence.afterValue,
                verdict = comparison.verdict,
            )
        }
    }

    suspend fun deleteBenchmark(id: Long) = withContext(io) { dao.deleteBenchmark(id) }

    suspend fun clearBenchmarks() = withContext(io) { dao.clearBenchmarks() }

    // ---------------------------------------------------------------------------------- learning

    fun observeLearning(): Flow<List<LearningRecord>> =
        dao.observeLearning().map { rows -> rows.map(SmartIntelligenceMappers::toModel) }

    suspend fun learningSummaries(): Map<String, LearningSummary> = withContext(io) {
        LearningEngine.summarizeAll(
            dao.allLearning().map(SmartIntelligenceMappers::toModel),
        )
    }

    suspend fun learningCount(): Int = withContext(io) { dao.learningCount() }

    /**
     * Remembers which recommendations were actually generated, without turning refresh taps into an
     * unbounded duplicate log. The same action/game/goal is recorded at most once per six hours; a
     * changed goal or game is a distinct recommendation context and gets its own row.
     */
    suspend fun recordGenerated(
        recommendations: List<SmartRecommendation>,
        goal: OptimizationGoal,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withContext(io) {
        recommendations.forEach { recommendation ->
            val previous = dao.latestGeneratedAt(
                actionKey = recommendation.key,
                gamePackage = recommendation.appliesToPackage,
                goal = goal.name,
            )
            if (previous == null || nowMillis - previous >= GENERATION_DEDUP_MILLIS) {
                dao.insertLearning(
                    SmartIntelligenceMappers.toEntity(
                        LearningRecord(
                            actionKey = recommendation.key,
                            gamePackage = recommendation.appliesToPackage,
                            goal = goal,
                            decision = LearningDecision.GENERATED,
                            outcome = LearningOutcome.UNKNOWN,
                            metricName = null,
                            beforeValue = null,
                            afterValue = null,
                            note = "${recommendation.classification.label}, " +
                                "${recommendation.confidencePercent}% confidence",
                            createdAtMillis = nowMillis,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Records that a user applied, dismissed or tested a recommendation.
     *
     * No outcome is attached yet: an application is a decision, not evidence that the decision
     * helped. The outcome arrives later from [recordOutcome], and only that changes confidence.
     */
    suspend fun recordDecision(
        recommendation: SmartRecommendation,
        decision: LearningDecision,
        goal: OptimizationGoal,
        note: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = withContext(io) {
        dao.insertLearning(
            SmartIntelligenceMappers.toEntity(
                LearningRecord(
                    actionKey = recommendation.key,
                    gamePackage = recommendation.appliesToPackage,
                    goal = goal,
                    decision = decision,
                    outcome = LearningOutcome.UNKNOWN,
                    metricName = null,
                    beforeValue = null,
                    afterValue = null,
                    note = note,
                    createdAtMillis = nowMillis,
                ),
            ),
        )
    }

    /** Records a measured outcome. This is the only path that can raise or lower confidence. */
    suspend fun recordOutcome(
        actionKey: String,
        gamePackage: String?,
        goal: OptimizationGoal,
        outcome: LearningOutcome,
        metricName: String?,
        beforeValue: Float?,
        afterValue: Float?,
        note: String? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Long = withContext(io) {
        dao.insertLearning(
            SmartIntelligenceMappers.toEntity(
                LearningRecord(
                    actionKey = actionKey,
                    gamePackage = gamePackage,
                    goal = goal,
                    decision = LearningDecision.TESTED,
                    outcome = outcome,
                    metricName = metricName,
                    beforeValue = beforeValue,
                    afterValue = afterValue,
                    note = note,
                    createdAtMillis = nowMillis,
                ),
            ),
        )
    }

    suspend fun clearLearning() = withContext(io) { dao.clearLearning() }

    /** Clears only Intelligence-owned data; profiles, sessions and app settings are untouched. */
    suspend fun clearAllIntelligenceData() = withContext(io) {
        dao.clearAllIntelligenceData()
    }

    // --------------------------------------------------------------------------- recommendations

    /**
     * Scans the device and produces recommendations for [goal] and, when supplied, one game.
     *
     * The benchmark used as evidence is the latest capture for the same package, and the profile
     * used to suppress already-configured actions is that game's profile. Passing a game package is
     * therefore what makes the free-RAM, thermal and affinity advice game-specific instead of
     * generic.
     */
    suspend fun recommendations(
        goal: OptimizationGoal,
        gamePackage: String? = null,
        gameLabel: String? = null,
    ): List<SmartRecommendation> = withContext(io) {
        recommendationsFor(scanner.scan(), goal, gamePackage, gameLabel)
    }

    /**
     * The same rules against a scan the caller already holds.
     *
     * [learningEnabled] is the user's switch, not a capability: false means the stored outcomes are
     * not consulted, so confidence stays at what the device readings alone justify. The records are
     * left in place — turning learning back on restores their effect.
     */
    suspend fun recommendationsFor(
        scan: DeviceScan,
        goal: OptimizationGoal,
        gamePackage: String? = null,
        gameLabel: String? = null,
        learningEnabled: Boolean = true,
    ): List<SmartRecommendation> = withContext(io) {
        val benchmark = dao.latestBenchmark(gamePackage)?.let(SmartIntelligenceMappers::toModel)
        val profile = gamePackage?.let { profiles.profileFor(it) }
        val history = sessions.snapshot()
            .asSequence()
            .filter { gamePackage == null || it.packageName == gamePackage }
            .take(MAX_SESSION_EVIDENCE)
            .toList()
        val learning = if (learningEnabled) {
            LearningEngine.summarizeAll(
                dao.allLearning().map(SmartIntelligenceMappers::toModel),
            )
        } else {
            emptyMap()
        }
        RecommendationEngine.recommend(
            scan = scan,
            goal = goal,
            benchmark = benchmark,
            learning = learning,
            gamePackage = gamePackage,
            gameLabel = gameLabel,
            profile = profile,
            sessions = history,
        )
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val GENERATION_DEDUP_MILLIS = 6L * 60L * 60L * 1_000L
        const val MAX_SESSION_EVIDENCE = 20
    }
}
