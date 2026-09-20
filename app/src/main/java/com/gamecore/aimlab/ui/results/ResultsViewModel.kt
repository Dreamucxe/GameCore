package com.gamecore.aimlab.ui.results

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.ui.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One stored session, and the rules for which of its figures are real.
 *
 * [SessionSummary] is a single flat record shared by seven modes, and every field a mode does not produce is
 * left at zero (§18/§B). A screen that printed all of them would report a reaction test's tracking error as
 * "0.000" and a free-practice run's score as "0" — numbers the engine never measured, presented as results.
 * So each `shows…` below is the UI half of the engine's own condition for populating that field, and the
 * screen draws a row only where one is true.
 *
 * The three kinds of rule, in order of how much they can be trusted:
 *
 *  - **Structural.** The mode decides. `TrainingMode.scored` is the engine's own answer to "does this mode
 *    produce a score", recoil compensation is computed only for `RECOIL` and gyro stability only for `GYRO`.
 *  - **Structural and evidenced.** The mode could produce it but this run may not have: a recoil session
 *    with no residual samples still stores 0, so the mode test is paired with a value test.
 *  - **Evidenced alone.** Where the value itself is the evidence — reaction attempts, shots fired — no mode
 *    list is needed, and none is written, so a mode that starts recording reactions tomorrow displays them
 *    without this file changing.
 */
data class ResultsState(
    val sessionId: Long = MISSING_ID,
    val session: SessionSummary? = null,
    val loaded: Boolean = false,
) {

    /** The id resolved to nothing. Said plainly on screen rather than drawn as a card of zeroes. */
    val isMissing: Boolean get() = loaded && session == null

    /** Scored modes only. Free practice is unscored and stores a 0 that is not a result. */
    val showsScore: Boolean get() = session?.mode?.scored == true

    /** Hits, shots and accuracy. Tracking and gyro hold aim rather than shoot, so they never fire one. */
    val showsShooting: Boolean get() = (session?.shots ?: 0) > 0

    /**
     * Targets that expired unhit.
     *
     * Structural only: in a spawn-and-expire mode a zero is a real measurement — nothing got away — while in
     * reaction, tracking, gyro or recoil nothing can expire at all, so there is nothing to report.
     */
    val showsTargetsMissed: Boolean get() = session?.mode?.let { it in SPAWNING_MODES } == true

    /** How long a target lived before it was hit. Only the two modes that time it, and only if any was. */
    val showsAcquire: Boolean
        get() = session?.mode?.let { it in ACQUIRE_MODES } == true &&
            (session?.averageAcquireMillis ?: 0f) > 0f

    /** The reaction block. The attempt count is its own evidence. */
    val showsReaction: Boolean get() = (session?.reactionStats?.attempts ?: 0) > 0

    val showsTimeOnTarget: Boolean
        get() = session?.mode?.let { it in TRACKING_MODES } == true &&
            (session?.timeOnTargetFraction ?: 0f) > 0f

    val showsTrackingError: Boolean
        get() = session?.mode?.let { it in TRACKING_MODES } == true &&
            (session?.trackingErrorAverage ?: 0f) > 0f

    val showsTracking: Boolean get() = showsTimeOnTarget || showsTrackingError

    val showsRecoil: Boolean
        get() = session?.mode == TrainingMode.RECOIL && (session?.recoilCompensation ?: 0f) > 0f

    val showsGyro: Boolean
        get() = session?.mode == TrainingMode.GYRO && (session?.gyroStability ?: 0f) > 0f

    companion object {
        /** No usable id came off the route. Distinct from 0, which is a real (if unstored) row id. */
        const val MISSING_ID = -1L

        /** Modes whose targets expire on their own, so "missed" counts something they measure. */
        val SPAWNING_MODES: Set<TrainingMode> = setOf(
            TrainingMode.FLICK,
            TrainingMode.MOVEMENT,
            TrainingMode.FREE_PRACTICE,
        )

        /** Modes that time acquisition — how long a spawned target survived before it was hit. */
        val ACQUIRE_MODES: Set<TrainingMode> = setOf(TrainingMode.FLICK, TrainingMode.MOVEMENT)

        /** Modes that hold aim on a moving target and accumulate error against it. */
        val TRACKING_MODES: Set<TrainingMode> = setOf(TrainingMode.TRACKING, TrainingMode.GYRO)
    }
}

/**
 * Loads one stored session for the results screen.
 *
 * The id comes off the route through [SavedStateHandle], read as either a `Long` or a `String` so this does
 * not depend on the argument's `NavType` — a route whose type changed should fail to find a session and say
 * so, rather than resolve to a different one. A caller that hands the id straight to the composable can
 * override it through [onSessionRequested], and the two can never disagree because both write the same
 * [MutableStateFlow].
 *
 * The session is *observed* rather than read once. A finished session's figures never change, but its
 * existence does: deleting it from the history screen while this one is on the back stack makes the flow
 * re-emit without it, and this screen says so instead of continuing to display a row that is gone.
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: AimLabRepository,
) : ViewModel() {

    /** The session being shown. Seeded from the route, overridable by the composable's own argument. */
    private val requested = MutableStateFlow(
        savedState.get<Long>(Destination.ARG_ID)
            ?: savedState.get<String>(Destination.ARG_ID)?.toLongOrNull()
            ?: ResultsState.MISSING_ID,
    )

    val state: StateFlow<ResultsState> = combine(
        requested,
        repository.sessions,
    ) { id, sessions ->
        ResultsState(
            sessionId = id,
            session = if (id == ResultsState.MISSING_ID) null else sessions.firstOrNull { it.id == id },
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
        initialValue = ResultsState(sessionId = requested.value),
    )

    /**
     * Points this screen at a session id supplied directly by its caller.
     *
     * A non-positive id is ignored: an unset argument must not overwrite the perfectly good id the route
     * already carried.
     */
    fun onSessionRequested(id: Long) {
        if (id > 0L) requested.value = id
    }

    private companion object {
        /** Long enough to survive a rotation without re-querying, short enough to stop when the screen does. */
        const val SUBSCRIPTION_GRACE_MILLIS = 1_000L
    }
}
