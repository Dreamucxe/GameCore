package com.gamecore.core.controller

import org.json.JSONArray
import org.json.JSONObject

/**
 * The versioned envelope a controller profile is exported and imported in.
 *
 * The shape is deliberately small and independent of Room ids: a file written on one device cannot carry a
 * rowid that means something else on another. Import is total — a truncated file, a missing field, an enum
 * from a newer build or a number outside the range the model accepts costs that profile and is counted in
 * [Decoded.rejectedProfiles]; it never throws and never produces a partly-valid row with invented values.
 *
 * Unknown keys are ignored, which is what lets a later version add a field without making this build refuse
 * the whole file. The format and version are checked before anything else, because an unrelated JSON file
 * that happens to contain a `profiles` array must not be imported as controller settings.
 */
object ControllerProfileCodec {

    const val FORMAT = "gamecore.controller-profile"
    const val VERSION = 1

    /** A ceiling on entries walked, so a hand-crafted gigantic array cannot turn an import into a stall. */
    const val MAX_PROFILES = 64

    data class Decoded(
        val profiles: List<ControllerProfile> = emptyList(),
        val rejectedProfiles: Int = 0,
        val issues: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val isEmpty: Boolean get() = profiles.isEmpty() && rejectedProfiles == 0 && error == null
    }

    fun encode(profiles: List<ControllerProfile>): String {
        val array = JSONArray()
        for (profile in profiles.take(MAX_PROFILES)) {
            array.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("device_signature", profile.deviceSignature)
                    .put("device_name", profile.deviceName)
                    .put("deadzone", profile.deadzone.toDouble())
                    .put("curve", profile.curve.name)
                    .put("exponent", profile.exponent.toDouble())
                    .put("drift_offset", profile.driftOffsetThreshold.toDouble())
                    .put("drift_jitter", profile.driftJitterThreshold.toDouble())
                    .put("notes", profile.notes)
                    .put("created_at", profile.createdAtMillis)
                    .put("updated_at", profile.updatedAtMillis),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("profiles", array)
            .toString(2)
    }

    fun decode(text: String): Decoded {
        if (text.isBlank()) return Decoded(error = "That file is empty.")
        return try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) {
                return Decoded(error = "That file is not a GameCore controller profile export.")
            }
            if (root.optInt("version", -1) != VERSION) {
                return Decoded(
                    error = "That profile export was written by a different format version " +
                        "(${root.optInt("version", -1)}); this build reads version $VERSION.",
                )
            }
            val array = root.optJSONArray("profiles")
                ?: return Decoded(error = "The export contains no profiles list.")
            val profiles = ArrayList<ControllerProfile>(array.length().coerceAtMost(MAX_PROFILES))
            val issues = ArrayList<String>(4)
            var rejected = 0
            val walked = minOf(array.length(), MAX_PROFILES)
            for (index in 0 until walked) {
                val item = array.opt(index)
                if (item !is JSONObject) {
                    rejected++
                    addIssue(issues, "Profile ${index + 1} is not an object.")
                    continue
                }
                when (val outcome = decodeOne(item)) {
                    is ProfileValidation.Valid -> profiles += outcome.profile
                    is ProfileValidation.Invalid -> {
                        rejected++
                        addIssue(issues, "Profile ${index + 1}: ${outcome.errors.first()}")
                    }
                }
            }
            if (array.length() > MAX_PROFILES) {
                rejected += array.length() - MAX_PROFILES
                addIssue(issues, "Only the first $MAX_PROFILES profiles in the file were read.")
            }
            Decoded(profiles = profiles, rejectedProfiles = rejected, issues = issues)
        } catch (error: Throwable) {
            // The exception text is not shown: org.json can quote the input it choked on, and that input is
            // the user's file. The class name is already more than the screen needs.
            Decoded(error = "That file could not be read as a controller profile export.")
        }
    }

    private fun decodeOne(item: JSONObject): ProfileValidation {
        val curveName = item.optString("curve")
        val curve = ResponseCurveKind.entries.firstOrNull { it.name == curveName }
            ?: return ProfileValidation.Invalid(listOf("The curve '$curveName' is not one this build knows."))

        val deadzone = number(item, "deadzone")
            ?: return ProfileValidation.Invalid(listOf("The dead zone is missing or not a number."))
        val exponent = number(item, "exponent")
            ?: return ProfileValidation.Invalid(listOf("The exponent is missing or not a number."))
        val driftOffset = number(item, "drift_offset")
            ?: return ProfileValidation.Invalid(listOf("The drift offset threshold is missing or not a number."))
        val driftJitter = number(item, "drift_jitter")
            ?: return ProfileValidation.Invalid(listOf("The drift jitter threshold is missing or not a number."))

        val profile = ControllerProfile(
            id = 0L,
            name = item.optString("name"),
            deviceSignature = item.optString("device_signature"),
            deviceName = item.optString("device_name"),
            deadzone = deadzone,
            curve = curve,
            exponent = exponent,
            driftOffsetThreshold = driftOffset,
            driftJitterThreshold = driftJitter,
            notes = item.optString("notes"),
            createdAtMillis = item.optLong("created_at", 0L),
            updatedAtMillis = item.optLong("updated_at", 0L),
        )
        return ControllerProfileValidator.validate(profile)
    }

    /**
     * A finite number, or null. `optDouble` returns NaN when a key holds a non-numeric string, and NaN would
     * pass a naive `isFinite` check only by being forgotten; converting it to null here makes the missing
     * case and the hostile case the same answer.
     */
    private fun number(item: JSONObject, key: String): Float? {
        val value = item.optDouble(key, Double.NaN)
        if (!value.isFinite()) return null
        return value.toFloat().takeIf { it.isFinite() }
    }

    private fun addIssue(issues: MutableList<String>, issue: String) {
        if (issues.size < MAX_ISSUES) issues += issue
    }

    private const val MAX_ISSUES = 6
}
