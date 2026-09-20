package com.gamecore.aimlab.ui.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.TrackingPattern
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
 * Runs one tracking session and keeps the screen's state while it happens.
 *
 * The shape here is the section's idiom, and the important part of it is that **collection is what runs the
 * loop**. [state] folds the user's choices together with the active loop's frames, and it is shared with
 * `WhileSubscribed`; when the screen stops collecting, the frames flow is cancelled, the loop's `awaitClose`
 * fires, its ticker job is cancelled and the run is abandoned without being written (§19/§29). There is no
 * service, no listener and no coroutine that can outlive the screen — leaving mid-run leaves nothing behind.
 * The grace window is deliberately short: long enough to ride out a configuration change, short enough that
 * a backgrounded app is not still ticking a training loop.
 *
 * A loop instance is taken from the [Provider] once per run and held for that run only, so a retry is a
 * genuinely fresh engine — new seed, cleared accumulators, a newly built `TrackingPath` — rather than a
 * restarted one carrying a previous session's residue.
 *
 * Nothing in here invents a number. The result card is the
 * [com.gamecore.aimlab.engine.SessionSummary] the loop produced on [AimTrainingLoop.stop]; whether the run
 * was kept is the repository's answer, not an assumption; and the one figure this class derives itself —
 * the live time-on-target readout — is measured from the loop's own frames and is replaced by the engine's
 * authoritative value the moment the run ends.
 */
