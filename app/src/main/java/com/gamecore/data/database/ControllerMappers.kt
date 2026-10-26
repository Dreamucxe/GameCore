package com.gamecore.data.database

import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.DriftVerdict
import com.gamecore.core.controller.ResponseCurveKind
import com.gamecore.core.controller.StickKind

/**
 * Room rows ↔ controller-domain models.
 *
 * Enum columns are names, not ordinals, and an unrecognised name returns null rather than a default. A row
 * written by a newer build that added a curve, a stick or a verdict must not silently render as "Linear" or
 * "Stable"; the repository drops it from the list and the user keeps every row this build can actually
 * interpret.
 */
object ControllerLabMappers {

    fun toModel(entity: ControllerProfileEntity): ControllerProfile? {
        val curve = ResponseCurveKind.entries.firstOrNull { it.name == entity.curve } ?: return null
        return ControllerProfile(
            id = entity.id,
            name = entity.name,
            deviceSignature = entity.deviceSignature,
            deviceName = entity.deviceName,
            deadzone = entity.deadzone,
            curve = curve,
            exponent = entity.exponent,
            driftOffsetThreshold = entity.driftOffset,
            driftJitterThreshold = entity.driftJitter,
            notes = entity.notes,
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis,
        )
    }

    fun toEntity(model: ControllerProfile): ControllerProfileEntity = ControllerProfileEntity(
        id = model.id,
        name = model.name,
        deviceSignature = model.deviceSignature,
        deviceName = model.deviceName,
        deadzone = model.deadzone,
        curve = model.curve.name,
        exponent = model.exponent,
        driftOffset = model.driftOffsetThreshold,
        driftJitter = model.driftJitterThreshold,
        notes = model.notes,
        createdAtMillis = model.createdAtMillis,
        updatedAtMillis = model.updatedAtMillis,
    )

    fun toModel(entity: ControllerTestResultEntity): ControllerTestResult? {
        val stick = StickKind.entries.firstOrNull { it.name == entity.stick } ?: return null
        val verdict = DriftVerdict.entries.firstOrNull { it.name == entity.verdict } ?: return null
        return ControllerTestResult(
            id = entity.id,
            deviceSignature = entity.deviceSignature,
            deviceName = entity.deviceName,
            stick = stick,
            startedAtMillis = entity.startedAtMillis,
            durationMillis = entity.durationMillis,
            sampleCount = entity.sampleCount,
            offsetX = entity.offsetX,
            offsetY = entity.offsetY,
            offsetMagnitude = entity.offsetMagnitude,
            jitter = entity.jitter,
            stabilityPercent = entity.stabilityPercent,
            peakMagnitude = entity.peakMagnitude,
            verdict = verdict,
            offsetThreshold = entity.offsetThreshold,
            jitterThreshold = entity.jitterThreshold,
        )
    }

    fun toEntity(model: ControllerTestResult): ControllerTestResultEntity = ControllerTestResultEntity(
        id = model.id,
        deviceSignature = model.deviceSignature,
        deviceName = model.deviceName,
        stick = model.stick.name,
        startedAtMillis = model.startedAtMillis,
        durationMillis = model.durationMillis,
        sampleCount = model.sampleCount,
        offsetX = model.offsetX,
        offsetY = model.offsetY,
        offsetMagnitude = model.offsetMagnitude,
        jitter = model.jitter,
        stabilityPercent = model.stabilityPercent,
        peakMagnitude = model.peakMagnitude,
        verdict = model.verdict.name,
        offsetThreshold = model.offsetThreshold,
        jitterThreshold = model.jitterThreshold,
    )
}
