package com.gamecore.aimlab.ui.reaction

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * Runs one reaction session and keeps the screen's state while it happens.
 *
 * The shape is the section's idiom, and the important part of it is that **collection is what runs the
 * loop**. [state] folds the user's choices together with the active loop's frames and is shared with
 * `WhileSubscribed`; when the screen stops collecting, the frames flow is cancelled, the loop's `awaitClose`
 * fires, its ticker job is cancelled and the run is abandoned without being written (§19/§29). There is no
 * service, no listener and no coroutine that can outlive the screen.
 *
 * A loop instance is taken from the [Provider] once per run and held for that run only, so a retry is a
 * genuinely fresh engine — new seed, cleared reaction times — rather than a restarted one still carrying the
 * previous test's samples.
 *
 * Nothing in here invents a number. Every reaction time is measured inside the engine from the moment the
 * target was drawn to the moment the tap landed; the result card is the summary the loop produced on stop;
 * and whether the run was kept is the repository's answer rather than an assumption.
 */
@HiltViewModel
class ReactionViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
) : ViewModel() {

    /** What the user chose and where the run has got to. Everything except the frames. */
    private val local = MutableStateFlow(ReactionState())

    /**
     * The loop of the run in progress, or null between runs.
     *
     * Switching this is how the screen starts and stops collecting frames: a non-null value makes
     * [loopFrames] collect that loop (which is what makes it tick), and nulling it cancels the collection.
     */
    private val active = MutableStateFlow<AimTrainingLoop3D?>(null)

    /**
     * Guards the end of a run so it happens exactly once.
     *
     * The loop emits a final finished frame and can replay it to a late subscriber, so without this a
     * session could be stopped twice or saved twice.
     */
    private var finishing = false

    /**
     * The active loop's frames, or a single null when there is no run.
     *
     * The finish is handled here rather than in the [combine] below, and rather than in a `LaunchedEffect`
     * on the screen, because this is the one place a frame is seen exactly once, as it arrives — a
     * screen-side effect keyed on `frame.finished` would fire again on the first composition after a return,
     * against a frame the loop had already reported.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop -> loop?.frames ?: flowOf<TrainingFrame?>(null) }
        .onEach { frame -> if (frame != null && frame.finished) finishRun() }

    val state: StateFlow<ReactionState> = combine(local, loopFrames, repository.layouts) { base, frame, layouts ->
        // Fold in the saved control layouts so the setup screen can offer a HUD, and re-resolve the chosen
        // one by id so an edit in the control editor is reflected here. A null frame means "no run" — setup
        // and result own the whole screen then, and the frame the state already holds (none) is correct.
        val withLayouts = base.copy(
            layouts = layouts,
            layout = base.layout?.let { chosen -> layouts.firstOrNull { it.id == chosen.id } },
        )
        if (frame == null) withLayouts else withLayouts.copy(frame = frame)
    }
        // Fires when the screen stops collecting and the grace window expires — the same moment the loop's
        // ticker is cancelled. Abandoning here rather than waiting to notice a dead loop on the way back
        // matters twice over: the state cannot sit claiming a run that no longer ticks, and [finishing] is
        // latched, so a torn-down loop's final frame can never be mistaken for a test the user finished and
        // saved as a session they never took.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = ReactionState(),
        )

    // ------------------------------------------------------------------------------ setup

    fun selectDifficulty(difficulty: Difficulty) {
        local.update { if (it.loop != null) it else it.copy(difficulty = difficulty) }
    }

    fun setDuration(seconds: Int) {
        val clamped = seconds.coerceIn(ReactionState.DURATION_RANGE)
        local.update { if (it.loop != null) it else it.copy(durationSeconds = clamped) }
    }

    /** Picks the on-screen control layout (HUD) for the run, or clears it — null means no controls. */
    fun onLayoutSelected(layout: ControlLayout?) {
        local.update { if (it.loop != null) it else it.copy(layout = layout) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The guard is on the *state's* loop rather than on a field that only an explicit stop clears. That
     * distinction is the whole bug it replaces: abandonment returns the state to setup but never went near
     * such a field, so a user who left mid-test came back to a live-looking Start button that silently did
     * nothing. Guarding on the same value the screen uses to decide what to draw means the button is dead
     * exactly when it is also invisible.
     *
     * The loop is started before it is published to [active]: the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run.
     */
    fun start() {
        val current = local.value
        if (current.loop != null) return

        finishing = false
        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.REACTION,
                difficulty = current.difficulty,
                durationSeconds = current.durationSeconds,
                layoutId = current.layout?.id,
            ),
        )
        local.value = current.copy(
            loop = loop,
            frame = null,
            summary = null,
            saving = false,
            recorded = false,
            abandoned = false,
        )
        active.value = loop
    }

    /** Ends the test now, at the user's request. An early stop is still a real session if anything was timed. */
    fun stopRun() = finishRun()

    /** Back to the setup card without recording anything — the way out of a finished run that is not a retry. */
    fun reset() {
        finishing = false
        active.value = null
        local.update {
            ReactionState(difficulty = it.difficulty, durationSeconds = it.durationSeconds)
        }
    }

    /**
     * Drops a run that was still going when the screen stopped collecting.
     *
     * Not a stop: nothing is read off the loop and nothing is written, because the user did not end this
     * test — they left it, and a reaction time nobody was present for is not a reaction time. By the time
     * this runs the collection that *was* the run has already been cancelled and the ticker is gone; what
     * this does is make the state agree with that, so a returning user gets the setup card instead of a dim
     * arena waiting for a target that will never appear. [finishing] is latched true so a final frame from
     * the loop being torn down cannot reach [finishRun] and persist a test the user walked away from;
     * [start] clears it.
     */
    private fun abandonRun() {
        if (local.value.loop == null) return
        finishing = true
        active.value = null
        local.update {
            it.copy(
                loop = null,
                frame = null,
                summary = null,
                saving = false,
                recorded = false,
                abandoned = true,
            )
        }
    }

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * Nulling [active] is what actually shuts the engine down: the frames collection is cancelled and the
     * loop's `awaitClose` tears its ticker down. The summary is read first, because a cancelled loop has
     * nothing left to report. A null summary means the loop judged the run empty — a test in which no target
     * was ever answered. That is not saved, and the screen says so rather than showing a card of zero
     * milliseconds, which would read as an impossibly fast reaction rather than as no reaction at all.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                loop = null,
                frame = null,
                summary = summary,
                saving = summary != null,
                recorded = false,
                abandoned = false,
            )
        }
        if (summary == null) return

        viewModelScope.launch {
            val id = repository.saveSession(summary)
            // Only report back if the screen is still showing this run: a quick retry must not have its
            // fresh state overwritten by the previous run's write landing late.
            local.update { if (it.summary === summary) it.copy(saving = false, recorded = id > 0L) else it }
        }
    }

    private companion object {
        /**
         * How long [state] — and therefore the loop — survives the screen going away.
         *
         * A second covers a rotation and nothing else. Past that the run is abandoned, which is the correct
         * outcome: a training session the user walked away from is not a session.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 1_000L
    }
}
