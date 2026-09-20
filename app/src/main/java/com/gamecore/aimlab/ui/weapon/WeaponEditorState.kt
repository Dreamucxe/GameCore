package com.gamecore.aimlab.ui.weapon

import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.FireMode
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.aimlab.engine.WeaponCategory
import kotlin.math.roundToInt

/**
 * Everything the weapon editor draws: the saved weapons, and the one being edited.
 *
 * The screen is two halves of the same state. [weapons] is the stored list, observed straight from the
 * repository, and [draft] is the working copy of whichever one the user opened — or a new one. Nothing
 * here is invented: an empty [weapons] means the tables really are empty and the screen says so rather
 * than drawing a placeholder rifle.
 *
 * [loading] is the frame before the weapons flow has emitted, kept distinct from "loaded and empty" so
 * the empty state does not flash up before the database has answered.
 *
 * [error] is set when a write actually failed. A save that did not happen must not look like one that
 * did, and there is no silent path here: either the weapons flow re-emits with the new row, or this says
 * why it did not.
 *
 * [editingBuiltIn] is the gate for §13's rule that the shipped weapons are not the user's to change: a
 * built-in can be opened and read, and duplicated into an editable copy, but it cannot be renamed in
 * place, re-tuned or deleted. Every control the editor draws for a built-in is therefore read-only, and
 * the only action offered is Duplicate.
 */
data class WeaponEditorState(
    val loading: Boolean = true,
    val weapons: List<Weapon> = emptyList(),
    val draft: WeaponDraft = WeaponDraft(),
    val editingId: Long? = null,
    val editingBuiltIn: Boolean = false,
    val confirmingDelete: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    /** True when the draft has never been saved, so the editor is building rather than amending. */
    val isNew: Boolean get() = editingId == null

    /** A weapon needs a name before it is worth storing, and a built-in is never written back. */
    val canSave: Boolean get() = !editingBuiltIn && !saving && draft.name.isNotBlank()

    /** Only a saved, user-made weapon can be deleted; the built-ins are the section's floor. */
    val canDelete: Boolean get() = editingId != null && !editingBuiltIn && !saving

    /** A built-in must exist before it can be copied, and a brand-new draft has nothing to copy yet. */
    val canDuplicate: Boolean get() = editingId != null && !saving

    /** The editor card's heading: the weapon's own name once it has one, else what is being made. */
    val editorTitle: String
        get() = when {
            draft.name.isNotBlank() -> draft.name
            isNew -> "New weapon"
            else -> "Weapon"
        }

    /** The one-line summary under a weapon in the list: what family it is and how fast it fires. */
    fun subtitleFor(weapon: Weapon): String =
        "${weapon.category.label} · ${weapon.fireRateRpm} rpm · ${weapon.magazineSize} rounds"
}

/**
 * The editable form of a [Weapon], with every parameter held as the whole number its slider moves in.
 *
 * The model's aim-affecting fields are fractions — a movement penalty of `0.4`, a spread of `0.010`, a
 * vertical kick of `0.020` arena units — and a slider that moved in those would be a thumb the user
 * cannot place. So each one is carried here as an integer on a scale a person can read (a percent, a
 * thousandth of an arena unit, a tenth of a unit per second) and converted back at exactly one place:
 * [toWeapon] on the way out, [from] on the way in. That keeps the conversion out of the composables,
 * where it would otherwise be repeated eleven times and rounded eleven different ways.
 *
 * The integer ranges below are the editor's, not the engine's. They are deliberately narrower than
 * [Weapon.normalised]'s clamps — a 30 rpm weapon is legal but not useful to train against — and
 * `normalised()` is still called before every save, so a value that somehow arrives outside them is
 * corrected by the engine rather than stored.
 */
