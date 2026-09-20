package com.gamecore.aimlab.ui.recoil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Weapon
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
 * Runs one recoil session and keeps the screen's state while it happens.
 *
 * The shape is the section's idiom, and the important part of it is that **collection is what runs the
 * loop**. [state] folds the user's choices together with the active loop's frames and the repository's
 * weapons, and it is shared with `WhileSubscribed`; when the screen stops collecting, the frames flow is
 * cancelled, the loop's `awaitClose` fires, its ticker job is cancelled and the run is abandoned without
 * being written (§19/§29). There is no service, no listener and no coroutine that can outlive the screen.
 *
 * A loop instance is taken from the [Provider] once per run and held for that run only, so a retry is a
 * genuinely fresh engine — new seed, cleared accumulators — rather than a restarted one carrying the last
 * burst's standing offset.
 *
 * The weapon is the one thing this mode cannot do without, and it is never invented. The list comes
 * straight from [AimLabRepository.weapons] (seeded once by the Aim Lab home screen, extended by the weapon
 * editor); if it is empty this view model does not synthesise a placeholder, it simply never becomes
 * startable and the screen explains why. The seed is left at zero so the loop derives one from its clock —
 * each run gets a fresh pattern rather than the same learnable one every time.
 */
@HiltViewModel
class RecoilViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
) : ViewModel() {

    /** What the user chose and where the run has got to. Everything except the frames and the weapons. */
    private val local = MutableStateFlow(RecoilState())

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
     * The finish is handled here rather than in the [combine] below because this is the one place a frame
     * is seen exactly once, as it arrives.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop -> loop?.frames ?: flowOf<TrainingFrame?>(null) }
        .onEach { frame -> if (frame != null && frame.finished) finishRun() }

    val state: StateFlow<RecoilState> = combine(
        local,
        loopFrames,
        repository.weapons,
        repository.layouts,
    ) { base, frame, weapons, layouts ->
        // `weaponsLoaded` flips on the repository's first emission and never back: from here on, an empty
        // list is a fact about the user's armoury rather than a flow that has not spoken yet. Fold in the
        // saved control layouts too, re-resolving the chosen one by id so a control-editor edit shows here.
        val withWeapons = base.copy(
            weapons = weapons,
            weaponsLoaded = true,
            layouts = layouts,
            layout = base.layout?.let { chosen -> layouts.firstOrNull { it.id == chosen.id } },
        )
        // A null frame means "no run" — setup and result own the whole screen then, and the frame the
        // state already holds (none) is the correct one.
        if (frame == null) withWeapons else withWeapons.copy(frame = frame)
    }
        // Fires when the screen stops collecting and the grace window expires — the same moment the loop's
        // ticker is cancelled. A run cannot survive that, so the state must not be left claiming it did.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = RecoilState(),
        )

    // ------------------------------------------------------------------------------ setup

    /** Picks the weapon whose pattern the run will be fought against. Ignored mid-run. */
    fun onWeaponSelected(weapon: Weapon) {
        local.update { if (it.phase == RecoilPhase.Running) it else it.copy(selectedWeaponId = weapon.id) }
    }

    fun onDifficultySelected(difficulty: Difficulty) {
        local.update { if (it.phase == RecoilPhase.Running) it else it.copy(difficulty = difficulty) }
    }

    fun onDurationChanged(seconds: Int) {
        val clamped = seconds.coerceIn(RecoilState.DURATION_RANGE)
        local.update { if (it.phase == RecoilPhase.Running) it else it.copy(durationSeconds = clamped) }
    }

    /** Picks the on-screen control layout (HUD) for the run, or clears it — null means no controls. */
    fun onLayoutSelected(layout: ControlLayout?) {
        local.update { if (it.phase == RecoilPhase.Running) it else it.copy(layout = layout) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The weapon is read through [state] — the combined value, which is where the repository's list lives —
     * and a missing one returns rather than substituting anything; the screen keeps Start disabled in that
     * case, so this is the belt to that braces. Reading `state.value` is sound because Start and Retry can
     * only be pressed by a screen that is currently collecting, which is exactly when the value is live.
     *
     * The loop is started before it is published to [active]: the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run.
     */
    fun start() {
        val current = state.value
        if (current.phase == RecoilPhase.Running) return
        val weapon = current.weapon ?: return

        finishing = false
        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.RECOIL,
                difficulty = current.difficulty,
                weapon = weapon,
                durationSeconds = current.durationSeconds,
                layoutId = current.layout?.id,
            ),
        )
        local.update {
            it.copy(
                phase = RecoilPhase.Running,
                loop = loop,
                frame = null,
                result = null,
                saving = false,
                recorded = false,
            )
        }
        active.value = loop
    }

    /** Ends the run now, at the user's request. An early stop is still a real session if shots were fired. */
    fun stopRun() = finishRun()

    /** Back to the setup card without running anything — the way out of a finished run that is not a retry. */
    fun reset() {
        finishing = false
        active.value = null
        local.update {
            it.copy(
                phase = RecoilPhase.Setup,
                loop = null,
                frame = null,
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
     * make the state agree with that, so a returning user gets the setup card instead of an arena frozen
     * mid-burst. [finishing] is latched true so a final frame from the loop being torn down cannot reach
     * [finishRun] and persist a compensation figure for a burst the user walked away from; [start] clears it.
     */
    private fun abandonRun() {
        if (local.value.phase != RecoilPhase.Running) return
        finishing = true
        active.value = null
        local.update {
            it.copy(
                phase = RecoilPhase.Setup,
                loop = null,
                frame = null,
                result = null,
                saving = false,
                recorded = false,
            )
        }
    }

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * Nulling [active] is what actually shuts the engine down: the frames collection is cancelled, and the
     * loop's `awaitClose` tears its ticker down. The summary is read first, because a cancelled loop has
     * nothing left to report. A null summary means the loop judged the run empty (no shots fired at all);
     * that is not saved and the screen says so rather than showing a compensation figure for a burst that
     * never happened.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                phase = RecoilPhase.Result,
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
