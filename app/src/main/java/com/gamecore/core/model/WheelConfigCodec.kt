package com.gamecore.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wheel's storage and backup codec.
 *
 * One JSON document under one encrypted preference key, and one section in a backup file. A document
 * rather than a dozen scalar keys because the profile library is genuinely nested: N profiles, each with
 * the same fifteen fields, plus a map of per-game bindings. Scalar keys would need an encoding for the
 * list inside a key, which is this document with worse error handling.
 *
 * [decode] is total. It returns a normalised [WheelConfig] for null, an empty string, invalid JSON, a
 * future schema version, missing fields, wrong field types and out-of-range numbers. The caller never
 * has to catch, and a corrupt preference can never stop the overlay service from starting. Values that
 * did parse are kept; only the broken parts fall back, which is the same rule the rest of GameCore's
 * preference readers follow.
 */
object WheelConfigCodec {

    /** The only schema this build writes. An unknown newer version is read best-effort, never refused. */
    const val VERSION = 1

    private const val KEY_VERSION = "version"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "activeProfileId"
    private const val KEY_BUTTON = "button"
    private const val KEY_BINDINGS = "bindings"
    private const val KEY_AUTO_SWITCH = "autoSwitch"
    private const val KEY_RISK_ACKNOWLEDGED = "riskAcknowledged"
    private const val KEY_PRECISION_MULTIPLIER = "precisionMultiplier"
    private const val KEY_TURBO_MULTIPLIER = "turboMultiplier"

    // Profile keys ---------------------------------------------------------------------------------
    private const val P_ID = "id"
    private const val P_NAME = "name"
    private const val P_SENSITIVITY = "sensitivity"
    private const val P_X_SENSITIVITY = "xSensitivity"
    private const val P_Y_SENSITIVITY = "ySensitivity"
    private const val P_RADIUS = "radiusPx"
    private const val P_DEADZONE = "deadzonePx"
    private const val P_DIAMETER = "diameterDp"
    private const val P_OPACITY = "opacity"
    private const val P_SMOOTHING = "smoothing"
    private const val P_INVERT_X = "invertX"
    private const val P_INVERT_Y = "invertY"
    private const val P_CURVE = "curve"
    private const val P_STYLE = "style"
    private const val P_ACCENT = "accent"
    private const val P_LOCKED = "locked"
    private const val P_X_FRACTION = "xFraction"
    private const val P_Y_FRACTION = "yFraction"
    private const val P_SHOW_VECTOR = "showVector"
    private const val P_HAPTICS = "haptics"

    // Button keys ----------------------------------------------------------------------------------
    private const val B_VISIBLE = "visible"
    private const val B_X_FRACTION = "xFraction"
    private const val B_Y_FRACTION = "yFraction"
    private const val B_SIZE = "sizeDp"
    private const val B_OPACITY = "opacity"
    private const val B_STYLE = "style"
    private const val B_ICON = "icon"
    private const val B_ACCENT = "accent"
    private const val B_HAPTICS = "haptics"
    private const val B_SNAP_TO_EDGE = "snapToEdge"
    private const val B_IDLE_FADE = "idleFade"

    fun encode(config: WheelConfig): String {
        val safe = config.normalised()
        val profiles = JSONArray()
        safe.profiles.forEach { profiles.put(encodeProfile(it)) }
        val bindings = JSONObject()
        safe.bindings.toSortedMap().forEach { (packageName, profileId) ->
            bindings.put(packageName, profileId)
        }
        return JSONObject()
            .put(KEY_VERSION, VERSION)
            .put(KEY_PROFILES, profiles)
            .put(KEY_ACTIVE, safe.activeProfileId)
            .put(KEY_BUTTON, encodeButton(safe.button))
            .put(KEY_BINDINGS, bindings)
            .put(KEY_AUTO_SWITCH, safe.autoSwitch)
            .put(KEY_RISK_ACKNOWLEDGED, safe.riskAcknowledged)
            .put(KEY_PRECISION_MULTIPLIER, safe.precisionMultiplier.toDouble())
            .put(KEY_TURBO_MULTIPLIER, safe.turboMultiplier.toDouble())
            .toString()
    }

