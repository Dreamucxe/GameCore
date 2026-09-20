package com.gamecore.aimlab.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.TrainingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Reads the stored personal records and holds the screen's filters.
 *
 * This ViewModel writes no records and computes none. [AimLabRepository.records] is the whole of the data,
 * and every row in it was put there by `PersonalRecordBook.challenge` when a real session beat a previous
 * result — so there is no path in this file that could produce a best the user never set. What is not in the
 * flow is not on the screen: no defaults, no zero-filled metrics, no placeholder rows for modes never
 * trained (§1/§30).
 *
 * The filters live in [local] and are folded together with the repository flow, which means they are
 * *resolved against the rows that currently exist* on every emission — the same idiom the practice screen
 * uses for gear. A filter pinned to a mode whose last record is then reset does not linger as a selection
 * that matches nothing; it falls back to "no filter".
 */
@HiltViewModel
class RecordsViewModel @Inject constructor(
    private val repository: AimLabRepository,
) : ViewModel() {

    /** The screen's own choices: which filters are on, and where the reset flow has got to. */
    private val local = MutableStateFlow(RecordsState())

    val state: StateFlow<RecordsState> = combine(
        local,
        repository.records,
    ) { base, records ->
        base.copy(
            // The first emission is the database's real answer, so the empty state can only appear once
            // there is genuinely nothing rather than while the query is still in flight.
            loading = false,
            records = records,
            modeFilter = base.modeFilter?.takeIf { mode -> records.any { it.mode == mode } },
            difficultyFilter = base.difficultyFilter?.takeIf { level -> records.any { it.difficulty == level } },
            // The "records cleared" note is about an empty table. The moment a new record lands it is stale,
            // and a fresh record is its own answer.
            reset = base.reset && records.isEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = RecordsState(),
    )

    // ------------------------------------------------------------------------------ filters

    /** Narrows to one mode, or clears the mode filter when [mode] is null. */
    fun onModeSelected(mode: TrainingMode?) = local.update { it.copy(modeFilter = mode) }

    /** Narrows to one difficulty, or clears the difficulty filter when [difficulty] is null. */
    fun onDifficultySelected(difficulty: Difficulty?) =
        local.update { it.copy(difficultyFilter = difficulty) }

    /** Drops both filters — the way out of a filter combination that matches nothing. */
    fun clearFilters() = local.update { it.copy(modeFilter = null, difficultyFilter = null) }

    // ------------------------------------------------------------------------------ reset

    /** Asks for the confirmation. Nothing is deleted until [confirmReset]. */
    fun requestReset() {
        if (!state.value.hasAny) return
        local.update { it.copy(confirmingReset = true) }
    }

    fun dismissReset() = local.update { it.copy(confirmingReset = false) }

    /**
     * Clears the records, through the repository's one delete.
     *
     * [AimLabRepository.clearHistory] is that delete, and it removes the sessions *and* the records together
     * — they are one transaction because a record is derived from a session, and clearing one without the
     * other would leave a best whose session no longer exists. The confirmation dialog says so in as many
     * words; this is not a records-only wipe dressed up as one.
     *
     * The filters are dropped with the rows: a filter selected for a mode that no longer has any record is
     * a control pointing at nothing.
     */
    fun confirmReset() {
        val current = state.value
        if (current.resetting || !current.hasAny) return
        local.update { it.copy(confirmingReset = false, resetting = true) }
        viewModelScope.launch {
            repository.clearHistory()
            local.update {
                it.copy(
                    resetting = false,
                    reset = true,
                    modeFilter = null,
                    difficultyFilter = null,
                )
            }
        }
    }

    private companion object {
        /**
         * How long [state] survives the screen going away.
         *
         * The same grace the section's other read-only screens use: long enough to cover a rotation and a
         * trip to a mode screen and back, short enough that the records query is not held open behind a
         * backgrounded app.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
