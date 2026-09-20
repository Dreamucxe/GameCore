package com.gamecore.aimlab.ui.reaction

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary

/**
 * Immutable UI state for the Reaction Test screen.
 *
 * The screen has three phases, derived rather than stored:
 *  - Setup   : no [loop] and no [summary].
 *  - Live    : [loop] and [frame] are both present.
 *  - Result  : [summary] is non-null.
 *
 * [loop] is the one non-data field, and it is here for the same reason it is in the other modes: the shared
 * `TrainingSurface` needs the loop instance to route taps into, and carrying it in the state means the screen
 * collects one flow rather than reading a view model field during composition — a read that is not a snapshot
 * and so has no guarantee of being re-run when the loop changes. It is non-null only while a run exists.
 *
 * Every live figure reads through [frame]; nothing here is synthesised (§30).
 */
data class ReactionState(
    val difficulty: Difficulty = Difficulty.NORMAL,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /** The saved control layouts (HUDs) the user can pick from, and the one chosen for the run. */
    val layouts: List<ControlLayout> = emptyList(),
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    val summary: SessionSummary? = null,
    val saving: Boolean = false,
    /** The repository's answer, not an assumption: true only once a row actually came back with an id. */
    val recorded: Boolean = false,
    /**
     * Set when a run was dropped because the screen stopped collecting — the user left, or backgrounded the
     * app for longer than the grace window. The run is gone and nothing was written, and the setup card says
     * so, because the alternative is the user wondering where their session went.
     */
    val abandoned: Boolean = false,
) {
    val phase: ReactionPhase
        get() = when {
            summary != null -> ReactionPhase.Result
            loop != null && frame != null -> ReactionPhase.Live
            else -> ReactionPhase.Setup
        }

    /** True while the loop is holding the randomized wait before a target appears. */
    val waiting: Boolean
        get() = frame?.targets?.isEmpty() == true && frame.running

    val elapsedSeconds: Int
        get() = ((frame?.elapsedMillis ?: 0L) / 1000L).toInt()

    val remainingSeconds: Int
        get() = (durationSeconds - elapsedSeconds).coerceAtLeast(0)

    /** The HUD options for the picker: "None" first (no controls), then every saved layout. */
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    companion object {
        const val DEFAULT_DURATION_SECONDS = 60

        /** The one place the duration bounds live, so the slider and the clamp cannot drift apart. */
        val DURATION_RANGE = 15..180
    }
}

enum class ReactionPhase { Setup, Live, Result }
