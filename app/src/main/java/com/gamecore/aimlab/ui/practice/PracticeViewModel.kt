package com.gamecore.aimlab.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.aimlab.AimLabRepository
import com.gamecore.aimlab.TrainingConfig
import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SensitivityProfile
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
 * Runs one free-practice session and keeps the screen's state while it happens.
 *
 * The shape is the section's idiom, and the important part of it is that **collection is what runs the
 * loop**. [state] folds the user's choices together with the active loop's frames and the repository's three
 * lists, and it is shared with `WhileSubscribed`; when the screen stops collecting, the frames flow is
 * cancelled, the loop's `awaitClose` fires, its ticker job is cancelled and the run is abandoned without
 * being written (§19/§29). There is no service, no listener and no coroutine that can outlive the screen.
 *
 * That matters more here than in the timed modes. Free practice has no duration, so nothing ever ends a run
 * on its own — if leaving the screen did not end it, a sandbox left open would tick forever. It does end it:
 * [onCompletion] below turns the cancelled collection into an abandoned run in the state, so returning to
 * the screen shows the setup card and a note, never a frozen arena with dead buttons.
 *
 * A loop instance is taken from the [Provider] once per run and held for that run only, so a retry is a
 * genuinely fresh engine — new seed, cleared accumulators — rather than a restarted one carrying residue.
 *
 * Nothing in here invents a number. The live figures are the loop's frame; the result is the
 * [com.gamecore.aimlab.engine.SessionSummary] the loop produced on stop; and whether the run was kept is the
 * repository's answer, not an assumption. There is no score anywhere in this file, because
 * `TrainingMode.FREE_PRACTICE.scored` is false and the engine returns 0 for it.
 */
