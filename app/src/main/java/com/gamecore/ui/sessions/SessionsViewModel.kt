package com.gamecore.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.core.common.Formatters
import com.gamecore.core.model.GameSession
import com.gamecore.core.model.SessionFilterKind
import com.gamecore.core.model.SessionKind
import com.gamecore.core.model.SessionOrder
import com.gamecore.core.model.SessionSort
import com.gamecore.core.model.SessionStatistics
import com.gamecore.core.model.StopReason
import com.gamecore.core.model.ThermalClass
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.core.model.ThermalSensorType
import com.gamecore.core.model.aimLabTiles
import com.gamecore.core.model.filterByKind
import com.gamecore.core.model.gameSessionTiles
import com.gamecore.core.model.kindChips
import com.gamecore.core.model.sortSessions
import com.gamecore.core.model.tilesSentence
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
 * The history list: everything this device recorded, sorted and filtered, with the totals above it.
 *
 * §6 makes this the one screen that answers "what have I been doing", and GameCore records that in two
 * places: [SessionRepository] holds a row per game session, [AimLabRepository] holds a row per training
 * run. They share no table, no model and no id space. Merging them is therefore done here, in the only
 * layer allowed to know about both — a read, and nothing but a read. No DAO, entity, repository or
 * schema is touched by this screen, which is also why an Aim Lab row has no Delete: this ViewModel is a
 * reader of that repository, not an owner of it.
 *
 * Both lists are held in memory and re-derived when the sort or a chip changes, rather than re-queried.
 * That is a deliberate trade: history is small — a session is one row, not one row per sample — and
 * holding it is what lets every chip carry a real count instead of a guess.
 *
 * Deletion is the one destructive thing this screen does, so it goes through [askDelete] and needs a
 * second press. [SecurePreferenceStore] holds whether that confirmation is wanted; nothing here decides
 * it.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val aimLab: AimLabRepository,
    private val coordinator: GamingCoordinator,
    private val preferences: SecurePreferenceStore,
) : ViewModel() {

    private data class LocalState(
        val sort: SessionSort = SessionSort.NEWEST_FIRST,
        val kind: SessionFilterKind = SessionFilterKind.ALL,
        val filterPackage: String? = null,
        val statistics: SessionStatistics = SessionStatistics.EMPTY,
        val isLoading: Boolean = true,
        val pendingDelete: SessionRow? = null,
        val pendingClear: Boolean = false,
        val message: String? = null,
    )

    /**
     * A row together with the figures it is ordered by.
     *
     * The ordering keys have to survive the merge, and neither model can supply them for the other: a
     * training run has no battery drain and a game session has no score. Pairing each row with a
     * [SessionOrder] lets one pure comparator order the merged list, instead of sorting each repository
     * separately and interleaving the results — which is not the same list.
     */
    private data class Entry(val row: SessionRow, val order: SessionOrder)

    private val local = MutableStateFlow(LocalState())

    val state: StateFlow<SessionsUiState> = combine(
        repository.sessions,
        aimLab.sessions,
        coordinator.session,
        preferences.settings,
        local,
    ) { sessions, runs, active, settings, own ->
        val entries = sessions.map(::gameEntry) +
            runs.map { run -> aimLabEntry(run, isReachable = settings.aimLabEnabled) }

        // The package chips only exist under All and Games, so a stale selection is ignored rather than
        // silently emptying the Aim Lab list — every Aim Lab row has a null package and would match none.
        val packageFilter = own.filterPackage.takeIf { own.kind != SessionFilterKind.AIMLAB }

        val shown = sortSessions(
            filterByKind(entries, own.kind) { it.row.kind }
                .filter { packageFilter == null || it.row.packageName == packageFilter },
            own.sort,
        ) { it.order }

        SessionsUiState(
            rows = shown.map { it.row },
            statistics = statisticRowsOf(own.statistics),
            sort = own.sort,
            kinds = kindChips(gameCount = sessions.size, aimLabCount = runs.size),
            kind = own.kind,
            games = filtersOf(sessions),
            filterPackage = packageFilter,
            totalCount = entries.size,
            isEmpty = entries.isEmpty(),
            isLoading = own.isLoading,
            isCompact = settings.compactDensity,
            live = active?.let(::liveOf),
            trackingEnabled = settings.trackSessions,
            aimLabAvailable = settings.aimLabEnabled,
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
     * The lists are flows and need no prompting, but [SessionRepository.statistics] is a set of aggregate
     * queries and is deliberately not one — it is re-read when the screen resumes and after a deletion,
     * which are the only two moments its answer can have changed.
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

    /**
     * §6's All / Games / Aim Lab chips.
     *
     * Choosing Aim Lab drops any game selection rather than remembering it, because the two filters are
     * not independent: a game chip under Aim Lab selects nothing at all, and coming back to Games with a
     * chip still held down from several taps ago is a list the user did not ask for.
     */
    fun setKind(kind: SessionFilterKind) {
        local.value = local.value.copy(
            kind = kind,
            filterPackage = local.value.filterPackage.takeIf { kind != SessionFilterKind.AIMLAB },
        )
    }

    /** Null means every game. The chip for it is always present, so there is a way back. */
    fun setFilter(packageName: String?) {
        local.value = local.value.copy(filterPackage = packageName)
    }

    /** The way out of an empty filtered list, in one press rather than two. */
    fun clearFilters() {
        local.value = local.value.copy(kind = SessionFilterKind.ALL, filterPackage = null)
    }

    /**
     * Asks first, deletes on the second press.
     *
     * A session is a record of something that happened and there is no undo, so the confirmation is the
     * default. The user can switch it off in Settings, and [delete] honours that by being reachable
     * directly. [SessionRow.canDelete] is checked rather than trusted: only a game session is this
     * screen's to remove, and a row that cannot be deleted must not be able to open a dialog saying it
     * will be.
     */
    fun askDelete(row: SessionRow) {
        if (!row.canDelete) return
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
     * accept it. This is every row, and there is no setting under which deleting a whole history on a
     * single press is what someone meant.
     *
     * The gate is the game count rather than the visible rows: the list may be showing nothing but Aim
     * Lab runs, and a button that then deleted something the user cannot see would be worse than one
     * that is simply not offered.
     */
    fun askClear() {
        if (state.value.gameCount == 0) return
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
     * One recorded game session as the strings its card draws.
     *
     * The "at least" prefix is decided here and nowhere else. A session GameCore stopped *watching* has a
     * duration that is a floor rather than a length, and the row says which it has rather than leaving the
     * reader to assume the more flattering one.
     *
     * The three tiles come from [gameSessionTiles], which is where every "Unavailable" and its reason is
     * decided. None of that is in the card: §6's rule is that an unrecorded figure reads as the word plus
     * a reason, and a rule that lives in a composable is a rule no test can hold it to.
     */
    private fun gameEntry(session: GameSession): Entry {
        val duration = Formatters.durationCoarse(session.durationMillis())
        val tiles = gameSessionTiles(session)
        val row = SessionRow(
            key = GAME_KEY_PREFIX + session.id,
            id = session.id,
            kind = SessionKind.GAME,
            packageName = session.packageName,
            label = session.gameLabel,
            started = startedText(session.startedAtMillis),
            duration = if (session.hasCompleteDuration) duration else "at least $duration",
            tiles = tiles,
            spokenTiles = tilesSentence(tiles),
            note = session.stopReason?.takeUnless { it.durationIsComplete }
                ?.let { "${it.label} — the duration above is a floor, not a length." },
        )
        return Entry(
            row = row,
            order = SessionOrder(
                startedAtMillis = session.startedAtMillis,
                durationMillis = session.durationMillis(),
                batteryPercentPerHour = session.drain()?.percentPerHour,
            ),
        )
    }

    /**
     * One Aim Lab run as the same kind of card.
     *
     * The title is "Aim Lab" rather than the mode, because §6 puts the mode in the first tile and a card
     * that says "Flick training" twice is not telling the reader anything the second time. It then reads
     * exactly as a game's card does: what produced the session, when, and for how long.
     *
     * [isReachable] is the Aim Lab setting. The run is listed either way — it happened, and a history
     * that quietly omits part of itself because of an unrelated toggle is the failure this merge exists
     * to prevent — but with Aim Lab switched off its report is not in the navigation graph, so the card
     * stops claiming to be tappable and says why.
     */
    private fun aimLabEntry(run: SessionSummary, isReachable: Boolean): Entry {
        val tiles = aimLabTiles(
            modeLabel = run.mode.label,
            difficultyLabel = run.difficulty.label,
            isScored = run.mode.scored,
            score = run.score,
            hits = run.hits,
            shots = run.shots,
            accuracyPercent = run.accuracyPercent,
            isLegacyScoring = run.isLegacy2D,
        )
        val row = SessionRow(
            key = AIM_LAB_KEY_PREFIX + run.id,
            id = run.id,
            kind = SessionKind.AIMLAB,
            packageName = null,
            label = AIM_LAB_LABEL,
            started = startedText(run.startedAtMillis),
            duration = Formatters.durationCoarse(run.durationMillis),
            tiles = tiles,
            spokenTiles = tilesSentence(tiles),
            note = if (isReachable) null else AIM_LAB_OFF_NOTE,
            canOpen = isReachable,
            // Read-only: this screen reads the Aim Lab repository and never writes to it. Runs are
            // deleted from Aim Lab's own history screen, which owns them.
            canDelete = false,
        )
        return Entry(
            row = row,
            order = SessionOrder(
                startedAtMillis = run.startedAtMillis,
                durationMillis = run.durationMillis,
                // Nothing measures battery during a training run, so there is no rate to sort by. Null
                // sorts last under "Highest battery use" rather than being read as zero drain.
                batteryPercentPerHour = null,
            ),
        )
    }

    /** "Today · 14:32". The same line for both kinds, so a merged list reads as one list. */
    private fun startedText(startedAtMillis: Long): String =
        Formatters.relativeDay(startedAtMillis) + " · " + Formatters.clockTime(startedAtMillis)

    // ------------------------------------------------------------------------ the totals

    /**
     * The summary above the list, across every game session rather than across the current filter.
     *
     * Deliberately across everything: the chips already carry their own counts, and a total that moved
     * when a filter changed would be a different quantity wearing the same label. It stays a game-session
     * summary — [SessionStatistics] comes from aggregate queries over that table alone, and folding
     * training runs into "total play time" would need both a second set of queries and a claim about what
     * the two kinds of session have in common.
     */
    private fun statisticRowsOf(statistics: SessionStatistics): List<Readout> {
        if (!statistics.hasData) return emptyList()
        val rows = mutableListOf(
            readoutOf(
                label = "Sessions",
                value = statistics.sessionCount.toString(),
                detail = "Game sessions recorded and kept on this device only.",
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
            // §2: the word and the colour come from the one classifier, so this row cannot end up
            // reading "Normal" in red the way the pre-redesign screens could.
            val thermal = ThermalClassifier.classify(peak, ThermalSensorType.CPU)
            rows += readoutOf(
                label = "Hottest reading",
                value = Formatters.temperature(peak),
                detail = "${thermal.label} · across every session recorded.",
                tone = toneOf(thermal.level),
            )
        }
        return rows
    }

    /** The one place a [ThermalClass] becomes a [Tone] on this screen, so the two cannot drift apart. */
    private fun toneOf(thermal: ThermalClass): Tone = when (thermal) {
        ThermalClass.CRITICAL, ThermalClass.HOT -> Tone.Danger
        ThermalClass.WARM -> Tone.Warning
        ThermalClass.OK -> Tone.Neutral
        ThermalClass.UNAVAILABLE -> Tone.Muted
    }

    // ------------------------------------------------------------------------ chips and the live row

    /**
     * One chip per game that appears in history, each carrying its own count.
     *
     * The counts are the reason the list is held in memory at all. They come from the same list the rows
     * come from, so a chip reading "4" above a filtered list of five rows is not a state this can reach.
     * Aim Lab runs are deliberately absent: they have no package, and §6's kind chips above already
     * select them.
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
     * presenting figures that look as settled as a finished session's. The tiles are the same three §6
     * asks for, and they suppress themselves honestly: under ten samples they say so rather than showing
     * the average of a loading screen.
     */
    private fun liveOf(session: GameSession): LiveSession = LiveSession(
        packageName = session.packageName,
        label = session.gameLabel,
        duration = Formatters.duration(session.durationMillis()),
        detail = if (session.sampleCount > 0) {
            "Recording · ${Formatters.count(session.sampleCount, "sample")} so far"
        } else {
            "Recording · waiting for the first sample"
        },
        tiles = gameSessionTiles(session),
    )

    private companion object {
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L

        /**
         * Row key prefixes, and the reason a key is a string at all.
         *
         * The two repositories number their rows independently, so a game session and a training run can
         * both be id 5. A `LazyColumn` keyed on the raw id would throw the first time a user has both,
         * which is every user who opens Aim Lab once.
         */
        const val GAME_KEY_PREFIX = "game-"
        const val AIM_LAB_KEY_PREFIX = "aim-"

        const val AIM_LAB_LABEL = "Aim Lab"

        const val AIM_LAB_OFF_NOTE =
            "Aim Lab is switched off in Settings, so this run's report cannot be opened from here. The " +
                "run itself is kept."

        const val DELETED_MESSAGE = "Session deleted."
        const val CLEARED_MESSAGE = "Game session history cleared."
        const val STOPPED_MESSAGE = "Recording stopped. What was measured has been kept."
    }
}
