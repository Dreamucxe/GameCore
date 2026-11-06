package com.gamecore.ui.intelligence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.model.BenchmarkComparison
import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkProgress
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.DeviceIntelligenceReport
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.InstalledApp
import com.gamecore.core.model.IntelligenceAnalysisFrequency
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.LearningDecision
import com.gamecore.core.model.LearningRecord
import com.gamecore.core.model.LearningSummary
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.RecommendationTarget
import com.gamecore.core.model.SmartProfileField
import com.gamecore.core.model.SmartRecommendation
import com.gamecore.core.system.InstalledAppLister
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.GameProfileRepository
import com.gamecore.data.repository.SessionRepository
import com.gamecore.data.repository.SmartIntelligenceRepository
import com.gamecore.domain.optimization.OptimizationManager
import com.gamecore.domain.optimization.OptimizationRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Everything the Intelligence screen draws, already decided. */
data class IntelligenceUiState(
    val isLoading: Boolean = true,
    val isScanning: Boolean = false,
    val scan: DeviceScan? = null,
    val report: DeviceIntelligenceReport? = null,
    val goal: OptimizationGoal = OptimizationGoal.MAXIMUM_PERFORMANCE,
    val learningEnabled: Boolean = true,
    val analysisFrequency: IntelligenceAnalysisFrequency = IntelligenceAnalysisFrequency.MANUAL,
    val notificationsEnabled: Boolean = false,
    val autoApplyProfiles: Boolean = true,
    val games: List<InstalledApp> = emptyList(),
    val selectedPackage: String? = null,
    val selectedLabel: String? = null,
    val recommendations: List<SmartRecommendation> = emptyList(),
    val benchmarks: List<BenchmarkRun> = emptyList(),
    val gameSessions: List<GameSession> = emptyList(),
    val learning: Map<String, LearningSummary> = emptyMap(),
    val learningRecords: List<LearningRecord> = emptyList(),
    val benchmarkProfile: BenchmarkProfile = BenchmarkProfile.QUICK,
    val progress: BenchmarkProgress? = null,
    val isCapturing: Boolean = false,
    val comparison: BenchmarkComparison? = null,
    val comparedRunId: Long? = null,
    val outcomeRecorded: Boolean = false,
    val busyKey: String? = null,
    val message: String? = null,
    /**
     * Recommendation contexts hidden for the current view after a dismissal or confirmed apply.
     *
     * The key includes goal and game so changing either is a new context and shows the advice again.
     * An explicit rescan clears the set, because a new snapshot is exactly the evidence that may
     * justify offering it once more.
     */
    val suppressedKeys: Set<String> = emptySet(),
    /** A package the screen should open in the profile editor. Consumed after navigation. */
    val editorRequest: String? = null,
)

/**
 * The Intelligence screen's state machine.
 *
 * It scans once per refresh and shares that snapshot between the report and the recommendations,
 * so the two halves of the screen can never describe different moments. Nothing here invents a
 * reading: the facts come from [SmartIntelligenceRepository], a benchmark is the repository's real
 * capture, and an apply is [OptimizationManager]'s result or a profile write the user asked for.
 *
 * Goal and learning are read from Settings rather than copied into local state. Changing either in
 * Settings is therefore also a change here, and the recommendation list is regenerated against the
 * same scan instead of waiting for the user to press refresh.
 */
