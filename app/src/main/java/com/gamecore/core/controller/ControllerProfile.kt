package com.gamecore.core.controller

import java.security.MessageDigest
import java.util.Locale

/**
 * A saved controller setting, kept as a reference rather than applied anywhere.
 *
 * The fields describe the curve lab's simulation and the tolerance a drift run used. None of them is
 * applied to a game or to the system: the app has no supported mechanism to change a game's dead zone,
 * response curve or stick mapping, so a profile is a record of what was tested and what was simulated,
 * not a live control. The UI says that wherever a profile is saved or selected.
 *
 * [deviceSignature] is derived from capabilities (vendor, product, axis ids, declared button count), never
 * from the device's marketing name alone; two pads that report different axes are different profiles even
 * if a driver calls them both "Wireless Controller". [deviceName] is stored only so a human can tell the
 * rows apart.
 */
data class ControllerProfile(
    val id: Long = 0L,
    val name: String,
    val deviceSignature: String,
    val deviceName: String,
    val deadzone: Float = 0.08f,
    val curve: ResponseCurveKind = ResponseCurveKind.EXPONENTIAL,
    val exponent: Float = 2f,
    val driftOffsetThreshold: Float = 0.08f,
    val driftJitterThreshold: Float = 0.02f,
    val notes: String = "",
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    fun curveSettings(): ResponseCurveSettings = ResponseCurveSettings(
        deadzone = deadzone,
        kind = curve,
        exponent = exponent,
    )

    fun driftThresholds(): DriftThresholds = DriftThresholds(
        offset = driftOffsetThreshold,
        jitter = driftJitterThreshold,
    )
}

sealed interface ProfileValidation {
    data class Valid(val profile: ControllerProfile) : ProfileValidation
    data class Invalid(val errors: List<String>) : ProfileValidation
}

/**
 * The one place a profile is decided to be storable.
 *
 * Validation rejects rather than clamps. A file that asks for a 90% dead zone is not repaired into a
 * 50% one behind the user's back: it is reported, and the rest of an import still lands. The only
 * normalisation applied is whitespace trimming and a timestamp ordering fix, neither of which changes a
 * measurement.
 */
object ControllerProfileValidator {

    const val MAX_NAME_LENGTH = 40
    const val MAX_DEVICE_NAME_LENGTH = 80
    const val MAX_SIGNATURE_LENGTH = 160
    const val MAX_NOTES_LENGTH = 280
    const val MIN_DEADZONE = 0f
    const val MAX_DEADZONE = ResponseCurveSettings.MAX_DEADZONE
    const val MAX_DRIFT_OFFSET = 0.30f
    const val MAX_DRIFT_JITTER = 0.10f