    /**
     * Decodes a stored document. Never throws.
     *
     * A version newer than this build is not refused: the fields this build knows are read and the ones it
     * does not are ignored. Refusing would make a downgrade lose the wheel entirely, and the wheel's
     * settings are all bounded numbers rather than a schema whose unknown fields could mislead the maths.
     */
    fun decode(raw: String?): WheelConfig {
        if (raw.isNullOrBlank()) return WheelConfig.DEFAULT
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return WheelConfig.DEFAULT
        val profilesJson = root.optJSONArray(KEY_PROFILES)
        val profiles = buildList {
            for (index in 0 until (profilesJson?.length() ?: 0)) {
                val item = profilesJson?.optJSONObject(index) ?: continue
                add(decodeProfile(item))
            }
        }
        val button = root.optJSONObject(KEY_BUTTON)?.let(::decodeButton) ?: WheelButtonConfig.DEFAULT
        val bindingsJson = root.optJSONObject(KEY_BINDINGS)
        val bindings = buildMap {
            bindingsJson?.keys()?.forEach { packageName ->
                bindingsJson.optString(packageName, "").takeIf { it.isNotBlank() }?.let { id ->
                    put(packageName, id)
                }
            }
        }
        val active = root.optString(KEY_ACTIVE, WheelProfile.DEFAULT_ID)
        return WheelConfig(
            profiles = profiles.ifEmpty { listOf(WheelProfile.DEFAULT) },
            activeProfileId = active,
            button = button,
            bindings = bindings,
            autoSwitch = root.optBoolean(KEY_AUTO_SWITCH, false),
            riskAcknowledged = root.optBoolean(KEY_RISK_ACKNOWLEDGED, false),
            precisionMultiplier = root.optDouble(
                KEY_PRECISION_MULTIPLIER,
                WheelConfig.DEFAULT_PRECISION_MULTIPLIER.toDouble(),
            ).toFloat(),
            turboMultiplier = root.optDouble(
                KEY_TURBO_MULTIPLIER,
                WheelConfig.DEFAULT_TURBO_MULTIPLIER.toDouble(),
            ).toFloat(),
        ).normalised()
    }

    private fun encodeProfile(profile: WheelProfile): JSONObject = JSONObject()
        .put(P_ID, profile.id)
        .put(P_NAME, profile.name)
        .put(P_SENSITIVITY, profile.sensitivity.toDouble())
        .put(P_X_SENSITIVITY, profile.xSensitivity.toDouble())
        .put(P_Y_SENSITIVITY, profile.ySensitivity.toDouble())
        .put(P_RADIUS, profile.radiusPx)
        .put(P_DEADZONE, profile.deadzonePx)
        .put(P_DIAMETER, profile.diameterDp)
        .put(P_OPACITY, profile.opacity.toDouble())
        .put(P_SMOOTHING, profile.smoothing.toDouble())
        .put(P_INVERT_X, profile.invertX)
        .put(P_INVERT_Y, profile.invertY)
        .put(P_CURVE, profile.curve.name)
        .put(P_STYLE, profile.style.name)
        .put(P_ACCENT, profile.accent.name)
        .put(P_LOCKED, profile.locked)
        .put(P_X_FRACTION, profile.xFraction.toDouble())
        .put(P_Y_FRACTION, profile.yFraction.toDouble())
        .put(P_SHOW_VECTOR, profile.showVector)
        .put(P_HAPTICS, profile.haptics)

