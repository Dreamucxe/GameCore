package com.gamecore.data.database

import com.gamecore.aimlab.engine.ControlLayout
import com.gamecore.aimlab.engine.ControlOrientation
import com.gamecore.aimlab.engine.ControlRole
import com.gamecore.aimlab.engine.ControlShape
import com.gamecore.aimlab.engine.ControlWidget
import com.gamecore.aimlab.engine.Difficulty
import com.gamecore.aimlab.engine.PersonalRecord
import com.gamecore.aimlab.engine.ReactionStats
import com.gamecore.aimlab.engine.FireMode
import com.gamecore.aimlab.engine.RecoilSpec
import com.gamecore.aimlab.engine.RecordMetric
import com.gamecore.aimlab.engine.SensitivityPreset
import com.gamecore.aimlab.engine.SensitivityProfile
import com.gamecore.aimlab.engine.SessionSummary
import com.gamecore.aimlab.engine.TrainingMode
import com.gamecore.aimlab.engine.Weapon
import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.core.common.TextSanitizer

/**
 * Entity ↔ engine-model conversion for the Aim Lab tables.
 *
 * Kept apart from [Mappers] because it depends on the `aimlab.engine` package rather than `core.model`, but
 * it follows the same two rules: enum names are parsed with `entries.firstOrNull { it.name == stored }` and
 * a sane default — never `valueOf`, which throws on a row from a newer build or a hand-edited file — and
 * every stored user name goes through [TextSanitizer.sanitizeName] on the way in, because these names are
 * drawn into the UI. A control whose role is unrecognised is dropped rather than defaulted, the same way an
 * unknown HUD stat is, because there is no safe substitute for "which button is this".
 */
internal object AimLabMappers {

    private const val NAME_LIMIT = 40

    // ---------------------------------------------------------------------- sessions

    fun toEntity(summary: SessionSummary): AimLabSessionEntity = AimLabSessionEntity(
        id = summary.id,
        mode = summary.mode.name,
        difficulty = summary.difficulty.name,
        startedAtMillis = summary.startedAtMillis,
        endedAtMillis = summary.endedAtMillis,
        weaponName = summary.weaponName?.let { TextSanitizer.sanitizeName(it, NAME_LIMIT) },
        sensitivityName = summary.sensitivityName?.let { TextSanitizer.sanitizeName(it, NAME_LIMIT) },
        score = summary.score,
        hits = summary.hits,
        shots = summary.shots,
        targetsMissed = summary.targetsMissed,
        reactionAttempts = summary.reactionStats.attempts,
        reactionFastest = summary.reactionStats.fastestMillis,
        reactionSlowest = summary.reactionStats.slowestMillis,
        reactionAverage = summary.reactionStats.averageMillis,
        reactionMedian = summary.reactionStats.medianMillis,
        averageAcquireMillis = summary.averageAcquireMillis,
        trackingErrorAverage = summary.trackingErrorAverage,
        timeOnTargetFraction = summary.timeOnTargetFraction,
        recoilCompensation = summary.recoilCompensation,
        gyroStability = summary.gyroStability,
        scoringVersion = summary.scoringVersion,
    )

    fun toModel(entity: AimLabSessionEntity): SessionSummary = SessionSummary(
        id = entity.id,
        mode = TrainingMode.fromName(entity.mode) ?: TrainingMode.FLICK,
        difficulty = Difficulty.fromName(entity.difficulty),
        startedAtMillis = entity.startedAtMillis,
        endedAtMillis = entity.endedAtMillis,
        weaponName = entity.weaponName,
        sensitivityName = entity.sensitivityName,
        score = entity.score,
        hits = entity.hits,
        shots = entity.shots,
        targetsMissed = entity.targetsMissed,
        reactionStats = ReactionStats(
            attempts = entity.reactionAttempts,
            fastestMillis = entity.reactionFastest,
            slowestMillis = entity.reactionSlowest,
            averageMillis = entity.reactionAverage,
            medianMillis = entity.reactionMedian,
        ),
        averageAcquireMillis = entity.averageAcquireMillis,
        trackingErrorAverage = entity.trackingErrorAverage,
        timeOnTargetFraction = entity.timeOnTargetFraction,
        recoilCompensation = entity.recoilCompensation,
        gyroStability = entity.gyroStability,
        scoringVersion = entity.scoringVersion,
    )

