package com.gamecore.aimlab.engine

import com.gamecore.core.common.TextSanitizer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Serializes and validates Aim Lab configuration for export/import.
 *
 * Weapons, sensitivity profiles and control layouts can be exported to a JSON document and imported back
 * (§35). This codec is where the round-trip and — more importantly — the *validation* live. Import is the
 * one place untrusted data enters Aim Lab, so [decode] is strict: it rejects malformed JSON, a wrong
 * schema version, an oversized document, out-of-range values, and unknown or duplicate ids, and returns a
 * typed [ImportResult] the UI can explain rather than throwing (§B2.10, §C).
 *
 * `org.json` ships inside `android.jar`, so nothing extra reaches the APK. The copy AGP puts on the *unit
 * test* classpath is a stub that returns defaults instead of parsing, which would make every rejection
 * test below pass without a character being read — so the test source set declares a real
 * `org.json:json` implementation ahead of it. No android.* import appears here either way.
 *
 * The format is one object: `{ "schema": 1, "kind": "aimlab-config", "weapons": [...],
 * "sensitivities": [...], "layouts": [...] }`. This codec range-checks the numeric fields *and* sanitises
 * the names itself rather than leaving that to whoever stores the bundle: [decode] hands back a
 * [ConfigBundle] that an import screen will preview before anything is persisted, so the strings in it have
 * to be safe to draw on their own. `TextSanitizer` is pure Kotlin with no imports of its own, so using it
 * here costs nothing in testability and keeps this file free of `android.*` (§5 of the isolation audit).
 */
object ConfigCodec {

    const val SCHEMA_VERSION = 1
    const val KIND = "aimlab-config"

    /** A hard ceiling on import size, so a pasted multi-megabyte file cannot be walked into OOM. */
    const val MAX_IMPORT_BYTES = 512 * 1024

    /** Caps on collection sizes, so a document claiming a million weapons is rejected early. */
    const val MAX_ITEMS = 500

    /**
     * The length every imported name is sanitised down to.
     *
     * [ControlLayout] is the only one of the three domain types that states a name limit of its own, so it
     * is the source here rather than `TextSanitizer.MAX_NAME_LENGTH`. The weapon and sensitivity editors
     * and the storage mappers all settled on the same number, which is the point: a name that arrives by
     * import ends up indistinguishable from one typed into an editor, instead of one that survives the
     * preview only to be cut again on its way into the database.
     */
    const val MAX_NAME_LENGTH = ControlLayout.MAX_NAME_LENGTH

    /** A bundle of exportable configuration. */
    data class ConfigBundle(
        val weapons: List<Weapon> = emptyList(),
        val sensitivities: List<SensitivityProfile> = emptyList(),
        val layouts: List<ControlLayout> = emptyList(),
    )

    /** The outcome of an import attempt. */
    sealed interface ImportResult {
        data class Ok(val bundle: ConfigBundle) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    // ------------------------------------------------------------------------------ encode

    /** Serializes a bundle to a compact JSON string. Total; never throws on valid domain objects. */
    fun encode(bundle: ConfigBundle): String {
        val root = JSONObject()
        root.put("schema", SCHEMA_VERSION)
        root.put("kind", KIND)
        root.put("weapons", JSONArray().apply { bundle.weapons.forEach { put(encodeWeapon(it)) } })
        root.put("sensitivities", JSONArray().apply { bundle.sensitivities.forEach { put(encodeSensitivity(it)) } })
        root.put("layouts", JSONArray().apply { bundle.layouts.forEach { put(encodeLayout(it)) } })
        return root.toString()
    }

    private fun encodeWeapon(w: Weapon): JSONObject = JSONObject().apply {
        put("id", w.id)
        put("name", w.name)
        put("category", w.category.name)
        put("fireRateRpm", w.fireRateRpm)
        put("magazineSize", w.magazineSize)
        put("reloadMillis", w.reloadMillis)
        put("adsTimeMillis", w.adsTimeMillis)
        put("movementPenalty", w.movementPenalty.toDouble())
        put("spread", w.spread.toDouble())
        put("recoilVertical", w.recoil.verticalPerShot.toDouble())
        put("recoilHorizontal", w.recoil.horizontalPerShot.toDouble())
        put("recoilRandomness", w.recoil.randomness.toDouble())
        put("recoilRecovery", w.recoil.recoveryPerSecond.toDouble())
        put("fireMode", w.fireMode.name)
        put("burstCount", w.burstCount)
    }

