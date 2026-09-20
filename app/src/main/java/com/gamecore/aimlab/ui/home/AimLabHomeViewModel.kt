package com.gamecore.aimlab.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Aim Lab home screen's state, assembled from the repository's stored history.
 *
 * This is the section's entry point in more than the navigational sense: its [init] is where
 * [AimLabRepository.seedDefaultsIfEmpty] is called, which is the one place §1 asks the built-in weapons,
 * sensitivity presets and default layout to be created — "initialise only when the user opens Aim Lab",
 * and home is what opening Aim Lab means. Seeding from here rather than at process start keeps the cost off
 * every launch for the users who never open the section.
 *
 * [state] is hot and combines the three observed flows through a single [stateIn] with
 * `WhileSubscribed(5000)`, following the same idiom the rest of the app's dashboards use: the flows stay
 * observed across a configuration change and a hop to a detail screen and back, and are released shortly
 * after the screen stops collecting. Every figure it exposes is derived by [AimLabSummary.from] from real
 * sessions and records — there is no synthetic data path, so an empty history yields the empty state rather
 * than a strip of zeroes (§1/§30).
 */
@HiltViewModel
class AimLabHomeViewModel @Inject constructor(
    private val repository: AimLabRepository,
) : ViewModel() {

    val state: StateFlow<AimLabHomeState> = combine(
        repository.sessions,
        repository.records,
        repository.sessionCount,
    ) { sessions, records, count ->
        AimLabHomeState(
            loading = false,
            summary = AimLabSummary.from(sessions = sessions, records = records, count = count),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        // loading = true until the first combine emits, so the empty banner never flashes before the
        // database has actually answered.
        initialValue = AimLabHomeState(loading = true),
    )

    init {
        // The "initialise only when the user opens Aim Lab" point (§1). Idempotent: the repository's own
        // contract is that this creates the built-ins only if the tables are empty, so a repeated open — a
        // process death and return, a second visit — does no work and adds no rows.
        viewModelScope.launch { repository.seedDefaultsIfEmpty() }
    }

    private companion object {
        /**
         * How long [state] stays alive after the screen stops collecting.
         *
         * Long enough to survive a configuration change and a navigation into a mode screen and back, short
         * enough that the flows are not held open behind a backgrounded app. The same grace the app's other
         * dashboards use, so the section feels no different from the rest.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 5_000L
    }
}