data class WeaponDraft(
    val name: String = "",
    val category: WeaponCategory = DEFAULTS.category,
    val fireRateRpm: Int = DEFAULTS.fireRateRpm,
    val magazineSize: Int = DEFAULTS.magazineSize,
    val reloadMillis: Int = DEFAULTS.reloadMillis.toInt(),
    val adsMillis: Int = DEFAULTS.adsTimeMillis.toInt(),
    /** Percent of aim error movement adds: `40` is the model's `0.4`. */
    val movementPenaltyPercent: Int = percentOf(DEFAULTS.movementPenalty),
    /** Spread as a percent of an arena width: `1` is the model's `0.010`. */
    val spreadPercent: Int = percentOf(DEFAULTS.spread),
    /** Vertical kick per shot in thousandths of an arena unit: `20` is the model's `0.020`. */
    val verticalPerMille: Int = perMilleOf(DEFAULTS.recoil.verticalPerShot),
    /** Horizontal kick per shot in thousandths of an arena unit: `8` is the model's `0.008`. */
    val horizontalPerMille: Int = perMilleOf(DEFAULTS.recoil.horizontalPerShot),
    /** How much of each kick is randomised, as a percent: `30` is the model's `0.3`. */
    val randomnessPercent: Int = percentOf(DEFAULTS.recoil.randomness),
    /** Recovery in tenths of a unit per second: `25` is the model's `2.5`. */
    val recoveryTenths: Int = tenthsOf(DEFAULTS.recoil.recoveryPerSecond),
    val fireMode: FireMode = DEFAULTS.fireMode,
    val burstCount: Int = DEFAULTS.burstCount,
) {

    /**
     * The recoil parameters as the engine wants them.
     *
     * This is what the live preview draws, so it is a plain derived value rather than something the
     * view model recomputes: the moment a recoil slider moves, the draft changes, this changes, and the
     * canvas redraws the pattern those numbers actually produce.
     */
    val recoil: RecoilSpec
        get() = RecoilSpec(
            verticalPerShot = verticalPerMille / PER_MILLE,
            horizontalPerShot = horizontalPerMille / PER_MILLE,
            randomness = randomnessPercent / PERCENT,
            recoveryPerSecond = recoveryTenths / TENTHS,
        )

    /** Milliseconds between shots at the drafted fire rate, straight from the model's own derivation. */
    val shotIntervalMillis: Long get() = toWeapon(id = 0L, isBuiltIn = false).shotIntervalMillis

    /** How long a full magazine takes to empty at this fire rate, for the fire-rate row's description. */
    val magazineDurationMillis: Long get() = shotIntervalMillis * magazineSize.coerceAtLeast(1)

    /**
     * Builds the stored weapon. Not normalised here — the caller does that, so the one place the engine's
     * clamps are applied is immediately before the write.
     */
    fun toWeapon(id: Long, isBuiltIn: Boolean): Weapon = Weapon(
        id = id,
        name = name,
        category = category,
        fireRateRpm = fireRateRpm,
        magazineSize = magazineSize,
        reloadMillis = reloadMillis.toLong(),
        adsTimeMillis = adsMillis.toLong(),
        movementPenalty = movementPenaltyPercent / PERCENT,
        spread = spreadPercent / PERCENT,
        recoil = recoil,
        fireMode = fireMode,
        burstCount = burstCount,
        isBuiltIn = isBuiltIn,
    )

    companion object {
        /**
         * The model's own defaults, so a new weapon starts where [Weapon] says a weapon starts rather
         * than at a second set of numbers written out here that would drift from it.
         */
        private val DEFAULTS = Weapon(name = "")

        private const val PERCENT = 100f
        private const val PER_MILLE = 1_000f
        private const val TENTHS = 10f

        /** Fast enough to train against, up to the engine's ceiling. */
        val RPM_RANGE = 60..Weapon.MAX_RPM

        /** A single-shot weapon up to a drum; the engine allows more, no training mode needs it. */
        val MAGAZINE_RANGE = 1..100

        /** Under 300 ms is not a reload the player can feel; over 5 s is a pause, not a weapon. */
        val RELOAD_RANGE = 300..5_000

        /** Instant ADS teaches nothing, and a full second is the slowest sniper worth drilling. */
        val ADS_RANGE = 100..1_000

        /** Any 0..100 percent field: movement penalty and recoil randomness. */
        val PERCENT_RANGE = 0..100

        /** 0..20% maps onto the engine's 0f..0.2f spread clamp exactly. */
        val SPREAD_RANGE = 0..20

        /** 0..100 thousandths maps onto the engine's 0f..0.1f per-shot kick clamp exactly. */
        val KICK_RANGE = 0..100

        /** 0..100 tenths maps onto the engine's 0f..10f recovery clamp exactly. */
        val RECOVERY_RANGE = 0..100

        /** Rounds per burst, matching the engine's [Weapon.MIN_BURST]..[Weapon.MAX_BURST] clamp. */
        val BURST_RANGE = Weapon.MIN_BURST..Weapon.MAX_BURST

        /** §13 caps a weapon name at this; the sanitiser enforces it again before the write. */
        const val MAX_NAME_LENGTH = 40

        /** Opens an existing weapon for editing, rounding each fraction onto its slider's scale. */
        fun from(weapon: Weapon): WeaponDraft = WeaponDraft(
            name = weapon.name,
            category = weapon.category,
            fireRateRpm = weapon.fireRateRpm.coerceIn(RPM_RANGE),
            magazineSize = weapon.magazineSize.coerceIn(MAGAZINE_RANGE),
            reloadMillis = weapon.reloadMillis.toInt().coerceIn(RELOAD_RANGE),
            adsMillis = weapon.adsTimeMillis.toInt().coerceIn(ADS_RANGE),
            movementPenaltyPercent = percentOf(weapon.movementPenalty).coerceIn(PERCENT_RANGE),
            spreadPercent = percentOf(weapon.spread).coerceIn(SPREAD_RANGE),
            verticalPerMille = perMilleOf(weapon.recoil.verticalPerShot).coerceIn(KICK_RANGE),
            horizontalPerMille = perMilleOf(weapon.recoil.horizontalPerShot).coerceIn(KICK_RANGE),
            randomnessPercent = percentOf(weapon.recoil.randomness).coerceIn(PERCENT_RANGE),
            recoveryTenths = tenthsOf(weapon.recoil.recoveryPerSecond).coerceIn(RECOVERY_RANGE),
            fireMode = weapon.fireMode,
            burstCount = weapon.burstCount.coerceIn(BURST_RANGE),
        )

        private fun percentOf(value: Float): Int = (value * PERCENT).roundToInt()

        private fun perMilleOf(value: Float): Int = (value * PER_MILLE).roundToInt()

        private fun tenthsOf(value: Float): Int = (value * TENTHS).roundToInt()
    }
}
