package com.gamecore.aimlab.ui.sensitivity

import com.gamecore.aimlab.engine.SensitivityPreset
import com.gamecore.aimlab.engine.SensitivityProfile
import kotlin.math.roundToInt

/**
 * Everything the Sensitivity Lab draws: the saved profiles, and the one being tuned.
 *
 * The screen is two halves of the same state, the shape the weapon editor already uses. [profiles] is the
 * stored list, observed straight from the repository and never cached here, so a save or a delete appears
 * because the flow re-emitted. [draft] is the working copy of whichever profile is open — or a new one —
 * and it is the *only* place an unsaved value lives.
 *
 * [activeId] is what "active" means in this lab, and it means one thing rather than two: the profile whose
 * numbers the preview pad and the response curve are running. Opening a profile makes it active, and the
 * pad immediately runs its maths. GameCore has no app-wide sensitivity setting to point at — every drill
 * picks its own profile when a run starts — so this is not a stored "default" and the screen does not claim
 * it is one.
 *
 * [loading] is the frame before the profiles flow has emitted, kept apart from "loaded and empty" so the
 * empty state does not flash in front of a list that is about to arrive. [error] is set only when a write
 * actually failed: a save that did not happen must not look like one that did.
 *
 * [previewAiming] and [previewGyro] are not stored on a profile — they are which *branch* of
 * [com.gamecore.aimlab.engine.SensitivityMath.apply] the preview exercises. A profile carries four
 * sensitivity numbers (hip/ADS × camera/gyro) and only two of them are live for any given input, so the
 * preview has to be told which pair to run or half the fields would be untestable.
 */
