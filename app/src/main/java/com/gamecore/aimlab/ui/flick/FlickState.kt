package com.gamecore.aimlab.ui.flick

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.runtime.AimTrainingLoop3D

/** The three things the flick screen can be doing. There is no fourth, and no two at once. */
enum class FlickPhase { Setup, Running, Result }

/**
 * Everything the flick training screen draws, in one value.
 *
 * The screen is dumb: it renders this and calls back. The split that matters is between what the *user*
 * chose ([difficulty], [durationSeconds]) and what the *run* produced ([frame], [result]) — the first is
 * held by the view model, the second arrives from the training loop and is never synthesised here. Every
 * live figure below reads through [frame], so before the first tick the screen has nothing to show and says
 * so, rather than drawing a row of zeroes that would read as a session with no hits (§1/§30).
 *
 * [loop] is the one non-data field, and it is here deliberately. The shared `TrainingSurface` needs the
 * loop instance to route shots and aim into, so the screen has to be handed it; carrying it in the state
 * keeps the screen collecting exactly one flow instead of two, and its identity is stable for the length of
 * a run, so equality still behaves. It is non-null only while a run exists — setup and results have no loop.
 */
data class FlickState(
    val phase: FlickPhase = FlickPhase.Setup,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /** The saved control layouts (HUDs) the user can pick from, and the one chosen for the run. */
    val layouts: List<ControlLayout> = emptyList(),
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    val result: SessionSummary? = null,
    val saving: Boolean = false,
    val recorded: Boolean = false,
    /**
     * Set when a run was dropped because the screen stopped collecting — the user left, or backgrounded
     * the app for longer than the grace window. The run is gone and nothing was written, and the setup
     * card says so, because the alternative is the user wondering where their session went.
     */
    val abandoned: Boolean = false,
) {

    /** True when there is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == FlickPhase.Running && loop != null && frame != null

    /** The HUD options for the picker: "None" first (no controls), then every saved layout. */
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    // --- live figures, all straight off the loop's frame ---

    val hits: Int get() = frame?.hits ?: 0
    val shots: Int get() = frame?.shots ?: 0
    val score: Int get() = frame?.score ?: 0

    /** Accuracy through the engine's own rounding, so the live figure and the saved one cannot disagree. */
    val accuracyPercent: Int get() = Stats.accuracyPercent(hits, shots)

    /**
     * Whole seconds left in the run, counted down from the chosen duration.
     *
     * Clamped at zero: the loop finishes a frame or two either side of the deadline and a timer that shows
     * "-1" in that window would look broken. Before the first frame this is the full duration, which is the
     * truth — nothing has elapsed yet.
     */
    val remainingSeconds: Int
        get() {
            val elapsed = frame?.elapsedMillis ?: return durationSeconds
            val remainingMillis = durationSeconds * 1000L - elapsed
            return ((remainingMillis + 999L) / 1000L).coerceIn(0L, durationSeconds.toLong()).toInt()
        }

    /**
     * True once the run is over and the repository has told us it kept nothing.
     *
     * A run with no shots fired is rejected by `SessionSummary.isValid`, and the screen must say that
     * plainly instead of showing a result card that implies a stored session.
     */
    val wasDiscarded: Boolean get() = phase == FlickPhase.Result && !saving && !recorded

    companion object {
        /** A minute: long enough to settle into a rhythm, short enough to retry on a whim. */
        const val DEFAULT_DURATION_SECONDS = 60

        /** The range §2 asks for — a quarter of a minute up to three minutes. */
        val DURATION_RANGE = 15..180

        /**
         * The difficulties this screen offers.
         *
         * `CUSTOM` is excluded: it exists for runs configured from a saved parameter set, and there is no
         * way to author one here, so offering it would be an option that silently meant "normal".
         */
        val DIFFICULTIES: List<Difficulty> = Difficulty.entries.filter { it != Difficulty.CUSTOM }
    }
}
