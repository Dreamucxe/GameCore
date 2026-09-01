package com.gamecore.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.SessionSort
import com.gamecore.core.model.SessionStatistics
import com.gamecore.core.model.StopReason
import com.gamecore.data.preferences.SecurePreferenceStore
import com.gamecore.data.repository.SessionRepository
import com.gamecore.domain.gaming.GamingCoordinator
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.components.Readout
import com.gamecore.ui.components.Tone
import com.gamecore.ui.components.readoutOf
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The history list: what was recorded, sorted and filtered, with the totals above it.
 *
 * The list is derived from one flow of finished sessions and re-derived when the sort or the filter
 * changes, rather than re-queried. That is a deliberate trade: history is small — a session is one row,
 * not one row per sample — and holding it means the filter chips can carry their own counts, which is
 * what makes them worth having.
 *
 * Deletion is the one destructive thing this screen does, so it goes through [askDelete] and needs a
 * second press. [SecurePreferenceStore] holds whether that confirmation is wanted; nothing here decides
 * it.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val coordinator: GamingCoordinator,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    private data class LocalState(
        val sort: SessionSort = SessionSort.NEWEST_FIRST,
        val filterPackage: String? = null,
        val statistics: SessionStatistics = SessionStatistics.EMPTY,
        val isLoading: Boolean = true,
        val pendingDelete: SessionRow? = null,
        val pendingClear: Boolean = false,
        val message: String? = null,
    )

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<SessionsUiState> = combine(
        repository.sessions,
        coordinator.session,
        preferences.settings,
        local,
    ) { sessions, active, settings, own ->
        val filtered = sessions.filter { own.filterPackage == null || it.packageName == own.filterPackage }
        SessionsUiState(
            rows = repository.sort(filtered, own.sort).map(::rowOf),
            statistics = statisticRowsOf(own.statistics),
            sort = own.sort,
            filters = filtersOf(sessions),
            filterPackage = own.filterPackage,
            isEmpty = sessions.isEmpty(),
            isLoading = own.isLoading,
            live = active?.let(::liveOf),
            trackingEnabled = settings.trackSessions,
            pendingDelete = own.pendingDelete,
            confirmBeforeDelete = settings.confirmBeforeDiscard,
            pendingClear = own.pendingClear,
            message = own.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = SessionsUiState(),
    )

    init {
        viewModelScope.launch { reloadStatistics() }
    }

    /**
     * Re-reads the totals.
     *
     * The list itself is a flow and needs no prompting, but [SessionRepository.statistics] is a set of
     * aggregate queries and is deliberately not one — it is re-read when the screen resumes and after a
     * deletion, which are the only two moments its answer can have changed.
     */
    fun onResume() {
        viewModelScope.launch { reloadStatistics() }
    }

    private suspend fun reloadStatistics() {
        val statistics = repository.statistics()
        local.value = local.value.copy(statistics = statistics, isLoading = false)
    }

    fun setSort(sort: SessionSort) {
        local.value = local.value.copy(sort = sort)
    }

    /** Null means every game. The chip for it is always present, so there is a way back. */
    fun setFilter(packageName: String?) {
        local.value = local.value.copy(filterPackage = packageName)
    }

    /**
     * Asks first, deletes on the second press.
     *
     * A session is a record of something that happened and there is no undo, so the confirmation is the
     * default. The user can switch it off in Settings, and [delete] honours that by being reachable
     * directly.
     */
    fun askDelete(row: SessionRow) {
        if (state.value.confirmBeforeDelete) {
            local.value = local.value.copy(pendingDelete = row)
        } else {
            delete(row.id)
        }
    }

    fun cancelDelete() {
        local.value = local.value.copy(pendingDelete = null, pendingClear = false)
    }

    fun confirmDelete() {
        val row = local.value.pendingDelete ?: return
        delete(row.id)
    }

    private fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            reloadStatistics()
            local.value = local.value.copy(pendingDelete = null, message = DELETED_MESSAGE)
        }
    }

    /**
     * Clearing everything always asks, whatever the setting says.
     *
     * [askDelete] honours `confirmBeforeDiscard` because one row is a small loss and the user chose to
     * accept it. This is every row, and there is no setting under which deleting the whole history on a
     * single press is what someone meant.
     */
    fun askClear() {
        if (state.value.rows.isEmpty()) return
        local.value = local.value.copy(pendingClear = true)
    }

    fun confirmClear() {
        viewModelScope.launch {
            repository.clearHistory()
            reloadStatistics()
            local.value = local.value.copy(pendingClear = false, message = CLEARED_MESSAGE)
        }
    }

    /**
     * Ends the session being recorded now, keeping what has been measured so far.
     *
     * [StopReason.STOPPED_BY_USER] is one of the reasons whose duration is complete, so the row this
     * produces carries no "at least" — the user said when it ended, which is as exact as an end time gets.
     */
    fun stopLiveSession() {
        coordinator.stopCurrent(StopReason.STOPPED_BY_USER)
        local.value = local.value.copy(message = STOPPED_MESSAGE)
        viewModelScope.launch { reloadStatistics() }
    }

    fun dismissMessage() {
        local.value = local.value.copy(message = null)
    }

    // ------------------------------------------------------------------------ record → row

    /**
     * One recorded session as the strings its row draws.
     *
     * The "at least" prefix is decided here and nowhere else. A session GameCore stopped *watching* has a
     * duration that is a floor rather than a length, and the row says which it has rather than leaving the
     * reader to assume the more flattering one.
     */
    private fun rowOf(session: GameSession): SessionRow {
        val duration = Formatters.durationCoarse(session.durationMillis())
        return SessionRow(
            id = session.id,
            label = session.gameLabel,
            started = Formatters.relativeDay(session.startedAtMillis) +
                " · " + Formatters.clockTime(session.startedAtMillis),
            duration = if (session.hasCompleteDuration) duration else "at least $duration",
            figures = figuresOf(session),
            note = session.stopReason?.takeUnless { it.durationIsComplete }?.label,
        )
    }

    private fun figuresOf(session: GameSession): List<Readout> =
        listOf(batteryFigure(session), temperatureFigure(session), processorFigure(session))

    /**
     * Battery as a rate where that is honest, and as points where it is not.
     *
     * [com.gamecore.core.model.BatteryDrain.percentPerHour] is null for a charging session and for
     * anything under five minutes. The points lost are still a measurement in both cases, so they are
     * what the row shows — with the reason the rate is missing underneath it.
     */
    private fun batteryFigure(session: GameSession): Readout {
        val drain = session.drain() ?: return readoutOf(
            label = "Battery",
            value = ABSENT,
            detail = "No level was recorded at the end of this session.",
            tone = Tone.Muted,
        )
        val perHour = drain.percentPerHour
        return when {
            perHour != null -> readoutOf(
                label = "Battery",
                value = Formatters.percentValue(perHour, decimals = 1) + " / hour",
                detail = "${drain.pointsLost}% over ${Formatters.durationCoarse(drain.elapsedMillis)}",
                tone = Tone.Neutral,
            )
            drain.wasCharging -> readoutOf(
                label = "Battery",
                value = "${drain.pointsLost}%",
                detail = "A charger was connected, so a drain rate would not mean anything.",
                tone = Tone.Muted,
            )
            else -> readoutOf(
                label = "Battery",
                value = "${drain.pointsLost}%",
                detail = "Too short to quote a rate from.",
                tone = Tone.Muted,
            )
        }
    }

    /** The peak, not the average: a thermal figure is about the worst moment, not the typical one. */
    private fun temperatureFigure(session: GameSession): Readout {
        val peak = session.peakTemperatureDeciCelsius ?: return readoutOf(
            label = "Peak heat",
            value = ABSENT,
            detail = "No temperature sensor was readable during this session.",
            tone = Tone.Muted,
        )
        return readoutOf(
            label = "Peak heat",
            value = Formatters.temperature(peak),
            detail = session.averageTemperatureDeciCelsius
                ?.let { "Average ${Formatters.temperature(it)}" },
            tone = if (peak >= WARM_DECI_CELSIUS) Tone.Warning else Tone.Neutral,
        )
    }

    /**
     * Processor use, suppressed below ten samples.
     *
     * [GameSession.hasMeaningfulAggregates] is the rule and it is worth honouring on the list as well as
     * the report: the mean of three readings taken in a session's first six seconds describes a loading
     * screen, and printing it beside a real average would make the two look like the same kind of number.
     */
    private fun processorFigure(session: GameSession): Readout {
        val average = session.averageCpuPercent
        if (average == null || !session.hasMeaningfulAggregates) {
            return readoutOf(
                label = "Processor",
                value = ABSENT,
                detail = if (average == null) {
                    "Processor use was not readable on this device."
                } else {
                    "Only ${Formatters.count(session.sampleCount, "sample")} — too few to average."
                },
                tone = Tone.Muted,
            )
        }
        return readoutOf(
            label = "Processor",
            value = Formatters.percentValue(average),
            detail = session.peakCpuPercent?.let { "Peak ${Formatters.percentValue(it)}, device-wide" },
            fraction = (average / 100f).coerceIn(0f, 1f),
            tone = Tone.Neutral,
        )
    }

    // ------------------------------------------------------------------------ the totals

    /**
     * The summary above the list, across everything recorded rather than the current filter.
     *
     * Deliberately across everything: the chips already carry per-game counts, and a total that moved when
     * a filter changed would be a different quantity wearing the same label.
     */
    private fun statisticRowsOf(statistics: SessionStatistics): List<Readout> {
        if (!statistics.hasData) return emptyList()
        val rows = mutableListOf(
            readoutOf(
                label = "Sessions",
                value = statistics.sessionCount.toString(),
                detail = "Recorded and kept on this device only.",
            ),
            readoutOf(
                label = "Total play time",
                value = Formatters.durationTotal(statistics.totalPlayTimeMillis),
                detail = "Longest single session ${Formatters.durationCoarse(statistics.longestSessionMillis)}",
            ),
        )
        statistics.mostPlayedLabel?.let { label ->
            rows += readoutOf(
                label = "Most played",
                value = label,
                detail = Formatters.durationTotal(statistics.mostPlayedMillis) + " in total",
            )
        }
        rows += readoutOf(
            label = "Battery use",
            value = statistics.averageBatteryDrainPercentPerHour
                ?.let { Formatters.percentValue(it, decimals = 1) + " / hour" } ?: ABSENT,
            detail = if (statistics.averageBatteryDrainPercentPerHour == null) {
                "No session was long enough, or on battery throughout, to measure a rate."
            } else {
                "Averaged over the sessions where a rate could be measured."
            },
            tone = Tone.Neutral,
        )
        statistics.peakTemperatureDeciCelsius?.let { peak ->
            rows += readoutOf(
                label = "Hottest reading",
                value = Formatters.temperature(peak),
                detail = "Across every session recorded.",
                tone = if (peak >= WARM_DECI_CELSIUS) Tone.Warning else Tone.Neutral,
            )
        }
        return rows
    }

    // ------------------------------------------------------------------------ chips and the live row

    /**
     * One chip per game that appears in history, each carrying its own count.
     *
     * The counts are the reason the list is held in memory at all. They come from the same list the rows
     * come from, so a chip reading "4" and a filtered list of five rows is not a state this can reach.
     */
    private fun filtersOf(sessions: List<GameSession>): List<SessionFilter> {
        if (sessions.isEmpty()) return emptyList()
        val perGame = sessions.groupBy { it.packageName }
            .map { (packageName, rows) ->
                SessionFilter(
                    packageName = packageName,
                    label = rows.first().gameLabel,
                    count = rows.size,
                )
            }
            .sortedWith(compareByDescending<SessionFilter> { it.count }.thenBy { it.label })
        return listOf(SessionFilter(null, "All games", sessions.size)) + perGame
    }

    /**
     * The session being recorded right now.
     *
     * Its duration is read from the clock rather than from a stored end time, and its aggregates are as of
     * the last flush — which is why the detail line says how many samples are behind them instead of
     * presenting a figure that looks as settled as a finished session's.
     */
    private fun liveOf(session: GameSession): LiveSession = LiveSession(
        label = session.gameLabel,
        duration = Formatters.duration(session.durationMillis()),
        detail = if (session.sampleCount > 0) {
            "Recording · ${Formatters.count(session.sampleCount, "sample")} so far"
        } else {
            "Recording · waiting for the first sample"
        },
    )

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /** 42 °C at the battery or an SoC zone is where a phone starts to feel warm in the hand. */
        const val WARM_DECI_CELSIUS = 420

        const val DELETED_MESSAGE = "Session deleted."
        const val CLEARED_MESSAGE = "Session history cleared."
        const val STOPPED_MESSAGE = "Recording stopped. What was measured has been kept."
    }
}
