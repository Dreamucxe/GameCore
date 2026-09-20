package com.gamecore.aimlab.ui.movement

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Stats
import com.gamecore.aimlab.engine.Vec2
import com.gamecore.aimlab.engine.Weapon

/** The three things the movement screen can be doing. There is no fourth, and no two at once. */
enum class MovementPhase { Setup, Running, Result }

/**
 * Everything the movement-training screen draws, in one value.
 *
 * Movement training is aim-while-moving (§7): the targets and the shooting are the flick mode's, but the
 * score is scaled by how much of the run the player actually spent moving. `Scoring.movement` is literally
 * `flick(...) * consistency`, where consistency is the mean of a per-tick sample of the strafe state. So
 * standing still and shooting perfectly scores zero, and the strafe control is not decoration — it is half
 * the exercise.
 *
 * That also sets the honest limit of this screen, which [movingNow] and the result card both respect: the
 * engine samples a **boolean**. It knows whether the player was moving on each tick and nothing else. It
 * does not model acceleration, strafe direction or a weapon's movement penalty, so this screen claims none
 * of those. See [MovementViewModel] for what the weapon selection does and does not do here.
 *
 * The split that matters is between what the *user* chose ([difficulty], [durationSeconds], [weapon],
 * [sensitivity], [layout]) and what the *run* produced ([frame], [result]). The second is never synthesised:
 * every live figure reads through [frame], so before the first tick there is nothing to show rather than a
 * row of zeroes (§1/§30).
 *
 * [loop] is the one non-data field, carried because the shared `TrainingSurface` needs the loop instance to
 * route shots and aim into. Its identity is stable for the length of a run, so equality still behaves, and
 * it is non-null only while a run exists.
 */
data class MovementState(
    val phase: MovementPhase = MovementPhase.Setup,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    val weapons: List<Weapon> = emptyList(),
    val sensitivities: List<SensitivityProfile> = emptyList(),
    val layouts: List<ControlLayout> = emptyList(),
    val weapon: Weapon? = null,
    val sensitivity: SensitivityProfile? = null,
    val layout: ControlLayout? = null,
    val loop: AimTrainingLoop3D? = null,
    val frame: TrainingFrame? = null,
    val movingNow: Boolean = false,
    val result: SessionSummary? = null,
    val saving: Boolean = false,
    val recorded: Boolean = false,
    val abandoned: Boolean = false,
) {

    /** True when there is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == MovementPhase.Running && loop != null && frame != null

    /** Whether the loop is ticking, as the loop itself reports it. What the Pause/Resume button reflects. */
    val running: Boolean get() = frame?.running == true

    // --- live figures, all straight off the loop's frame ---

    val hits: Int get() = frame?.hits ?: 0
    val shots: Int get() = frame?.shots ?: 0
    val elapsedMillis: Long get() = frame?.elapsedMillis ?: 0L

    /** Accuracy as a 0..1 fraction through the engine's own maths, so live and saved cannot disagree. */
    val accuracyFraction: Float get() = Stats.accuracy(hits, shots)

    /** Time left in a timed run, floored at zero so a late frame cannot show a negative countdown. */
    val remainingMillis: Long
        get() = (durationSeconds * 1_000L - elapsedMillis).coerceAtLeast(0L)

    // --- option lists, each with the honest "no choice" entry in front ---

    val weaponOptions: List<Weapon?> get() = listOf<Weapon?>(null) + weapons
    val sensitivityOptions: List<SensitivityProfile?>
        get() = listOf<SensitivityProfile?>(null) + sensitivities
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    /** Whether there is any saved gear at all to offer. With none, the setup card is difficulty and time. */
    val hasGear: Boolean
        get() = weapons.isNotEmpty() || sensitivities.isNotEmpty() || layouts.isNotEmpty()

    /**
     * True when a finished run produced nothing to store.
     *
     * The loop returns a null summary when the run recorded nothing, and that is the whole of it: no card,
     * no zeroes, just the note saying so.
     */
    val nothingRecorded: Boolean get() = phase == MovementPhase.Result && result == null

    companion object {

        /**
         * `CUSTOM` is excluded: it means "use the values the user configured elsewhere", and there is no way
         * to author those here, so offering it would be an option that silently meant "normal".
         */
        val DIFFICULTIES: List<Difficulty> = Difficulty.entries.filter { it != Difficulty.CUSTOM }

        /** The run lengths offered, in seconds. Long enough to sample movement, short enough to repeat. */
        val DURATIONS: List<Int> = listOf(30, 60, 90, 120)

        const val DEFAULT_DURATION_SECONDS = 60

    }
}

/**
 * Where the two strafe pads sit, in the arena's own 0..1 coordinates.
 *
 * This exists as a value rather than a handful of constants because **three** places have to agree on the
 * same rectangles to the pixel: the renderer that draws the pads, the touch targets laid over them, and the
 * exclusion predicate handed to `TrainingSurface`. That last one is the reason the agreement has to be exact
 * — the surface treats a short press as a shot regardless of whether anything above it consumed the event,
 * so a thumb resting on a pad that the predicate does not cover becomes a shot into empty space and a miss
 * against the player's accuracy. Three hand-copied sets of numbers would drift; one value cannot.
 *
 * It is built per arena size rather than fixed, because the foot of the screen is not free space: the
 * floating navigation bar draws over every screen in the app, and a pad extending under it would take
 * touches the navigation bar gets first. [forArena] takes that inset in pixels and keeps the pads above it.
 */
data class StrafePads(
    val top: Float,
    val bottom: Float,
    val leftEnd: Float,
    val rightStart: Float,
) {

    /** Whether an arena point falls on either pad. The exclusion predicate, and the hit test. */
    fun contains(point: Vec2): Boolean =
        point.y in top..bottom && (point.x <= leftEnd || point.x >= rightStart)

    companion object {

        /** How tall a pad is, as a fraction of the arena — a comfortable thumb's reach, not more. */
        const val HEIGHT_FRACTION = 0.24f

        /** How far in from each side edge a pad reaches. The two are mirrored. */
        const val WIDTH_FRACTION = 0.26f

        /** The highest the pads are ever pushed, so they cannot creep up into the shooting area. */
        private const val HIGHEST_TOP = 0.45f

        /**
         * Lays the pads out for an arena [heightPx] tall, keeping [bottomInsetPx] clear at the foot.
         *
         * A zero or negative height means the arena has not been measured yet; the pads collapse to the
         * bottom edge rather than dividing by zero, and the first real measurement replaces them.
         */
        fun forArena(heightPx: Float, bottomInsetPx: Float): StrafePads {
            val bottom = if (heightPx <= 0f) 1f else (1f - bottomInsetPx / heightPx).coerceIn(0f, 1f)
            val top = (bottom - HEIGHT_FRACTION).coerceAtLeast(HIGHEST_TOP).coerceAtMost(bottom)
            return StrafePads(
                top = top,
                bottom = bottom,
                leftEnd = WIDTH_FRACTION,
                rightStart = 1f - WIDTH_FRACTION,
            )
        }
    }
}

/** Which way the player is strafing. The engine only samples "moving or not"; this is for the display. */
enum class StrafeDirection { None, Left, Right }
