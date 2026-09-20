package com.gamecore.aimlab.ui.recoil

import com.gamecore.aimlab.TrainingFrame
import com.gamecore.aimlab.runtime.AimTrainingLoop3D
import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.Vec2
import com.gamecore.aimlab.engine.Weapon

/** The three things the recoil screen can be doing. There is no fourth, and no two at once. */
enum class RecoilPhase { Setup, Running, Result }

/**
 * Everything the recoil training screen draws, in one value.
 *
 * Recoil is the one training mode that cannot run without a weapon: the pattern the player is fighting is
 * generated entirely from the chosen [Weapon]'s `RecoilSpec` (§6/§7), so there is no sensible default and
 * nothing to fall back on. That is why [weapons] is part of the state rather than a detail of the setup
 * card, and why [canStart] is false until one of them is picked — a Start button that began a run with an
 * invented weapon would be inventing the very thing being trained.
 *
 * [weaponsLoaded] separates "the repository says there are none" from "the repository has not answered
 * yet". Without it the screen would flash "No weapons yet" on every entry in the frame before the Flow's
 * first emission, which reads as data loss rather than as loading (§1/§30).
 *
 * Live figures all read through [frame], so before the first tick there is nothing to show and the screen
 * says so instead of drawing zeroes. There is deliberately **no live score here**: the loop scores recoil
 * only at `stop()`, from the mean residual over the whole run, and its mid-run `score` field is a constant
 * zero for this mode — showing it would be a readout that says "0" no matter how well the run is going.
 * The score appears once, on the result card, out of the [SessionSummary] the loop produced.
 *
 * [loop] is the one non-data field, and it is here so the arena can route shots and aim deltas into the
 * run it is drawing without the screen having to collect a second flow. It is non-null only while a run
 * exists — setup and results have no loop — and its identity is stable for the length of a run, so
 * equality still behaves.
 */
data class RecoilState(
    val phase: RecoilPhase = RecoilPhase.Setup,
    val weapons: List<Weapon> = emptyList(),
    val weaponsLoaded: Boolean = false,
    val selectedWeaponId: Long? = null,
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
) {

    /**
     * The weapon the run uses, resolved from the stored id against the live list.
     *
     * An id rather than the object, so a weapon edited in the weapon editor while this screen is open
     * resolves to its current parameters instead of a stale copy. An id that no longer matches anything
     * resolves to null, which disables Start — the correct outcome for a weapon deleted under us.
     */
    val weapon: Weapon? get() = weapons.firstOrNull { it.id == selectedWeaponId }

    /** True only once the repository has actually reported an empty weapon list. */
    val noWeapons: Boolean get() = weaponsLoaded && weapons.isEmpty()

    /** A run needs a weapon and no run already in progress. Both are hard requirements, not preferences. */
    val canStart: Boolean get() = phase != RecoilPhase.Running && weapon != null

    /** The HUD options for the picker: "None" first (no controls), then every saved layout. */
    val layoutOptions: List<ControlLayout?> get() = listOf<ControlLayout?>(null) + layouts

    /** There is a loop and a frame from it — the only condition under which the arena is drawn. */
    val isLive: Boolean get() = phase == RecoilPhase.Running && loop != null && frame != null

    // --- live figures, all straight off the loop's frame ---

    /** The standing recoil offset in arena units: what the player is fighting, right now. */
    val recoilOffset: Vec2 get() = frame?.recoilOffset ?: Vec2(0f, 0f)

    /** How far off centre the aim currently sits, in arena units. Lower is better compensation. */
    val residual: Float get() = recoilOffset.length

    val shots: Int get() = frame?.shots ?: 0

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
     * True once the run is over and nothing was kept — either the loop judged it empty or the repository
     * rejected it. Said plainly rather than dressed up as a stored session.
     */
    val wasDiscarded: Boolean get() = phase == RecoilPhase.Result && !saving && !recorded

    companion object {
        /** A minute: long enough for several magazines, short enough to retry on a whim. */
        const val DEFAULT_DURATION_SECONDS = 60

        /** The range §6 asks for — a quarter of a minute up to two. */
        val DURATION_RANGE = 15..120

        /**
         * The difficulties this screen offers.
         *
         * `CUSTOM` is excluded: it means "use the values the user set", and there is no way to author one
         * here, so offering it would be an option that silently meant "normal".
         */
        val DIFFICULTIES: List<Difficulty> = Difficulty.entries.filter { it != Difficulty.CUSTOM }
    }
}