    fun validate(profile: ControllerProfile): ProfileValidation {
        val errors = ArrayList<String>(4)
        val name = profile.name.trim()
        val deviceName = profile.deviceName.trim().ifBlank { "Unknown controller" }
        val signature = profile.deviceSignature.trim()
        val notes = profile.notes.replace("\r", "").trim()

        if (name.isEmpty()) errors += "A profile needs a name."
        if (name.length > MAX_NAME_LENGTH) errors += "The name is longer than $MAX_NAME_LENGTH characters."
        if (name.any { it.isISOControl() }) errors += "The name contains a control character."

        if (signature.isEmpty()) errors += "A profile needs a device signature."
        if (signature.length > MAX_SIGNATURE_LENGTH) errors += "The device signature is too long."
        if (signature.any { it.isISOControl() }) errors += "The device signature contains a control character."

        if (deviceName.length > MAX_DEVICE_NAME_LENGTH) {
            errors += "The device name is longer than $MAX_DEVICE_NAME_LENGTH characters."
        }
        if (deviceName.any { it.isISOControl() }) errors += "The device name contains a control character."

        if (!profile.deadzone.isFinite() || profile.deadzone < 0f || profile.deadzone > MAX_DEADZONE) {
            errors += "The dead zone must be between 0% and ${percent(MAX_DEADZONE)}."
        }
        if (!profile.exponent.isFinite() ||
            profile.exponent < ResponseCurveSettings.MIN_EXPONENT ||
            profile.exponent > ResponseCurveSettings.MAX_EXPONENT
        ) {
            errors += "The curve exponent must be between ${ResponseCurveSettings.MIN_EXPONENT} and " +
                "${ResponseCurveSettings.MAX_EXPONENT}."
        }
        if (!profile.driftOffsetThreshold.isFinite() ||
            profile.driftOffsetThreshold < 0f ||
            profile.driftOffsetThreshold > MAX_DRIFT_OFFSET
        ) {
            errors += "The drift offset threshold must be between 0 and ${percent(MAX_DRIFT_OFFSET)}."
        }
        if (!profile.driftJitterThreshold.isFinite() ||
            profile.driftJitterThreshold < 0f ||
            profile.driftJitterThreshold > MAX_DRIFT_JITTER
        ) {
            errors += "The drift jitter threshold must be between 0 and ${percent(MAX_DRIFT_JITTER)}."
        }
        if (notes.length > MAX_NOTES_LENGTH) errors += "Notes are longer than $MAX_NOTES_LENGTH characters."
        if (notes.any { it.isISOControl() && it != '\n' }) errors += "Notes contain a control character."

        if (profile.id < 0L) errors += "The profile id is negative."
        if (profile.createdAtMillis < 0L || profile.updatedAtMillis < 0L) {
            errors += "The profile timestamp is negative."
        }

        if (errors.isNotEmpty()) return ProfileValidation.Invalid(errors)

        return ProfileValidation.Valid(
            profile.copy(
                name = name,
                deviceSignature = signature,
                deviceName = deviceName,
                notes = notes,
                updatedAtMillis = maxOf(profile.createdAtMillis, profile.updatedAtMillis),
            ),
        )
    }

    fun isValid(profile: ControllerProfile): Boolean = validate(profile) is ProfileValidation.Valid

    /** A percentage a person can read, e.g. `8.0%` for a 0.08 dead zone. */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value * 100f)
}

/**
 * A capability fingerprint, not a name.
 *
 * The vendor and product ids separate hardware families; the sorted axis ids and the number of buttons a
 * device declares separate two pads that share a vendor id but expose different hardware. This is the key a
 * profile and a test result are filed under, so reconnecting the same controller on a different port still
 * finds its history, while a different controller with the same name does not.
 */
object ControllerSignature {

    fun of(
        vendorId: Int,
        productId: Int,
        axisIds: Collection<Int>,
        buttonCount: Int,
    ): String {
        val axes = axisIds.filter { it >= 0 }.distinct().sorted().joinToString(",")
        val material = "gc-controller-v1|$vendorId|$productId|$axes|${buttonCount.coerceAtLeast(0)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return "c1:" + digest.joinToString("") { String.format(Locale.US, "%02x", it) }.take(32)
    }
}

/** One row in the profile comparison. [differs] is computed, not stored, so a caller cannot disagree. */
data class ProfileDifference(
    val label: String,
    val left: String,
    val right: String,
) {
    val differs: Boolean get() = left != right
}

/**
 * Compares the settings that change how a stick behaves. Names, notes and timestamps are intentionally not
 * compared: two profiles can be named differently and still be the same curve, and a comparison table that
 * called that a difference would bury the one row that matters.
 */
object ControllerProfileComparator {

    fun compare(left: ControllerProfile, right: ControllerProfile): List<ProfileDifference> = listOf(
        ProfileDifference(
            label = "Dead zone",
            left = ControllerProfileValidator.percent(left.deadzone),
            right = ControllerProfileValidator.percent(right.deadzone),
        ),
        ProfileDifference(
            label = "Curve",
            left = left.curve.label,
            right = right.curve.label,
        ),
        ProfileDifference(
            label = "Exponent",
            left = format(left.exponent),
            right = format(right.exponent),
        ),
        ProfileDifference(
            label = "Drift offset",
            left = ControllerProfileValidator.percent(left.driftOffsetThreshold),
            right = ControllerProfileValidator.percent(right.driftOffsetThreshold),
        ),
        ProfileDifference(
            label = "Drift jitter",
            left = ControllerProfileValidator.percent(left.driftJitterThreshold),
            right = ControllerProfileValidator.percent(right.driftJitterThreshold),
        ),
    )

    private fun format(value: Float): String = String.format(Locale.US, "%.2f", value)
}
