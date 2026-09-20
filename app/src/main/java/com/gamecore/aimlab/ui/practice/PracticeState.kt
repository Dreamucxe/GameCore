package com.gamecore.aimlab.ui.practice

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.Weapon

/** The three things the free-practice screen can be doing. There is no fourth, and no two at once. */
enum class PracticePhase { Setup, Running, Result }

/**
 * Everything the free-practice screen draws, in one value.
 *
 * Free practice is the sandbox of §8, and two absences define it: **no timer and no score**. A run lasts
 * exactly as long as the user leaves it running, so there is no duration to choose and no countdown to
 * show; and `TrainingMode.FREE_PRACTICE.scored` is false, so no score appears live or on the result card.
 * Anything this screen showed under a "Score" heading would be a number the engine deliberately does not
 * produce (it returns 0 for this mode) dressed up as an achievement.
 *
 * The split that matters is between what the *user* chose ([difficulty], [weapon], [sensitivity], [layout])
 * and what the *run* produced ([frame], [result]). The second is never synthesised here: every live figure
 * reads through [frame], so before the first tick there is nothing to show rather than a row of zeroes
 * (§1/§30).
 *
 * [weapons], [sensitivities] and [layouts] are the repository's own lists, carried so the screen can offer
 * them. Any of the three may legitimately be empty — a user who has never opened the weapon editor has no
 * weapons — and in that case the screen omits that row entirely. It never inserts a placeholder entry, and
 * the selection fields stay null, which [com.gamecore.aimlab.TrainingConfig] already allows.
 *
 * [loop] is the one non-data field, and it is here deliberately: the shared `TrainingSurface` needs the loop
 * instance to route shots and aim into, so carrying it in the state keeps the screen collecting exactly one
 * flow. Its identity is stable for the length of a run, so equality still behaves. It is non-null only while
 * a run exists — setup and results have no loop.
 */
data class PracticeState(
    val phase: PracticePhase = PracticePhase.Setup,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val weapons: List<Weapon> = emptyList(),
    val sensitivities: List<SensitivityProfile> = emptyList(),
    val layouts: List<ControlLayout> = emptyList(),
    val weapon: Weapon? = null,
    val sensitivity: SensitivityProfile? = null,
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    val result: SessionSummary? = null,
    val saving: Boolean = false,
    val recorded: Boolean = false,
    val abandoned: Boolean = false,
) {

    /** True when there is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == PracticePhase.Running && loop != null && frame != null

    /** Whether the loop is ticking, as the loop itself reports it. What the Pause/Resume button reflects. */
    val running: Boolean get() = frame?.running == true

    // --- live figures, all straight off the loop's frame ---

    val hits: Int get() = frame?.hits ?: 0
    val shots: Int get() = frame?.shots ?: 0

    /**
     * Elapsed time in the run so far.
     *
     * This counts up rather than down: there is no duration to count down from. The loop freezes it while
     * paused, so a run left paused does not accumulate time the user did not train for.
     */
    val elapsedMillis: Long get() = frame?.elapsedMillis ?: 0L

    /** Accuracy as a 0..1 fraction through the engine's own maths, so live and saved cannot disagree. */
    val accuracyFraction: Float get() = Stats.accuracy(hits, shots)

    // --- option lists, each with the honest "no choice" entry in front ---

    /**
     * The weapon options: "None" plus whatever is saved.
     *
     * The leading null is not a fabricated weapon — it is the real "run without one" case that
     * `TrainingConfig.weapon` models, and it follows the existing `ChoiceRow` idiom for an optional value.
     * The screen only renders these when the underlying list has something in it, so a user with no saved
     * weapons is never shown a row whose only entry is "None".
     */
    val weaponOptions: List<Weapon?> get() = listOf<Weapon?>(null) + weapons
    val sensitivityOptions: List<SensitivityProfile?>
        get() = listOf<SensitivityProfile?>(null) + sensitivities
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    /** Whether there is any saved gear at all to offer. With none, the setup card is difficulty alone. */
    val hasGear: Boolean
        get() = weapons.isNotEmpty() || sensitivities.isNotEmpty() || layouts.isNotEmpty()

    /**
     * True when a finished run produced nothing to store.
     *
     * The loop returns a null summary when nothing was fired, and that is the whole of it: no card, no
     * zeroes, just the note saying so.
     */
    val nothingRecorded: Boolean get() = phase == PracticePhase.Result && result == null

    companion object {
        /**
         * The difficulties this screen offers.
         *
         * `CUSTOM` is excluded: it means "use the values the user configured elsewhere", and there is no way
         * to author those here, so offering it would be an option that silently meant "normal".
         */
        val DIFFICULTIES: List<Difficulty> = Difficulty.entries.filter { it != Difficulty.CUSTOM }
    }
}