@HiltViewModel
class TrackingViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
) : ViewModel() {

    /** What the user chose and where the run has got to. Everything except the frames. */
    private val local = MutableStateFlow(TrackingState())

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
     * The loop emits a final finished frame and can replay it to a late subscriber, and the user can also
     * end a run early — without this, a session could be stopped twice or saved twice.
     */
    private var finishing = false

    // --- live time-on-target measurement ------------------------------------------------------------
    //
    // The engine accumulates the real figure internally and only surfaces it in the summary, so there is
    // nothing on TrainingFrame to read mid-run. These three fields measure it from the frames instead:
    // each frame's on-target state is weighted by the time that actually elapsed since the previous frame,
    // which keeps the estimate honest even if the loop's `trySend` drops a frame under load — the gap is
    // still counted, it is just attributed to the state observed at its end.

    private var lastElapsedMillis = 0L
    private var onTargetMillis = 0L

    /**
     * The fraction for the frame currently being emitted.
     *
     * Read by the [combine] below rather than being a flow of its own: [loopFrames] updates it in `onEach`,
     * which runs upstream of and synchronously with the combine's transform for that same frame, so the
     * value the transform reads always belongs to the frame it is folding in. A second flow here would risk
     * emitting the pair out of step.
     */
    @Volatile private var liveOnTargetFraction = 0f

    /**
     * The active loop's frames, or a single null when there is no run.
     *
     * The measurement and the finish are handled here rather than in the [combine] below because this is
     * the one place a frame is seen exactly once, as it arrives.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop -> loop?.frames ?: flowOf<TrainingFrame?>(null) }
        .onEach { frame ->
            if (frame == null) return@onEach
            measureOnTarget(frame)
            if (frame.finished) finishRun()
        }

    val state: StateFlow<TrackingState> = combine(local, loopFrames, repository.layouts) { base, frame, layouts ->
        // Fold in the saved control layouts so the setup screen can offer a HUD, and re-resolve the chosen
        // one by id so an edit in the control editor is reflected here. A null frame means "no run" — the
        // setup and result states own the whole screen then, and the frame the state already holds (none)
        // is the correct one.
        val withLayouts = base.copy(
            layouts = layouts,
            layout = base.layout?.let { chosen -> layouts.firstOrNull { it.id == chosen.id } },
        )
        if (frame == null) withLayouts else withLayouts.copy(frame = frame, onTargetFraction = liveOnTargetFraction)
    }
        // Fires when the screen stops collecting and the grace window expires — the same moment the loop's
        // ticker is cancelled. A run cannot survive that, so the state must not be left claiming it did.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = TrackingState(),
        )

    // ------------------------------------------------------------------------------ setup

    fun onDifficultySelected(difficulty: Difficulty) {
        local.update { if (it.phase == TrackingPhase.Running) it else it.copy(difficulty = difficulty) }
    }

    fun onPatternSelected(pattern: TrackingPattern) {
        local.update { if (it.phase == TrackingPhase.Running) it else it.copy(pattern = pattern) }
    }

    fun onDurationChanged(seconds: Int) {
        val clamped = seconds.coerceIn(TrackingState.DURATION_RANGE)
        local.update { if (it.phase == TrackingPhase.Running) it else it.copy(durationSeconds = clamped) }
    }

    /** Picks the on-screen control layout (HUD) for the run, or clears it — null means no controls. */
    fun onLayoutSelected(layout: ControlLayout?) {
        local.update { if (it.phase == TrackingPhase.Running) it else it.copy(layout = layout) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The chosen [TrackingState.pattern] is passed through as [TrainingConfig.pattern], which is what the
     * loop builds its `TrackingPath` from — it is the movement the target actually follows, not a label on
     * the card. Leaving it null would hand the choice back to the difficulty, so it is always set.
     *
     * The loop is started before it is published to [active]: the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run.
     */
    fun start() {
        val current = local.value
        if (current.phase == TrackingPhase.Running) return

        finishing = false
        resetMeasurement()

        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.TRACKING,
                difficulty = current.difficulty,
                durationSeconds = current.durationSeconds,
                pattern = current.pattern,
                layoutId = current.layout?.id,
            ),
        )
        local.value = current.copy(
            phase = TrackingPhase.Running,
            loop = loop,
            frame = null,
            onTargetFraction = 0f,
            result = null,
            saving = false,
            recorded = false,
        )
        active.value = loop
    }

    /** Ends the run now, at the user's request. An early stop is still a real session if anything happened. */
    fun stopRun() = finishRun()

    /** Back to the setup card without running anything — the way out of a finished run that is not a retry. */
    fun reset() {
        finishing = false
        resetMeasurement()
        active.value = null
        local.update {
            it.copy(
                phase = TrackingPhase.Setup,
                loop = null,
                frame = null,
                onTargetFraction = 0f,
                result = null,
                saving = false,
                recorded = false,
            )
        }
    }

    /**
     * Drops a run that was still going when the screen stopped collecting.
     *
     * Not a stop: nothing is read off the loop and nothing is written, because the user did not end this
     * session — they left it, and a session nobody was present for is not a session. By the time this runs
     * the collection that *was* the run has already been cancelled and the ticker is gone; what this does is
     * make the state agree with that, so a returning user gets the setup card instead of a frozen arena with
     * a timer that silently ran on. [finishing] is latched true so a final frame from the loop being torn
     * down cannot reach [finishRun] and persist a session the user never finished; [start] clears it.
     */
    private fun abandonRun() {
        if (local.value.phase != TrackingPhase.Running) return
        finishing = true
        resetMeasurement()
        active.value = null
        local.update {
            it.copy(
                phase = TrackingPhase.Setup,
                loop = null,
                frame = null,
                onTargetFraction = 0f,
                result = null,
                saving = false,
                recorded = false,
            )
        }
    }

    /**
     * Folds one frame into the live time-on-target estimate.
     *
     * Uses the frame's own `elapsedMillis` as the clock — the loop pauses that clock when the run is paused,
     * so a backgrounded run contributes no time in either direction and the fraction does not sag while the
     * app is away.
     */
    private fun measureOnTarget(frame: TrainingFrame) {
        val elapsed = frame.elapsedMillis
        val delta = (elapsed - lastElapsedMillis).coerceAtLeast(0L)
        lastElapsedMillis = elapsed
        if (delta > 0L && frame.targets.any { it.center.distanceTo(frame.crosshair) <= it.radius }) {
            onTargetMillis += delta
        }
        liveOnTargetFraction = if (elapsed > 0L) {
            (onTargetMillis.toFloat() / elapsed.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    private fun resetMeasurement() {
        lastElapsedMillis = 0L
        onTargetMillis = 0L
        liveOnTargetFraction = 0f
    }

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * Nulling [active] is what actually shuts the engine down: the frames collection is cancelled, and the
     * loop's `awaitClose` tears its ticker down. The summary is read first, because a cancelled loop has
     * nothing left to report. A null summary means the loop judged the run empty (no tracking time at all);
     * that is not saved and the screen says so rather than showing a card of zeroes.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                phase = TrackingPhase.Result,
                loop = null,
                frame = null,
                result = summary,
                saving = summary != null,
                recorded = false,
            )
        }
        if (summary == null) return

        viewModelScope.launch {
            val id = repository.saveSession(summary)
            // Only report back if the screen is still showing this run: a quick retry must not have its
            // fresh state overwritten by the previous run's write landing late.
            local.update { if (it.result === summary) it.copy(saving = false, recorded = id > 0L) else it }
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
