package com.gamecore.aimlab.ui.flick

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
 * Runs one flick session and keeps the screen's state while it happens.
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
 * genuinely fresh engine — new seed, cleared accumulators — rather than a restarted one carrying a previous
 * session's residue.
 *
 * Nothing in here invents a number. The live figures are the loop's frame; the result card is the
 * [com.gamecore.aimlab.engine.SessionSummary] the loop produced on [AimTrainingLoop.stop]; and whether the
 * run was kept is the repository's answer, not an assumption.
 */
@HiltViewModel
class FlickViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
) : ViewModel() {

    /** What the user chose and where the run has got to. Everything except the frames. */
    private val local = MutableStateFlow(FlickState())

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

    /**
     * The active loop's frames, or a single null when there is no run.
     *
     * The end of a run is handled here rather than in the [combine] below, because this is the one place a
     * frame is seen exactly once, as it arrives. Abandonment is *not* detected from a frame: it is handled by
     * the `onCompletion` below, which fires at the moment the collection is torn down. The `!frame.running`
     * branch is a last-resort guard for a loop that somehow reports neither running nor finished — it should
     * be unreachable, because every path that stops a loop sets `finished` as well.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop -> loop?.frames ?: flowOf<TrainingFrame?>(null) }
        .onEach { frame ->
            when {
                frame == null -> Unit
                frame.finished -> finishRun()
                !frame.running -> abandonRun()
            }
        }

    val state: StateFlow<FlickState> = combine(local, loopFrames, repository.layouts) { base, frame, layouts ->
        // Fold in the saved control layouts so the setup screen can offer a HUD, and re-resolve the chosen
        // one by id so an edit in the control editor is reflected here. A null frame means "no run".
        val withLayouts = base.copy(
            layouts = layouts,
            layout = base.layout?.let { chosen -> layouts.firstOrNull { it.id == chosen.id } },
        )
        if (frame == null) withLayouts else withLayouts.copy(frame = frame)
    }
        // Fires when the screen stops collecting and the grace window expires — the same moment the loop's
        // ticker is cancelled. Abandoning here rather than waiting to notice a dead loop on the way back
        // matters twice over: the state cannot sit claiming a run that no longer ticks, and [finishing] is
        // latched, so a torn-down loop's final frame can never be mistaken for a run the user finished and
        // saved as a session they never played.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = FlickState(),
        )

    // ------------------------------------------------------------------------------ setup

    fun onDifficultySelected(difficulty: Difficulty) {
        local.update { if (it.phase == FlickPhase.Running) it else it.copy(difficulty = difficulty) }
    }

    fun onDurationChanged(seconds: Int) {
        val clamped = seconds.coerceIn(FlickState.DURATION_RANGE)
        local.update { if (it.phase == FlickPhase.Running) it else it.copy(durationSeconds = clamped) }
    }

    /** Picks the on-screen control layout (HUD) for the run, or clears it — null means no controls. */
    fun onLayoutSelected(layout: ControlLayout?) {
        local.update { if (it.phase == FlickPhase.Running) it else it.copy(layout = layout) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The loop is started before it is published to [active]: the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run.
     */
    fun start() {
        val current = local.value
        if (current.phase == FlickPhase.Running) return

        finishing = false
        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.FLICK,
                difficulty = current.difficulty,
                durationSeconds = current.durationSeconds,
                layoutId = current.layout?.id,
            ),
        )
        local.value = current.copy(
            phase = FlickPhase.Running,
            loop = loop,
            frame = null,
            result = null,
            saving = false,
            recorded = false,
            abandoned = false,
        )
        active.value = loop
    }

    /** Ends the run now, at the user's request. An early stop is still a real session if anything happened. */
    fun stopRun() = finishRun()

    /**
     * Drops a run that was still going when the screen stopped collecting.
     *
     * The loop stops ticking the moment its frames stop being collected, and it does not keep the clock for
     * us: coming back to it would mean a frozen arena and a timer that had silently run on. So the run is
     * discarded — which is exactly what the loop itself says an un-stopped run is — and the user is returned
     * to the setup card and told, rather than left staring at an arena that will never finish.
     *
     * [finishing] is latched true rather than cleared, so a final frame from the loop being torn down cannot
     * reach [finishRun] and persist a session the user walked away from. [start] clears it for the next run.
     */
    private fun abandonRun() {
        if (local.value.phase != FlickPhase.Running) return
        finishing = true
        active.value = null
        local.update {
            it.copy(
                phase = FlickPhase.Setup,
                loop = null,
                frame = null,
                result = null,
                saving = false,
                recorded = false,
                abandoned = true,
            )
        }
    }

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * Nulling [active] is what actually shuts the engine down: the frames collection is cancelled, and the
     * loop's `awaitClose` tears its ticker down. The summary is read first, because a cancelled loop has
     * nothing left to report. A null summary means the loop judged the run empty (no shots, nothing timed);
     * that is not saved and the screen says so rather than showing a card of zeroes.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                phase = FlickPhase.Result,
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
