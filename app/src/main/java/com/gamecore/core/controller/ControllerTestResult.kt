package com.gamecore.core.controller

import kotlin.math.abs
import kotlin.math.hypot

/** Which physical stick a saved drift result belongs to. */
enum class StickKind(val label: String) {
    LEFT("Left stick"),
    RIGHT("Right stick"),
    HAT("Hat switch"),
}

/**
 * One completed drift run, as persisted.
 *
 * The result is a summary, not a sample stream. A ten-second run at a controller's event rate can contain
 * hundreds of readings, and keeping them would turn a diagnostics feature into a growing log of how someone
 * held a controller. What is kept is what a later run can be compared against: the verdict, the mean offset,
 * the jitter, the tolerance that was applied, and how many readings supported it.
 *
 * [verdict] is never inferred on read. If a stored name is unknown to this build, the row is dropped rather
 * than relabelled, so an old result cannot silently become "Stable".
 */
data class ControllerTestResult(
    val id: Long = 0L,
    val deviceSignature: String,
    val deviceName: String,
    val stick: StickKind,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val sampleCount: Int,
    val offsetX: Float,
    val offsetY: Float,
    val offsetMagnitude: Float,
    val jitter: Float,
    val stabilityPercent: Float,
    val peakMagnitude: Float,
    val verdict: DriftVerdict,
    val offsetThreshold: Float,
    val jitterThreshold: Float,
) {
    companion object {
        fun from(
            result: DriftResult,
            deviceSignature: String,
            deviceName: String,
            stick: StickKind,
            startedAtMillis: Long,
            durationMillis: Long,
        ): ControllerTestResult = ControllerTestResult(
            deviceSignature = deviceSignature,
            deviceName = deviceName,
            stick = stick,
            startedAtMillis = startedAtMillis,
            durationMillis = durationMillis,
            sampleCount = result.sampleCount,
            offsetX = result.offsetX,
            offsetY = result.offsetY,
            offsetMagnitude = result.offsetMagnitude,
            jitter = result.jitter,
            stabilityPercent = result.stabilityPercent,
            peakMagnitude = result.peakMagnitude,
            verdict = result.verdict,
            offsetThreshold = result.effectiveOffset,
            jitterThreshold = result.effectiveJitter,
        )
    }
}

sealed interface TestResultValidation {
    data class Valid(val result: ControllerTestResult) : TestResultValidation
    data class Invalid(val errors: List<String>) : TestResultValidation
}

/**
 * Validates a result before it reaches Room, so a corrupt or hand-edited row cannot be presented as a
 * measurement. Numbers must be finite and inside the normalized range the analyzer produces; the offset
 * magnitude must agree with its own components, because that is the figure the verdict was based on.
 */
object ControllerTestResultValidator {

    private const val MAX_VECTOR = 2.5f
    private const val OFFSET_TOLERANCE = 0.01f

    fun validate(result: ControllerTestResult): TestResultValidation {
        val errors = ArrayList<String>(4)
        val signature = result.deviceSignature.trim()
        val deviceName = result.deviceName.trim().ifBlank { "Unknown controller" }

        if (signature.isEmpty()) errors += "A result needs a device signature."
        if (signature.length > ControllerProfileValidator.MAX_SIGNATURE_LENGTH) {
            errors += "The device signature is too long."
        }
        if (result.deviceName.length > ControllerProfileValidator.MAX_DEVICE_NAME_LENGTH) {
            errors += "The device name is too long."
        }
        if (result.startedAtMillis < 0L) errors += "The start time is negative."
        if (result.durationMillis <= 0L) errors += "A result needs a positive duration."
        if (result.sampleCount < 0) errors += "The sample count is negative."

        if (!finite(result.offsetX, result.offsetY, result.offsetMagnitude, result.jitter, result.peakMagnitude)) {
            errors += "A measurement is not a finite number."
        }
        if (result.offsetMagnitude < 0f || result.jitter < 0f || result.peakMagnitude < 0f) {
            errors += "A measurement is negative."
        }
        if (result.peakMagnitude > MAX_VECTOR) errors += "The peak magnitude is outside a normalized stick."
        if (!result.stabilityPercent.isFinite() ||
            result.stabilityPercent < 0f ||
            result.stabilityPercent > 100f
        ) {
            errors += "Stability must be a percentage."
        }
        if (!finite(result.offsetThreshold, result.jitterThreshold) ||
            result.offsetThreshold < 0f ||
            result.jitterThreshold < 0f
        ) {
            errors += "The applied tolerance is invalid."
        }
        val derived = hypot(result.offsetX, result.offsetY)
        if (abs(derived - result.offsetMagnitude) > OFFSET_TOLERANCE) {
            errors += "The offset magnitude does not match its components."
        }
        if (result.id < 0L) errors += "The result id is negative."

        return if (errors.isEmpty()) {
            TestResultValidation.Valid(result.copy(deviceSignature = signature, deviceName = deviceName))
        } else {
            TestResultValidation.Invalid(errors)
        }
    }

    private fun finite(vararg values: Float): Boolean = values.all { it.isFinite() }
}