@HiltViewModel
class PracticeViewModel @Inject constructor(
    private val repository: AimLabRepository,
    private val loops: Provider<AimTrainingLoop3D>,
) : ViewModel() {

    /** What the user chose and where the run has got to. Everything except the frames and the lists. */
    private val local = MutableStateFlow(PracticeState())

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
     * The loop can replay its final frame to a late subscriber, and the user can end a run at any time —
     * without this, a session could be stopped twice or saved twice.
     */
    private var finishing = false

    /**
     * The active loop's frames, or a single null when there is no run.
     *
     * A free-practice run never finishes by itself, so the finished check here is the safety net for the
     * loop ending for any reason of its own rather than the normal path — the normal path is [stopRun].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val loopFrames: Flow<TrainingFrame?> = active
        .flatMapLatest { loop -> loop?.frames ?: flowOf<TrainingFrame?>(null) }
        .onEach { frame -> if (frame != null && frame.finished) finishRun() }

    val state: StateFlow<PracticeState> = combine(
        local,
        loopFrames,
        repository.weapons,
        repository.sensitivities,
        repository.layouts,
    ) { base, frame, weapons, sensitivities, layouts ->
        // A null frame means "no run" — setup and result own the whole screen then, and the frame the state
        // already holds (none) is the correct one.
        val withFrame = if (frame == null) base else base.copy(frame = frame)
        withFrame.copy(
            weapons = weapons,
            sensitivities = sensitivities,
            layouts = layouts,
            // Selections are resolved against the current lists rather than trusted from the last emission,
            // so a weapon deleted in its editor while this screen sat in the back stack disappears from the
            // choice instead of lingering as a stale copy that no longer exists.
            weapon = weapons.firstOrNull { it.id == base.weapon?.id },
            sensitivity = sensitivities.firstOrNull { it.id == base.sensitivity?.id },
            layout = layouts.firstOrNull { it.id == base.layout?.id },
        )
    }
        // Fires when the screen stops collecting and the grace window expires — which is also when the loop
        // is cancelled. A run cannot survive that, so the state must not claim it did.
        .onCompletion { abandonRun() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MILLIS),
            initialValue = PracticeState(),
        )

    // ------------------------------------------------------------------------------ setup

    fun onDifficultySelected(difficulty: Difficulty) = editSetup { it.copy(difficulty = difficulty) }

    fun onWeaponSelected(weapon: Weapon?) = editSetup { it.copy(weapon = weapon) }

    fun onSensitivitySelected(profile: SensitivityProfile?) = editSetup { it.copy(sensitivity = profile) }

    fun onLayoutSelected(layout: ControlLayout?) = editSetup { it.copy(layout = layout) }

    /** One tap, one write, no draft — and never a change to a run already in progress. */
    private inline fun editSetup(crossinline transform: (PracticeState) -> PracticeState) {
        local.update { if (it.phase == PracticePhase.Running) it else transform(it) }
    }

    // ------------------------------------------------------------------------------ the run

    /**
     * Starts a run with the current choices. Also what "retry" does, which is why it clears the last result.
     *
     * The config carries no duration of its own: the loop does not apply one to free practice, which is what
     * makes this the open-ended sandbox §8 asks for. The run ends when [stopRun] is called and at no other
     * time.
     *
     * The loop is started before it is published to [active]: the ticker only advances a running loop, so
     * ordering it this way means the very first frame the screen sees is already a frame of the new run.
     */
    fun start() {
        // Read through the shared state so the gear that goes into the config is the resolved, still-real
        // selection rather than whatever was picked before the lists last changed.
        val current = state.value
        if (current.phase == PracticePhase.Running) return

        finishing = false
        val loop = loops.get()
        loop.start(
            TrainingConfig(
                mode = TrainingMode.FREE_PRACTICE,
                difficulty = current.difficulty,
                weapon = current.weapon,
                sensitivity = current.sensitivity,
                layoutId = current.layout?.id,
            ),
        )
        local.update {
            it.copy(
                phase = PracticePhase.Running,
                loop = loop,
                frame = null,
                result = null,
                saving = false,
                recorded = false,
                abandoned = false,
            )
        }
        active.value = loop
    }

    /** Pauses the run. The loop freezes its clock, so paused time is not counted as time trained. */
    fun pause() {
        active.value?.pause()
    }

    /** Resumes a paused run, picking up the same session rather than starting a new one. */
    fun resume() {
        active.value?.resume()
    }

    /** Ends the run now, at the user's request — the only way a free-practice run ends. */
    fun stopRun() = finishRun()

    /**
     * Takes the summary off the loop, releases it, and persists the run.
     *
     * Nulling [active] is what actually shuts the engine down: the frames collection is cancelled and the
     * loop's `awaitClose` tears its ticker down. The summary is read first, because a cancelled loop has
     * nothing left to report.
     *
     * A null summary means the loop judged the run empty — nothing was fired — and that run is not saved and
     * not shown as a result. The screen says so instead of displaying a card of zeroes that would read as a
     * session in which every shot missed.
     */
    private fun finishRun() {
        if (finishing) return
        finishing = true

        val summary = active.value?.stop()
        active.value = null

        local.update {
            it.copy(
                phase = PracticePhase.Result,
                loop = null,
                frame = null,
                result = summary,
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
            local.update { if (it.result === summary) it.copy(saving = false, recorded = id > 0L) else it }
        }
    }

    /**
     * Drops a run that was still going when the screen stopped collecting.
     *
     * Not a stop: nothing is read off the loop and nothing is written, because the user did not end this
     * session — they walked away from it, and a session nobody was present for is not a session. The state
     * returns to setup carrying [PracticeState.abandoned] so the screen can say what became of it rather
     * than silently forgetting.
     */
    private fun abandonRun() {
        finishing = false
        active.value = null
        local.update {
            if (it.phase != PracticePhase.Running) {
                it
            } else {
                it.copy(
                    phase = PracticePhase.Setup,
                    loop = null,
                    frame = null,
                    abandoned = true,
                )
            }
        }
    }

    private companion object {
        /**
         * How long [state] — and therefore the loop — survives the screen going away.
         *
         * A second covers a rotation and nothing else. Past that the run is abandoned, which is the correct
         * outcome: an open sandbox with nobody looking at it must not keep ticking.
         */
        const val SUBSCRIPTION_GRACE_MILLIS = 1_000L
    }
}
