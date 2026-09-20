package com.gamecore.aimlab.ui.tracking

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrackingPattern

/** The three things the tracking screen can be doing. There is no fourth, and no two at once. */
enum class TrackingPhase { Setup, Running, Result }

/**
 * Everything the tracking training screen draws, in one value.
 *
 * The screen is dumb: it renders this and calls back. The split that matters is between what the *user*
 * chose ([difficulty], [pattern], [durationSeconds]) and what the *run* produced ([frame], [result]) — the
 * first is held by the view model, the second arrives from the training loop and is never synthesised here.
 *
 * Tracking is the mode where that distinction bites hardest. The loop's [TrainingFrame] carries `hits`,
 * `shots` and a live `score`, and for `TRACKING` **all three stay zero for the whole run** — the mode is
 * hold-to-track, so there are no discrete shots, and the engine has no meaningful partial score before it
 * closes its accumulators. So this screen never shows them. A "0 hits / 0 shots" strip would read as a
 * session going badly rather than as a mode that does not count hits (§1/§30).
 *
 * [loop] is the one non-data field, and it is here deliberately. The shared `TrainingSurface` needs the loop
 * instance to route shots and aim into, so the screen has to be handed it; carrying it in the state keeps
 * the screen collecting exactly one flow instead of two, and its identity is stable for the length of a run,
 * so equality still behaves. It is non-null only while a run exists — setup and results have no loop.
 */
data class TrackingState(
    val phase: TrackingPhase = TrackingPhase.Setup,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val pattern: TrackingPattern = DEFAULT_PATTERN,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /** The saved control layouts (HUDs) the user can pick from, and the one chosen for the run. */
    val layouts: List<ControlLayout> = emptyList(),
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    /**
     * The live time-on-target fraction, measured by the view model from the frames it is handed.
     *
     * This is an *estimate* of the figure the engine is accumulating internally, sampled once per emitted
     * frame. It exists because nothing in [TrainingFrame] exposes the engine's accumulator mid-run. The
     * authoritative number is [SessionSummary.timeOnTargetFraction], and the result card reads that one —
     * never this — so the saved session and the number the user is shown at the end cannot disagree.
     */
    val onTargetFraction: Float = 0f,
    val result: SessionSummary? = null,
    val saving: Boolean = false,
    val recorded: Boolean = false,
) {

    /** True when there is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == TrackingPhase.Running && loop != null && frame != null

    /** How far into the run the loop says it is. Zero before the first tick, which is the truth. */
    val elapsedMillis: Long get() = frame?.elapsedMillis ?: 0L

    /**
     * Whether the crosshair is inside the target on the frame currently being shown.
     *
     * The same test the arena tints with and the same one [TrackingAccumulator][
     * com.gamecore.aimlab.engine.TrackingAccumulator] scores with, so the ring, the chip and the accumulated
     * percentage always agree about what "on target" means.
     */
    val onTargetNow: Boolean
        get() {
            val current = frame ?: return false
            return current.targets.any { it.center.distanceTo(current.crosshair) <= it.radius }
        }

    /**
     * Whole seconds left in the run, counted down from the chosen duration.
     *
     * Clamped at zero: the loop finishes a frame or two either side of the deadline and a timer that shows
     * "-1" in that window would look broken. Before the first frame this is the full duration — nothing has
     * elapsed yet.
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
     * A run the engine judged empty is rejected by `SessionSummary.isValid`, and the screen must say that
     * plainly instead of showing a result card that implies a stored session.
     */
    val wasDiscarded: Boolean get() = phase == TrackingPhase.Result && !saving && !recorded

    /** The HUD options for the picker: "None" first (no controls), then every saved layout. */
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    companion object {
        /** A minute: long enough to settle into a rhythm, short enough to retry on a whim. */
        const val DEFAULT_DURATION_SECONDS = 60

        /** The range §3 asks for — a quarter of a minute up to three minutes. */
        val DURATION_RANGE = 15..180

        /**
         * The pattern a fresh screen starts on.
         *
         * Deliberately the one `AimTrainingLoop.patternForDifficulty` would pick for the default NORMAL
         * difficulty, so the preselected chip describes what an untouched Start would actually run.
         */
        val DEFAULT_PATTERN = TrackingPattern.CIRCULAR

        /**
         * The difficulties this screen offers.
         *
         * `CUSTOM` is excluded: it exists for runs configured from a saved parameter set, and there is no
         * way to author one here, so offering it would be an option that silently meant "normal".
         */
        val DIFFICULTIES: List<Difficulty> = Difficulty.entries.filter { it != Difficulty.CUSTOM }

        /** Every movement pattern, in engine order. All seven are runnable, so all seven are offered. */
        val PATTERNS: List<TrackingPattern> = TrackingPattern.entries
    }
}