    private fun decodeProfile(json: JSONObject): WheelProfile {
        val defaults = WheelProfile.DEFAULT
        return WheelProfile(
            id = json.optString(P_ID, defaults.id),
            name = json.optString(P_NAME, defaults.name),
            sensitivity = json.optDouble(P_SENSITIVITY, defaults.sensitivity.toDouble()).toFloat(),
            xSensitivity = json.optDouble(P_X_SENSITIVITY, defaults.xSensitivity.toDouble()).toFloat(),
            ySensitivity = json.optDouble(P_Y_SENSITIVITY, defaults.ySensitivity.toDouble()).toFloat(),
            radiusPx = json.optInt(P_RADIUS, defaults.radiusPx),
            deadzonePx = json.optInt(P_DEADZONE, defaults.deadzonePx),
            diameterDp = json.optInt(P_DIAMETER, defaults.diameterDp),
            opacity = json.optDouble(P_OPACITY, defaults.opacity.toDouble()).toFloat(),
            smoothing = json.optDouble(P_SMOOTHING, defaults.smoothing.toDouble()).toFloat(),
            invertX = json.optBoolean(P_INVERT_X, defaults.invertX),
            invertY = json.optBoolean(P_INVERT_Y, defaults.invertY),
            curve = enumOrDefault(json.optString(P_CURVE), WheelResponseCurve.LINEAR),
            style = enumOrDefault(json.optString(P_STYLE), WheelVisualStyle.HALO),
            accent = enumOrDefault(json.optString(P_ACCENT), WheelAccent.CYAN),
            locked = json.optBoolean(P_LOCKED, defaults.locked),
            xFraction = json.optDouble(P_X_FRACTION, defaults.xFraction.toDouble()).toFloat(),
            yFraction = json.optDouble(P_Y_FRACTION, defaults.yFraction.toDouble()).toFloat(),
            showVector = json.optBoolean(P_SHOW_VECTOR, defaults.showVector),
            haptics = json.optBoolean(P_HAPTICS, defaults.haptics),
        ).normalised()
    }

    private fun encodeButton(button: WheelButtonConfig): JSONObject = JSONObject()
        .put(B_VISIBLE, button.visible)
        .put(B_X_FRACTION, button.xFraction.toDouble())
        .put(B_Y_FRACTION, button.yFraction.toDouble())
        .put(B_SIZE, button.sizeDp)
        .put(B_OPACITY, button.opacity.toDouble())
        .put(B_STYLE, button.style.name)
        .put(B_ICON, button.icon.name)
        .put(B_ACCENT, button.accent.name)
        .put(B_HAPTICS, button.haptics)
        .put(B_SNAP_TO_EDGE, button.snapToEdge)
        .put(B_IDLE_FADE, button.idleFade)

    private fun decodeButton(json: JSONObject): WheelButtonConfig {
        val defaults = WheelButtonConfig.DEFAULT
        return WheelButtonConfig(
            visible = json.optBoolean(B_VISIBLE, defaults.visible),
            xFraction = json.optDouble(B_X_FRACTION, defaults.xFraction.toDouble()).toFloat(),
            yFraction = json.optDouble(B_Y_FRACTION, defaults.yFraction.toDouble()).toFloat(),
            sizeDp = json.optInt(B_SIZE, defaults.sizeDp),
            opacity = json.optDouble(B_OPACITY, defaults.opacity.toDouble()).toFloat(),
            style = enumOrDefault(json.optString(B_STYLE), WheelButtonStyle.ORB),
            icon = enumOrDefault(json.optString(B_ICON), WheelButtonIcon.WHEEL),
            accent = enumOrDefault(json.optString(B_ACCENT), WheelAccent.CYAN),
            haptics = json.optBoolean(B_HAPTICS, defaults.haptics),
            snapToEdge = json.optBoolean(B_SNAP_TO_EDGE, defaults.snapToEdge),
            idleFade = json.optBoolean(B_IDLE_FADE, defaults.idleFade),
        ).normalised()
    }

    /** Enum by stored name, with the caller's default for an absent, renamed or unknown value. */
    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