    private fun encodeSensitivity(s: SensitivityProfile): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("preset", s.preset.name)
        put("camera", s.cameraSensitivity.toDouble())
        put("ads", s.adsMultiplier.toDouble())
        put("gyro", s.gyroSensitivity.toDouble())
        put("gyroAds", s.gyroAdsMultiplier.toDouble())
        put("horizontal", s.horizontalScale.toDouble())
        put("vertical", s.verticalScale.toDouble())
        put("deadzone", s.deadzonePercent)
        put("smoothing", s.smoothingPercent)
        put("exponent", s.responseExponent.toDouble())
        put("invertX", s.invertX)
        put("invertY", s.invertY)
    }

    private fun encodeLayout(l: ControlLayout): JSONObject = JSONObject().apply {
        put("id", l.id)
        put("name", l.name)
        // "controls" is the portrait set, kept under that key so an export this build makes still imports
        // into an older build that only reads portrait. Landscape goes in its own optional array (§4).
        put("controls", encodeControls(l.controls))
        if (l.landscapeControls.isNotEmpty()) {
            put("landscapeControls", encodeControls(l.landscapeControls))
        }
    }

    private fun encodeControls(controls: List<ControlWidget>): JSONArray = JSONArray().apply {
        controls.forEach { c ->
            put(JSONObject().apply {
                put("role", c.role.name)
                put("x", c.xFraction.toDouble())
                put("y", c.yFraction.toDouble())
                put("w", c.widthFraction.toDouble())
                put("h", c.heightFraction.toDouble())
                put("opacity", c.opacityPercent)
                put("shape", c.shape.name)
                put("enabled", c.enabled)
            })
        }
    }

    // ------------------------------------------------------------------------------ decode

    /**
     * Parses and validates an import document.
     *
     * Rejection order: oversized → malformed JSON → wrong kind/schema → per-item validation. Every parsed
     * object is normalised (clamped) so even an accepted value can never be out of range, and duplicate ids
     * within a kind are rejected rather than silently overwriting each other.
     *
     * Names get the treatment every other piece of outside text in GameCore gets: [TextSanitizer.sanitizeName]
     * at [MAX_NAME_LENGTH], which drops bidi overrides, control and other format characters, flattens
     * newlines to spaces, cuts a stack of combining marks down to what a line box holds, and truncates
     * last. An entry whose name sanitises away to nothing — one that was only control characters, say — is
     * rejected exactly like an entry that arrived with an empty name, taking the file with it. Substituting
     * a fallback would make this the one place decode invents data the file did not contain, and it would
     * dress a corrupt file up as a plausible one; rejecting keeps the existing all-or-nothing rule that one
     * bad entry fails the import.
     *
     * **Ids in the file are read but never kept.** Every decoded object comes back with `id = 0L`, so it is
     * an insert wherever it is stored. The ids are still parsed, because two entries sharing one is
     * evidence of a corrupt file worth rejecting — but carrying them through would hand an imported
     * document the primary keys of rows already in the database, and the Aim Lab DAOs insert with
     * `OnConflictStrategy.REPLACE`. A file naming id 3 would then silently overwrite whatever the user's
     * weapon 3 was, built-in or not, instead of adding a weapon. An import adds; it does not reach into
     * storage and pick which existing rows to destroy.
     */
    fun decode(raw: String): ImportResult {
        // Char count first: UTF-8 never encodes a string in fewer bytes than it has UTF-16 units, so a
        // string longer than the cap is over it for certain and can be rejected without the second
        // multi-megabyte copy `toByteArray` would allocate to find that out.
        if (raw.length > MAX_IMPORT_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) {
            return ImportResult.Rejected("The file is too large to be an Aim Lab configuration.")
        }
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return ImportResult.Rejected("That is not a valid configuration file.")
        } catch (deeplyNested: StackOverflowError) {
            // `org.json` parses nesting recursively, so a document of ten thousand open brackets exhausts
            // the stack rather than throwing JSONException. Caught here because the alternative is the
            // process dying on a file the user merely tried to open, and because it is a rejection like
            // any other: the file is not a configuration this build can read. Nothing is left half-built —
            // the parse produced no object, so there is no state to unwind.
            return ImportResult.Rejected("That is not a valid configuration file.")
        }
        if (root.optString("kind") != KIND) {
            return ImportResult.Rejected("This file is not an Aim Lab configuration.")
        }
        val schema = root.optInt("schema", -1)
        if (schema != SCHEMA_VERSION) {
            return ImportResult.Rejected("This configuration was made by a different version of GameCore.")
        }