data class SensitivityLabState(
    val loading: Boolean = true,
    val profiles: List<SensitivityProfile> = emptyList(),
    val draft: SensitivityDraft = SensitivityDraft(),
    val activeId: Long? = null,
    val previewAiming: Boolean = false,
    val previewGyro: Boolean = false,
    val confirmingDelete: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {

    /** True when the draft has never been saved, so the lab is building rather than amending. */
    val isNew: Boolean get() = activeId == null

    /** A profile needs a name before it is worth storing. */
    val canSave: Boolean get() = !saving && draft.name.isNotBlank()

    /** Only a saved profile can be copied or removed; a brand-new draft has no row behind it yet. */
    val canDuplicate: Boolean get() = activeId != null && !saving
    val canDelete: Boolean get() = activeId != null && !saving

    /** The stored row the draft belongs to, or null while a new profile is being built. */
    val activeProfile: SensitivityProfile? get() = profiles.firstOrNull { it.id == activeId }

    /** The editor card's heading: the profile's own name once it has one, else what is being made. */
    val editorTitle: String
        get() = when {
            draft.name.isNotBlank() -> draft.name
            isNew -> "New profile"
            else -> "Profile"
        }

    /**
     * The profile the pad and the curve actually run.
     *
     * Built from the draft and put through the engine's own `normalised()`, so what the preview reacts to
     * is exactly what a save would store — not the draft's raw integers with a second set of limits applied
     * to them here. A data class, so it is also a sound `remember` key: the preview is rebuilt when a
     * parameter changes and at no other time.
     */
    val previewProfile: SensitivityProfile
        get() = draft.toProfile(activeId ?: SensitivityDraft.NEW_ID).normalised()

    /** The one-line summary under a profile in the list: the two figures that identify it, and its curve. */
    fun subtitleFor(profile: SensitivityProfile): String {
        val camera = SensitivityDraft.hundredthsLabel(SensitivityDraft.hundredths(profile.cameraSensitivity))
        val gyro = SensitivityDraft.hundredthsLabel(SensitivityDraft.hundredths(profile.gyroSensitivity))
        val exponent = SensitivityDraft.hundredthsLabel(SensitivityDraft.hundredths(profile.responseExponent))
        return "Camera ×$camera · gyro ×$gyro · curve ^$exponent · ${profile.deadzonePercent}% deadzone"
    }
}

/**
 * The editable form of a [SensitivityProfile], with every parameter held as the whole number its slider
 * moves in.
 *
 * The model's fields are floats — a sensitivity of `1.35`, an exponent of `0.85` — and a Compose `Slider`
 * over a float range emits whatever the thumb lands on, which is how a profile ends up storing `1.3499999`
 * and showing a different figure than it saved. So each float is carried here as hundredths of itself and
 * converted at exactly one place: [toProfile] on the way out, [from] on the way in.
 *
 * **The ranges are the engine's, not this screen's.** Every bound below is derived from the constants in
 * [SensitivityProfile] rather than written out again, so a slider cannot offer a value `normalised()` would
 * silently pull back — and if the engine's clamps change, these move with them.
 */
data class SensitivityDraft(
    val name: String = "",
    val preset: SensitivityPreset = DEFAULTS.preset,
    /** Hundredths of the base camera multiplier: `135` is the model's `1.35`. */
    val cameraHundredths: Int = hundredths(DEFAULTS.cameraSensitivity),
    /** Hundredths of the ADS multiplier applied on top of the camera figure. */
    val adsHundredths: Int = hundredths(DEFAULTS.adsMultiplier),
    /** Hundredths of the base gyro multiplier. */
    val gyroHundredths: Int = hundredths(DEFAULTS.gyroSensitivity),
    /** Hundredths of the gyro ADS multiplier. */
    val gyroAdsHundredths: Int = hundredths(DEFAULTS.gyroAdsMultiplier),
    /** Hundredths of the extra scale applied to the X axis alone. */
    val horizontalHundredths: Int = hundredths(DEFAULTS.horizontalScale),
    /** Hundredths of the extra scale applied to the Y axis alone. */
    val verticalHundredths: Int = hundredths(DEFAULTS.verticalScale),
    /** Already a whole percent in the model, so it is carried as one. */
    val deadzonePercent: Int = DEFAULTS.deadzonePercent,
    /** Already a whole percent in the model: the weight kept from the previous output. */
    val smoothingPercent: Int = DEFAULTS.smoothingPercent,
    /** Hundredths of the response exponent: `85` is the model's `0.85`. */
    val exponentHundredths: Int = hundredths(DEFAULTS.responseExponent),
    val invertX: Boolean = DEFAULTS.invertX,
    val invertY: Boolean = DEFAULTS.invertY,
) {

    /**
     * Builds the stored profile. Not normalised here — the caller does that, so the one place the engine's
     * clamps are applied is immediately before the write (and, for the preview, immediately before the
     * maths runs).
     */
    fun toProfile(id: Long): SensitivityProfile = SensitivityProfile(
        id = id,
        name = name,
        preset = preset,
        cameraSensitivity = cameraHundredths / HUNDRED,
        adsMultiplier = adsHundredths / HUNDRED,
        gyroSensitivity = gyroHundredths / HUNDRED,
        gyroAdsMultiplier = gyroAdsHundredths / HUNDRED,
        horizontalScale = horizontalHundredths / HUNDRED,
        verticalScale = verticalHundredths / HUNDRED,
        deadzonePercent = deadzonePercent,
        smoothingPercent = smoothingPercent,
        responseExponent = exponentHundredths / HUNDRED,
        invertX = invertX,
        invertY = invertY,
    )

    /**
     * Applies a named starting point, which is the whole of what [SensitivityPreset] carries: a camera
     * figure and a gyro figure. Nothing else on the draft is touched, because the enum does not describe
     * anything else and inventing a deadzone to go with "High" would be a number the engine never stated.
     */
    fun withPreset(preset: SensitivityPreset): SensitivityDraft = copy(
        preset = preset,
        cameraHundredths = hundredths(preset.camera).coerceIn(SENSITIVITY_RANGE),
        gyroHundredths = hundredths(preset.gyro).coerceIn(SENSITIVITY_RANGE),
    )

    /**
     * Demotes the draft to `CUSTOM` once its numbers no longer match the preset it claims.
     *
     * A profile labelled "Medium" that has been dragged to 2.4× is mislabelled, and the label is stored —
     * it goes into the database by `.name` and comes back out through `SensitivityPreset.fromName`. Only
     * the two figures the preset actually defines are compared, so changing a deadzone leaves the label
     * alone.
     */
    fun reconciled(): SensitivityDraft {
        if (preset == SensitivityPreset.CUSTOM) return this
        val matches = cameraHundredths == hundredths(preset.camera) &&
            gyroHundredths == hundredths(preset.gyro)
        return if (matches) this else copy(preset = SensitivityPreset.CUSTOM)
    }

    companion object {

        /** The id a not-yet-stored profile carries; the repository allocates the real one on insert. */
        const val NEW_ID = 0L

        /**
         * The model's own defaults, so a new profile starts where [SensitivityProfile] says one starts
         * rather than at a second set of numbers written out here that would drift from it.
         */
        private val DEFAULTS = SensitivityProfile(name = "")

        private const val HUNDRED = 100f

        /** §13 caps a stored name at this, and the mapper sanitises to the same length on the way in. */
        const val MAX_NAME_LENGTH = 40

        /**
         * The engine's smoothing clamp, which `normalised()` writes as a literal `0..100` rather than a
         * named constant. Mirrored here so the slider cannot exceed it; at 100 the previous output is kept
         * in full and the camera stops responding, which is the engine's behaviour and not a bug to hide.
         */
        const val MAX_SMOOTHING = 100

        /** Camera and gyro base sensitivity, straight off the engine's clamp. */
        val SENSITIVITY_RANGE: IntRange =
            hundredths(SensitivityProfile.MIN_SENS)..hundredths(SensitivityProfile.MAX_SENS)

        /** ADS multipliers and per-axis scales — the engine's one multiplier clamp, used four times. */
        val MULTIPLIER_RANGE: IntRange =
            hundredths(SensitivityProfile.MIN_MULT)..hundredths(SensitivityProfile.MAX_MULT)

        /** The response curve's exponent. Below 1 sharpens small movements, above 1 eases them. */
        val EXPONENT_RANGE: IntRange =
            hundredths(SensitivityProfile.MIN_EXPONENT)..hundredths(SensitivityProfile.MAX_EXPONENT)

        /** Zero to the engine's cap, above which a deadzone would swallow most real input. */
        val DEADZONE_RANGE: IntRange = 0..SensitivityProfile.MAX_DEADZONE

        val SMOOTHING_RANGE: IntRange = 0..MAX_SMOOTHING

        /**
         * The starting points the lab offers.
         *
         * `CUSTOM` is excluded: it means "these values are the user's own", so offering it as something to
         * tap would be a button that either did nothing or quietly reset the profile to 1.0×. It is still
         * shown as the current label — [reconciled] is what puts it there.
         */
        val PRESETS: List<SensitivityPreset> =
            SensitivityPreset.entries.filter { it != SensitivityPreset.CUSTOM }

        /** Opens a stored profile for editing, rounding each float onto its slider's scale. */
        fun from(profile: SensitivityProfile): SensitivityDraft {
            val safe = profile.normalised()
            return SensitivityDraft(
                name = safe.name,
                preset = safe.preset,
                cameraHundredths = hundredths(safe.cameraSensitivity).coerceIn(SENSITIVITY_RANGE),
                adsHundredths = hundredths(safe.adsMultiplier).coerceIn(MULTIPLIER_RANGE),
                gyroHundredths = hundredths(safe.gyroSensitivity).coerceIn(SENSITIVITY_RANGE),
                gyroAdsHundredths = hundredths(safe.gyroAdsMultiplier).coerceIn(MULTIPLIER_RANGE),
                horizontalHundredths = hundredths(safe.horizontalScale).coerceIn(MULTIPLIER_RANGE),
                verticalHundredths = hundredths(safe.verticalScale).coerceIn(MULTIPLIER_RANGE),
                deadzonePercent = safe.deadzonePercent.coerceIn(DEADZONE_RANGE),
                smoothingPercent = safe.smoothingPercent.coerceIn(SMOOTHING_RANGE),
                exponentHundredths = hundredths(safe.responseExponent).coerceIn(EXPONENT_RANGE),
                invertX = safe.invertX,
                invertY = safe.invertY,
            )
        }

        /** A model float as the hundredths its slider moves in. */
        fun hundredths(value: Float): Int = (value * HUNDRED).roundToInt()

        /**
         * A hundredths-scaled integer as a two-decimal figure: `125` becomes "1.25".
         *
         * Integer maths rather than `String.format`, so there is no locale to make a decimal point into a
         * comma and no formatter allocated per frame — this runs on the preview's readout as well as on
         * every slider label.
         */
        fun hundredthsLabel(value: Int): String {
            val negative = value < 0
            val magnitude = if (negative) -value else value
            val fraction = magnitude % 100
            val decimals = if (fraction < 10) "0$fraction" else "$fraction"
            return "${if (negative) "-" else ""}${magnitude / 100}.$decimals"
        }
    }
}