    // ---------------------------------------------------------------------- records

    fun toEntity(record: PersonalRecord): AimLabRecordEntity = AimLabRecordEntity(
        id = 0L,
        mode = record.mode.name,
        difficulty = record.difficulty.name,
        weaponName = record.weaponName?.let { TextSanitizer.sanitizeName(it, NAME_LIMIT) },
        metric = record.metric.name,
        value = record.value,
        previousValue = record.previousValue,
        achievedAtMillis = record.achievedAtMillis,
        scoringVersion = record.scoringVersion,
    )

    /** Null when the stored metric name is one this build no longer knows — the row is simply skipped. */
    fun toModel(entity: AimLabRecordEntity): PersonalRecord? {
        val metric = RecordMetric.fromName(entity.metric) ?: return null
        val mode = TrainingMode.fromName(entity.mode) ?: return null
        return PersonalRecord(
            mode = mode,
            difficulty = Difficulty.fromName(entity.difficulty),
            weaponName = entity.weaponName,
            metric = metric,
            value = entity.value,
            previousValue = entity.previousValue,
            achievedAtMillis = entity.achievedAtMillis,
            scoringVersion = entity.scoringVersion,
        )
    }

    // ---------------------------------------------------------------------- weapons

    fun toEntity(weapon: Weapon): AimLabWeaponEntity {
        val safe = weapon.normalised()
        return AimLabWeaponEntity(
            id = safe.id,
            name = TextSanitizer.sanitizeName(safe.name, NAME_LIMIT),
            category = safe.category.name,
            fireRateRpm = safe.fireRateRpm,
            magazineSize = safe.magazineSize,
            reloadMillis = safe.reloadMillis,
            adsTimeMillis = safe.adsTimeMillis,
            movementPenalty = safe.movementPenalty,
            spread = safe.spread,
            verticalPerShot = safe.recoil.verticalPerShot,
            horizontalPerShot = safe.recoil.horizontalPerShot,
            randomness = safe.recoil.randomness,
            recoveryPerSecond = safe.recoil.recoveryPerSecond,
            isBuiltIn = safe.isBuiltIn,
            fireMode = safe.fireMode.name,
            burstCount = safe.burstCount,
        )
    }

    fun toModel(entity: AimLabWeaponEntity): Weapon = Weapon(
        id = entity.id,
        name = entity.name,
        category = WeaponCategory.fromName(entity.category),
        fireRateRpm = entity.fireRateRpm,
        magazineSize = entity.magazineSize,
        reloadMillis = entity.reloadMillis,
        adsTimeMillis = entity.adsTimeMillis,
        movementPenalty = entity.movementPenalty,
        spread = entity.spread,
        recoil = RecoilSpec(
            verticalPerShot = entity.verticalPerShot,
            horizontalPerShot = entity.horizontalPerShot,
            randomness = entity.randomness,
            recoveryPerSecond = entity.recoveryPerSecond,
        ),
        fireMode = FireMode.fromName(entity.fireMode),
        burstCount = entity.burstCount,
        isBuiltIn = entity.isBuiltIn,
    ).normalised()

    // ------------------------------------------------------------------ sensitivity

    fun toEntity(profile: SensitivityProfile): AimLabSensitivityEntity {
        val safe = profile.normalised()
        return AimLabSensitivityEntity(
            id = safe.id,
            name = TextSanitizer.sanitizeName(safe.name, NAME_LIMIT),
            preset = safe.preset.name,
            cameraSensitivity = safe.cameraSensitivity,
            adsMultiplier = safe.adsMultiplier,
            gyroSensitivity = safe.gyroSensitivity,
            gyroAdsMultiplier = safe.gyroAdsMultiplier,
            horizontalScale = safe.horizontalScale,
            verticalScale = safe.verticalScale,
            deadzonePercent = safe.deadzonePercent,
            smoothingPercent = safe.smoothingPercent,
            responseExponent = safe.responseExponent,
            invertX = safe.invertX,
            invertY = safe.invertY,
        )
    }