        val weapons = try {
            parseWeapons(root.optJSONArray("weapons"))
        } catch (e: ValidationException) {
            return ImportResult.Rejected(e.message ?: "Invalid weapon data.")
        }
        val sensitivities = try {
            parseSensitivities(root.optJSONArray("sensitivities"))
        } catch (e: ValidationException) {
            return ImportResult.Rejected(e.message ?: "Invalid sensitivity data.")
        }
        val layouts = try {
            parseLayouts(root.optJSONArray("layouts"))
        } catch (e: ValidationException) {
            return ImportResult.Rejected(e.message ?: "Invalid layout data.")
        }

        return ImportResult.Ok(ConfigBundle(weapons, sensitivities, layouts))
    }

    private class ValidationException(message: String) : Exception(message)

    /**
     * Reads a float that is guaranteed to be a real number, or fails the import.
     *
     * `optDouble` will hand back `NaN` or an infinity — `org.json` accepts the bare `NaN` token, and any
     * literal past a double's range parses to an infinity — and neither survives the clamp that is
     * supposed to make an accepted value safe: `coerceIn` compares, every comparison against `NaN` is
     * false, so `NaN.coerceIn(0f, 1f)` is `NaN`. A `NaN` spread or opacity then propagates through the
     * engine's arithmetic into a target that can never be hit or a control that cannot be drawn, from a
     * file that passed validation. Rejecting at the boundary is the fix; [Float.clampFinite] behind it is
     * the belt to this file's braces, for objects built anywhere else.
     */
    private fun JSONObject.finite(key: String, fallback: Double): Float {
        val value = optDouble(key, fallback).toFloat()
        if (value.isNaN() || value.isInfinite()) {
            throw ValidationException("A value in the file is not a number.")
        }
        return value
    }

    private fun parseWeapons(array: JSONArray?): List<Weapon> {
        if (array == null) return emptyList()
        if (array.length() > MAX_ITEMS) throw ValidationException("Too many weapons in the file.")
        val seen = HashSet<Long>()
        val out = ArrayList<Weapon>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: throw ValidationException("A weapon entry is malformed.")
            val id = o.optLong("id", 0L)
            if (id != 0L && !seen.add(id)) throw ValidationException("Two weapons share an id.")
            val name = TextSanitizer.sanitizeName(o.optString("name", ""), MAX_NAME_LENGTH)
            if (name.isEmpty()) throw ValidationException("A weapon has no name.")
            out += Weapon(
                id = 0L,
                name = name,
                category = WeaponCategory.fromName(o.optString("category")),
                fireRateRpm = o.optInt("fireRateRpm", 600),
                magazineSize = o.optInt("magazineSize", 30),
                reloadMillis = o.optLong("reloadMillis", 2_000L),
                adsTimeMillis = o.optLong("adsTimeMillis", 250L),
                movementPenalty = o.finite("movementPenalty", 0.4),
                spread = o.finite("spread", 0.01),
                recoil = RecoilSpec(
                    verticalPerShot = o.finite("recoilVertical", 0.02),
                    horizontalPerShot = o.finite("recoilHorizontal", 0.008),
                    randomness = o.finite("recoilRandomness", 0.3),
                    recoveryPerSecond = o.finite("recoilRecovery", 2.5),
                ),
                // Fire mode is optional: an export from before fire modes has no key, and FireMode.fromName
                // defaults it to AUTO — what those weapons already did. normalised() clamps burstCount.
                fireMode = FireMode.fromName(o.optString("fireMode")),
                burstCount = o.optInt("burstCount", 3),
            ).normalised()
        }
        return out
    }

    private fun parseSensitivities(array: JSONArray?): List<SensitivityProfile> {
        if (array == null) return emptyList()
        if (array.length() > MAX_ITEMS) throw ValidationException("Too many sensitivity profiles in the file.")
        val seen = HashSet<Long>()
        val out = ArrayList<SensitivityProfile>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: throw ValidationException("A sensitivity entry is malformed.")
            val id = o.optLong("id", 0L)
            if (id != 0L && !seen.add(id)) throw ValidationException("Two profiles share an id.")
            val name = TextSanitizer.sanitizeName(o.optString("name", ""), MAX_NAME_LENGTH)
            if (name.isEmpty()) throw ValidationException("A sensitivity profile has no name.")
            out += SensitivityProfile(
                id = 0L,
                name = name,
                preset = SensitivityPreset.fromName(o.optString("preset")),
                cameraSensitivity = o.finite("camera", 1.0),
                adsMultiplier = o.finite("ads", 1.0),
                gyroSensitivity = o.finite("gyro", 1.0),
                gyroAdsMultiplier = o.finite("gyroAds", 1.0),
                horizontalScale = o.finite("horizontal", 1.0),
                verticalScale = o.finite("vertical", 1.0),
                deadzonePercent = o.optInt("deadzone", 0),
                smoothingPercent = o.optInt("smoothing", 0),
                responseExponent = o.finite("exponent", 1.0),
                invertX = o.optBoolean("invertX", false),
                invertY = o.optBoolean("invertY", false),
            ).normalised()
        }
        return out
    }

    private fun parseLayouts(array: JSONArray?): List<ControlLayout> {
        if (array == null) return emptyList()
        if (array.length() > MAX_ITEMS) throw ValidationException("Too many layouts in the file.")
        val seen = HashSet<Long>()
        val out = ArrayList<ControlLayout>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: throw ValidationException("A layout entry is malformed.")
            val id = o.optLong("id", 0L)
            if (id != 0L && !seen.add(id)) throw ValidationException("Two layouts share an id.")
            val name = TextSanitizer.sanitizeName(o.optString("name", ""), MAX_NAME_LENGTH)
            if (name.isEmpty()) throw ValidationException("A layout has no name.")
            val controls = parseControls(o.optJSONArray("controls"))
            // Landscape is optional: an export from a build before landscape support has no such key, and
            // its layout imports with an empty landscape set — the editor/overlay fall back to a preset.
            val landscape = parseControls(o.optJSONArray("landscapeControls"))
            out += ControlLayout(id = 0L, name = name, controls = controls, landscapeControls = landscape)
        }
        return out
    }

    /** Parses and validates one control array (used for both the portrait and landscape sets). */
    private fun parseControls(controlsArray: JSONArray?): List<ControlWidget> {
        val controls = ArrayList<ControlWidget>()
        if (controlsArray == null) return controls
        if (controlsArray.length() > ControlRole.entries.size) {
            throw ValidationException("A layout has more controls than exist.")
        }
        val roles = HashSet<ControlRole>()
        for (j in 0 until controlsArray.length()) {
            val c = controlsArray.optJSONObject(j)
                ?: throw ValidationException("A control entry is malformed.")
            val role = ControlRole.fromName(c.optString("role"))
                ?: throw ValidationException("A control has an unknown role.")
            if (!roles.add(role)) throw ValidationException("A layout repeats a control.")
            controls += ControlWidget(
                role = role,
                xFraction = c.finite("x", 0.5),
                yFraction = c.finite("y", 0.5),
                widthFraction = c.finite("w", 0.14),
                heightFraction = c.finite("h", 0.14),
                opacityPercent = c.optInt("opacity", 70),
                shape = ControlShape.fromName(c.optString("shape")),
                enabled = c.optBoolean("enabled", true),
            ).normalised()
        }
        return controls
    }
}