@HiltViewModel
class IntelligenceViewModel @Inject constructor(
    private val repository: SmartIntelligenceRepository,
    private val profiles: GameProfileRepository,
    private val optimization: OptimizationManager,
    private val apps: InstalledAppLister,
    private val preferences: SecurePreferenceStore,
    private val sessions: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(IntelligenceUiState())
    val state: StateFlow<IntelligenceUiState> = mutableState.asStateFlow()

    private var benchmarkJob: Job? = null
    private var sessionSnapshot: List<GameSession> = emptyList()

    init {
        observeSettings()
        observeBenchmarks()
        observeLearning()
        observeSessions()
        loadGames()
        refresh()
    }

    // ---------------------------------------------------------------------------------- observers

    private fun observeSettings() {
        viewModelScope.launch {
            preferences.settings
                .map {
                    IntelligenceSettingsSnapshot(
                        goal = it.optimizationGoal,
                        learningEnabled = it.smartLearningEnabled,
                        frequency = it.intelligenceAnalysisFrequency,
                        notifications = it.intelligenceNotificationsEnabled,
                        autoApply = it.autoApplyProfiles,
                    )
                }
                .distinctUntilChanged()
                .collect { settings ->
                    val previous = mutableState.value
                    val changed = previous.goal != settings.goal ||
                        previous.learningEnabled != settings.learningEnabled
                    mutableState.update {
                        it.copy(
                            goal = settings.goal,
                            learningEnabled = settings.learningEnabled,
                            analysisFrequency = settings.frequency,
                            notificationsEnabled = settings.notifications,
                            autoApplyProfiles = settings.autoApply,
                        )
                    }
                    // The goal changes which trade-off the rules prefer and the switch changes
                    // whether local outcomes count, so both require the same facts to be weighed
                    // again. No new scan: the snapshot is still the most recent one read.
                    if (changed && previous.scan != null) refreshRecommendations()
                }
        }
    }

    private fun observeBenchmarks() {
        viewModelScope.launch {
            repository.observeBenchmarks().collect { runs ->
                mutableState.update { it.copy(benchmarks = runs) }
            }
        }
    }

    private fun observeLearning() {
        viewModelScope.launch {
            repository.observeLearning().collect { records ->
                mutableState.update { state ->
                    state.copy(
                        learning = com.gamecore.domain.intelligence.LearningEngine.summarizeAll(records),
                        learningRecords = records,
                    )
                }
            }
        }
    }

    private fun observeSessions() {
        viewModelScope.launch {
            sessions.sessions.collect { rows ->
                sessionSnapshot = rows
                val selected = mutableState.value.selectedPackage
                mutableState.update {
                    it.copy(
                        gameSessions = if (selected == null) {
                            emptyList()
                        } else {
                            rows.filter { session -> session.packageName == selected }
                        },
                    )
                }
            }
        }
    }

    private fun loadGames() {
        viewModelScope.launch {
            val installed = try {
                apps.list()
            } catch (error: Throwable) {
                emptyList()
            }
            mutableState.update { it.copy(games = installed) }
        }
    }

    // ------------------------------------------------------------------------------------ scanning

    /** Re-reads the device and regenerates the report and the recommendations from that one scan. */
    fun refresh() {
        viewModelScope.launch {
            mutableState.update { it.copy(isScanning = true, message = null) }
            try {
                val scan = repository.scanDevice()
                val report = repository.deviceReport(scan)
                val current = mutableState.value
                val recommendations = repository.recommendationsFor(
                    scan = scan,
                    goal = current.goal,
                    gamePackage = current.selectedPackage,
                    gameLabel = current.selectedLabel,
                    learningEnabled = current.learningEnabled,
                )
                if (current.learningEnabled) {
                    repository.recordGenerated(recommendations, current.goal)
                }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        isScanning = false,
                        scan = scan,
                        report = report,
                        recommendations = recommendations,
                        // A new snapshot is new evidence; anything dismissed or already applied
                        // against the previous one is eligible to be suggested again.
                        suppressedKeys = emptySet(),
                    )
                }
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        isScanning = false,
                        message = "The device scan could not complete: ${error.shortMessage()}",
                    )
                }
            }
        }
    }

    /** Re-runs only the rules, against the scan already on screen. */
    private fun refreshRecommendations() {
        viewModelScope.launch {
            val current = mutableState.value
            val scan = current.scan ?: return@launch
            val recommendations = repository.recommendationsFor(
                scan = scan,
                goal = current.goal,
                gamePackage = current.selectedPackage,
                gameLabel = current.selectedLabel,
                learningEnabled = current.learningEnabled,
            )
            mutableState.update { state ->
                state.copy(
                    recommendations = recommendations.filter {
                        suppressionKey(state.goal, state.selectedPackage, it.key) !in
                            state.suppressedKeys
                    },
                )
            }
        }
    }

    private fun suppressionKey(
        goal: OptimizationGoal,
        packageName: String?,
        recommendationKey: String,
    ): String = "${goal.name}|${packageName ?: "-"}|$recommendationKey"

    /** Hides a recommendation in this goal/game context until the next explicit rescan. */
    private fun suppress(recommendation: SmartRecommendation) {
        mutableState.update { state ->
            state.copy(
                suppressedKeys = state.suppressedKeys + suppressionKey(
                    state.goal,
                    state.selectedPackage,
                    recommendation.key,
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------------ choices

    fun setGoal(goal: OptimizationGoal) {
        preferences.updateSettings { it.copy(optimizationGoal = goal) }
    }

    fun setLearningEnabled(enabled: Boolean) {
        preferences.updateSettings { it.copy(smartLearningEnabled = enabled) }
    }

    fun setAnalysisFrequency(frequency: IntelligenceAnalysisFrequency) {
        preferences.updateSettings {
            it.copy(
                intelligenceAnalysisFrequency = frequency,
                intelligenceNotificationsEnabled = if (frequency == IntelligenceAnalysisFrequency.MANUAL) {
                    false
                } else {
                    it.intelligenceNotificationsEnabled
                },
            )
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        preferences.updateSettings { it.copy(intelligenceNotificationsEnabled = enabled) }
    }

    fun setAutoApplyProfiles(enabled: Boolean) {
        preferences.updateSettings { it.copy(autoApplyProfiles = enabled) }
    }

    /**
     * Selects the game a benchmark and its recommendations belong to.
     *
     * Null means the whole device: the scan, thermal and refresh-rate work still applies, but no
     * per-game profile field is offered, because there would be no profile to write it to.
     */
    fun selectGame(packageName: String?, label: String?) {
        mutableState.update {
            it.copy(
                selectedPackage = packageName,
                selectedLabel = label,
                gameSessions = if (packageName == null) {
                    emptyList()
                } else {
                    sessionSnapshot.filter { session -> session.packageName == packageName }
                },
                comparison = null,
                comparedRunId = null,
                outcomeRecorded = false,
            )
        }
        refreshRecommendations()
    }

    fun selectBenchmarkProfile(profile: BenchmarkProfile) {
        mutableState.update { it.copy(benchmarkProfile = profile) }
    }

    // ---------------------------------------------------------------------------------- benchmarks

    /**
     * Captures a real benchmark for the selected game and the chosen profile.
     *
     * The duration is the profile's own, never shortened to make the UI feel faster. While it runs
     * the screen shows the live points the runner actually read; when it ends the stored run is
     * compared with its recorded baseline, if there is one.
     */
    fun startBenchmark() {
        if (mutableState.value.isCapturing) return
        val current = mutableState.value
        val profile = current.benchmarkProfile
        benchmarkJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isCapturing = true,
                    progress = null,
                    comparison = null,
                    comparedRunId = null,
                    outcomeRecorded = false,
                    message = null,
                )
            }
            try {
                val run = repository.runBenchmark(
                    profile = profile,
                    gamePackage = current.selectedPackage,
                    gameLabel = current.selectedLabel,
                    goal = current.goal,
                ) { progress ->
                    mutableState.update { it.copy(progress = progress) }
                }
                val comparison = run.baselineRunId?.let { repository.compare(it, run.id) }
                mutableState.update {
                    it.copy(
                        isCapturing = false,
                        progress = null,
                        comparison = comparison,
                        comparedRunId = run.id,
                        outcomeRecorded = false,
                        message = "Saved ${profile.label.lowercase()}: " +
                            "${run.summary.sampleCount} readings over " +
                            "${formatDuration(run.summary.durationMillis)}.",
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update {
                    it.copy(
                        isCapturing = false,
                        progress = null,
                        message = "Capture stopped. Nothing was saved.",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        isCapturing = false,
                        progress = null,
                        message = "The capture failed: ${error.shortMessage()}",
                    )
                }
            } finally {
                benchmarkJob = null
            }
        }
    }

    /** Cancels the running capture. The repository only stores a completed capture. */
    fun stopBenchmark() {
        benchmarkJob?.cancel()
    }

    /** Loads the comparison a stored run made against its baseline. */
    fun showComparison(runId: Long) {
        viewModelScope.launch {
            val comparison = repository.compareToBaseline(runId)
            mutableState.update {
                it.copy(
                    comparison = comparison,
                    comparedRunId = if (comparison != null) runId else null,
                    outcomeRecorded = false,
                    message = if (comparison == null) {
                        "That run has no baseline to compare with."
                    } else {
                        null
                    },
                )
            }
        }
    }

    /**
     * Attributes the shown comparison to the action applied before the capture and stores the
     * measured outcome. This is the only place a comparison changes future confidence.
     */
    fun recordOutcome() {
        if (!mutableState.value.learningEnabled || mutableState.value.outcomeRecorded) return
        val runId = mutableState.value.comparedRunId ?: return
        viewModelScope.launch {
            val recorded = try {
                repository.recordComparisonOutcome(runId)
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(message = "The result could not be recorded: ${error.shortMessage()}")
                }
                return@launch
            }
            mutableState.update {
                it.copy(
                    outcomeRecorded = recorded != null,
                    message = if (recorded == null) {
                        "No recommendation was applied before this run, so there is nothing to " +
                            "attribute the result to."
                    } else if (recorded.alreadyRecorded) {
                        "This comparison is already in local learning history; it was not counted again."
                    } else {
                        "Recorded ${recorded.outcome.name.lowercase()} for " +
                            "${recorded.actionKey.replace('_', ' ')}" +
                            (recorded.metricName?.let { metric -> " using $metric" } ?: "") +
                            ". Confidence will use it from now on."
                    },
                )
            }
            refreshRecommendations()
        }
    }

    fun clearBenchmarks() {
        viewModelScope.launch {
            repository.clearBenchmarks()
            mutableState.update {
                it.copy(
                    comparison = null,
                    comparedRunId = null,
                    outcomeRecorded = false,
                    message = "Benchmark history cleared.",
                )
            }
        }
    }

    fun clearLearning() {
        viewModelScope.launch {
            repository.clearLearning()
            mutableState.update { it.copy(message = "Learning history cleared.") }
            refreshRecommendations()
        }
    }

    fun clearAllIntelligenceData() {
        viewModelScope.launch {
            repository.clearAllIntelligenceData()
            preferences.updateSettings { it.copy(lastIntelligenceAnalysisAtMillis = 0L) }
            mutableState.update {
                it.copy(
                    comparison = null,
                    comparedRunId = null,
                    outcomeRecorded = false,
                    message = "All benchmark and learning data owned by Intelligence was cleared.",
                )
            }
            refreshRecommendations()
        }
    }

    // --------------------------------------------------------------------------- recommendation acts

    /**
     * Carries out the recommendation's real action.
     *
     * A device action goes through [OptimizationManager], which checks the mechanism and records
     * the restore point; a profile field is written to the selected game's profile. An action that
     * needs testing or has no profile to write to opens the profile editor instead of writing
     * something the user has not reviewed.
     */
    fun applyRecommendation(recommendation: SmartRecommendation) {
        if (mutableState.value.busyKey != null) return
        viewModelScope.launch {
            mutableState.update { it.copy(busyKey = recommendation.key, message = null) }
            try {
                when (val target = recommendation.target) {
                    is RecommendationTarget.DeviceAction -> {
                        val result = optimization.apply(
                            OptimizationRequest(
                                action = target.action,
                                rateHz = recommendation.targetRefreshRateHz,
                            ),
                        )
                        if (mutableState.value.learningEnabled) {
                            repository.recordDecision(
                                recommendation = recommendation,
                                decision = if (result.isApplied) {
                                    LearningDecision.APPLIED
                                } else {
                                    // The attempt remains inspectable, but an unconfirmed, skipped or
                                    // failed operation must not be counted as a setting that was applied.
                                    LearningDecision.ATTEMPTED
                                },
                                goal = mutableState.value.goal,
                                note = result.message,
                            )
                        }
                        mutableState.update {
                            it.copy(message = "${recommendation.title}: ${result.message}")
                        }
                        if (result.isApplied) suppress(recommendation)
                    }

                    is RecommendationTarget.GameProfile -> {
                        mutableState.update {
                            it.copy(message = applyProfileField(recommendation, target.field))
                        }
                    }

                    RecommendationTarget.Information -> {
                        mutableState.update {
                            it.copy(
                                message = "This one is advice only; there is no setting to change.",
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(message = "The change failed: ${error.shortMessage()}")
                }
            } finally {
                mutableState.update { it.copy(busyKey = null) }
                refreshRecommendations()
            }
        }
    }

    private suspend fun applyProfileField(
        recommendation: SmartRecommendation,
        field: SmartProfileField,
    ): String {
        val packageName = recommendation.appliesToPackage
            ?: return "No game is selected, so there is no profile to change."

        // Affinity and performance mode are not silently written: the first is a test with a real
        // chance of making pacing worse, and the second changes how Android treats the whole game.
        if (recommendation.requiresTesting || field == SmartProfileField.CPU_AFFINITY ||
            field == SmartProfileField.PERFORMANCE_MODE
        ) {
            mutableState.update { it.copy(editorRequest = packageName) }
            return "Review this in the game's profile editor before it is applied."
        }

        val profile = profiles.profileFor(packageName)
            ?: run {
                mutableState.update { it.copy(editorRequest = packageName) }
                return "This needs a profile for the game. Opening the editor so one can be created."
            }

        val updated = when (field) {
            SmartProfileField.REFRESH_RATE ->
                profile.copy(targetRefreshRate = recommendation.targetRefreshRateHz)
            SmartProfileField.THERMAL_DOWNSHIFT -> profile.copy(thermalDownshiftEnabled = true)
            SmartProfileField.FULL_PERFORMANCE -> profile.copy(fullPerformanceEnabled = true)
            SmartProfileField.FREE_RAM_ON_LAUNCH -> profile.copy(freeRamOnLaunch = true)
            SmartProfileField.CPU_AFFINITY ->
                profile.copy(cpuAffinity = recommendation.targetCpuAffinity)
            SmartProfileField.PERFORMANCE_MODE ->
                profile.copy(performanceMode = PerformanceMode.PERFORMANCE)
        }
        profiles.save(updated)
        if (mutableState.value.learningEnabled) {
            repository.recordDecision(
                recommendation = recommendation,
                decision = LearningDecision.APPLIED,
                goal = mutableState.value.goal,
                note = "Saved to ${profile.label}'s profile",
            )
        }
        suppress(recommendation)
        return "Saved to ${profile.label}'s profile. It takes effect the next time the game starts."
    }

    /**
     * Records that the user saw the recommendation and chose not to act on it.
     *
     * A dismissal is evidence about the user's preference, not about the device, so it is stored but
     * cannot raise or lower confidence. The recommendation stays available on the next scan.
     */
    fun dismissRecommendation(recommendation: SmartRecommendation) {
        viewModelScope.launch {
            if (mutableState.value.learningEnabled) {
                repository.recordDecision(
                    recommendation = recommendation,
                    decision = LearningDecision.IGNORED,
                    goal = mutableState.value.goal,
                    note = "Dismissed on the Intelligence screen",
                )
            }
            suppress(recommendation)
            mutableState.update {
                it.copy(
                    message = "Dismissed for this goal and game. An explicit rescan evaluates it again.",
                )
            }
            refreshRecommendations()
        }
    }

    fun openProfileEditor(packageName: String) {
        mutableState.update { it.copy(editorRequest = packageName) }
    }

    // ------------------------------------------------------------------------------- screen events

    fun consumeEditorRequest() {
        mutableState.update { it.copy(editorRequest = null) }
    }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun Throwable.shortMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    }

    private data class IntelligenceSettingsSnapshot(
        val goal: OptimizationGoal,
        val learningEnabled: Boolean,
        val frequency: IntelligenceAnalysisFrequency,
        val notifications: Boolean,
        val autoApply: Boolean,
    )
}