    fun toModel(entity: AimLabSensitivityEntity): SensitivityProfile = SensitivityProfile(
        id = entity.id,
        name = entity.name,
        preset = SensitivityPreset.fromName(entity.preset),
        cameraSensitivity = entity.cameraSensitivity,
        adsMultiplier = entity.adsMultiplier,
        gyroSensitivity = entity.gyroSensitivity,
        gyroAdsMultiplier = entity.gyroAdsMultiplier,
        horizontalScale = entity.horizontalScale,
        verticalScale = entity.verticalScale,
        deadzonePercent = entity.deadzonePercent,
        smoothingPercent = entity.smoothingPercent,
        responseExponent = entity.responseExponent,
        invertX = entity.invertX,
        invertY = entity.invertY,
    ).normalised()

    // ---------------------------------------------------------------------- layouts

    fun toEntity(layout: ControlLayout, nowMillis: Long): AimLabLayoutEntity = AimLabLayoutEntity(
        id = layout.id,
        name = TextSanitizer.sanitizeName(layout.name, ControlLayout.MAX_NAME_LENGTH),
        createdAtMillis = if (layout.createdAtMillis > 0L) layout.createdAtMillis else nowMillis,
        updatedAtMillis = nowMillis,
    )

    fun toEntity(
        control: ControlWidget,
        layoutId: Long,
        orientation: ControlOrientation,
    ): AimLabControlEntity {
        val safe = control.normalised()
        return AimLabControlEntity(
            layoutId = layoutId,
            role = safe.role.name,
            xFraction = safe.xFraction,
            yFraction = safe.yFraction,
            widthFraction = safe.widthFraction,
            heightFraction = safe.heightFraction,
            opacityPercent = safe.opacityPercent,
            shape = safe.shape.name,
            enabled = safe.enabled,
            orientation = orientation.name,
        )
    }

    /** Every control row for a layout, both orientations, ready for [AimLabLayoutDao.save]. */
    fun controlEntities(layout: ControlLayout, layoutId: Long): List<AimLabControlEntity> =
        layout.controls.map { toEntity(it, layoutId, ControlOrientation.PORTRAIT) } +
            layout.landscapeControls.map { toEntity(it, layoutId, ControlOrientation.LANDSCAPE) }

    /**
     * Builds the model from a layout and its control rows, splitting the rows by orientation (§4).
     *
     * Rows written before schema 9 have `orientation = 'PORTRAIT'` from the migration default, so an old
     * layout's controls all land in [ControlLayout.controls] — exactly where they were before landscape
     * existed — and its landscape set is empty until edited.
     */
    fun toModel(entity: AimLabLayoutEntity, controls: List<AimLabControlEntity>): ControlLayout {
        val portrait = controls.filter { ControlOrientation.fromName(it.orientation) == ControlOrientation.PORTRAIT }
        val landscape = controls.filter { ControlOrientation.fromName(it.orientation) == ControlOrientation.LANDSCAPE }
        return ControlLayout(
            id = entity.id,
            name = entity.name,
            controls = portrait.mapNotNull(::toModelOrNull),
            landscapeControls = landscape.mapNotNull(::toModelOrNull),
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis,
        )
    }

    /** Convenience overload taking the relation row directly, the single load path (§bug-fix). */
    fun toModel(row: AimLabLayoutWithControls): ControlLayout = toModel(row.layout, row.controls)

    /** Null when the stored role is unknown to this build — the control is dropped, never guessed. */
    private fun toModelOrNull(entity: AimLabControlEntity): ControlWidget? {
        val role = ControlRole.fromName(entity.role) ?: return null
        return ControlWidget(
            role = role,
            xFraction = entity.xFraction,
            yFraction = entity.yFraction,
            widthFraction = entity.widthFraction,
            heightFraction = entity.heightFraction,
            opacityPercent = entity.opacityPercent,
            shape = ControlShape.fromName(entity.shape),
            enabled = entity.enabled,
        ).normalised()
    }
}
