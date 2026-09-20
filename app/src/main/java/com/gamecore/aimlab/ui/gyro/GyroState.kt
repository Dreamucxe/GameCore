package com.gamecore.aimlab.ui.gyro

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary

/** The three things the gyro screen can be doing. There is no fourth, and no two at once. */
enum class GyroPhase { Setup, Running, Result }

/**
 * Everything the gyro training screen draws, in one value.
 *
 * The split that matters is between what the *user* chose ([difficulty], [sensitivity], [durationSeconds])
 * and what the *device and the run* produced ([gyroAvailable], [frame], [result]). The first is held by the
 * view model; the second is reported by the hardware and the training loop and is never synthesised here.
 *
 * [gyroAvailable] is the answer [com.gamecore.aimlab.runtime.AimGyroReader.isAvailable] gave, carried in
 * state rather than re-asked at draw time. A device with no gyroscope cannot do this training at all, and
 * §5 says the screen must say so plainly instead of offering a Start button that could only ever produce a
 * motionless crosshair. There is no simulated fallback: a phone without the sensor gets an explanation
 * (§30).
 *
 * Note what is deliberately *not* here: a live score. The loop computes a gyro score only when the run ends
 * — during the run its `score` field is untouched — so putting [TrainingFrame.score] on the live HUD would
 * mean showing a hard zero for a minute and then a real number, which reads as a failed session rather than
 * as an unfinished one (§1). The live HUD shows what genuinely exists mid-run: elapsed time and how much of
 * it the crosshair has been on the target. The score arrives with [result].
 */
data class GyroState(
    val phase: GyroPhase = GyroPhase.Setup,
    val gyroAvailable: Boolean = false,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    /** The saved profiles offered in setup. Empty is normal — a profile is optional for a gyro run. */
    val sensitivities: List<SensitivityProfile> = emptyList(),
    /** The chosen profile, or null to run at the engine's default gyro scale. */
    val sensitivity: SensitivityProfile? = null,
    /** The saved control layouts (HUDs) the user can pick from, and the one chosen for the run. */
    val layouts: List<ControlLayout> = emptyList(),
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    /**
     * Time on target so far as a fraction of elapsed time, or null before the first sample.
     *
     * Measured by the engine's own [com.gamecore.aimlab.engine.TrackingAccumulator] over the frames the
     * screen has actually been handed, so it uses the same "within the target's radius" rule and the same
     * time weighting the final figure does. It is a live reading over delivered frames, not a prediction of
     * the result: the number on the result card is the loop's own, taken from the summary it produced.
     * Null rather than zero before anything has been sampled, so the strip shows an em dash instead of
     * claiming a perfect miss at the instant the run starts.
     */
    val onTargetFraction: Float? = null,
    val result: SessionSummary? = null,
    val saving: Boolean = false,
    val recorded: Boolean = false,
) {

    /** True when there is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == GyroPhase.Running && loop != null && frame != null

    /** A run can only be offered on a device that has the sensor, and only when one is not already going. */
    val canStart: Boolean get() = gyroAvailable && phase != GyroPhase.Running

    /** The HUD options for the picker: "None" first (no controls), then every saved layout. */
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    val elapsedMillis: Long get() = frame?.elapsedMillis ?: 0L

    /**
     * Whether the crosshair is inside the target right now, straight off the frame.
     *
     * The same hit test the engine accumulates with ([com.gamecore.aimlab.engine.Target.contains]), so the
     * chip on screen and the figure being accumulated cannot disagree about what "on target" means.
     */
    val onTargetNow: Boolean
        get() {
            val current = frame ?: return false
            return current.targets.any { it.contains(current.crosshair) }
        }

    /**
     * Whole seconds left in the run, counted down from the chosen duration.
     *
     * Clamped at zero: the loop finishes a frame or two either side of the deadline and a timer showing
     * "-1" in that window would look broken. Before the first frame this is the full duration, which is the
     * truth — nothing has elapsed yet.
     */
    val remainingSeconds: Int
        get() {
            val elapsed = frame?.elapsedMillis ?: return durationSeconds
            val remainingMillis = durationSeconds * 1000L - elapsed
            return ((remainingMillis + 999L) / 1000L).coerceIn(0L, durationSeconds.toLong()).toInt()
        }

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
