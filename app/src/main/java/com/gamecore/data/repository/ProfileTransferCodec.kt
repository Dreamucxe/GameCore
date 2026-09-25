package com.gamecore.data.repository

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.ColorCorrection
import com.gamecore.core.model.ColorPreset
import com.gamecore.core.model.ColorVisionFilter
import com.gamecore.core.model.CpuAffinityPreset
import com.gamecore.core.model.CrosshairDesign
import com.gamecore.core.model.CrosshairPreset
import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.GammaMode
import com.gamecore.core.model.HudLayout
import com.gamecore.core.model.HudStat
import com.gamecore.core.model.HudWidget
import com.gamecore.core.model.PerformanceMode
import com.gamecore.core.model.ScreenOrientationLock
import com.gamecore.core.model.ThermalClass
import org.json.JSONArray
import org.json.JSONObject

/**
 * The one place a set of [GameProfile]s becomes the shareable envelope and comes back from it (§5).
 *
 * Pure Kotlin: `org.json` (which ships inside `android.jar`) and the `core.model` classes, no `android.*`,
 * so the round trip, the clamping and the hostile-input handling are all driven directly by a unit test on
 * the JVM — the same discipline `MacroCodec` and the Aim Lab `ConfigCodec` follow.
 *
 * **The envelope.** `{ "format": "gamecore.profile", "version": 1, "profiles": [ ... ], "presets": { ... } }`.
 * [encodeBody] returns the unwrapped `{ "profiles": [...], "presets": {...} }` object — that shape is the
 * JSON-level contract shared with the #20 backup writer, which wraps it in its own envelope rather than this
 * one.
 *
 * **The preset-id-locality trap (§2).** A [GameProfile] points at its HUD layout, crosshair and colour
 * preset by **local `Long` id**, which means nothing on another device. So every referenced preset
 * *definition* is embedded in `presets` keyed by the id the profile carries, and the profile keeps that same
 * id in the file. [decodeBody] returns those definitions in [Decoded] alongside the profiles; recreating or
 * matching them on the target device and remapping the ids to new local ones is the importer's job, because
 * that half needs the repositories. Nothing here ever writes a profile carrying an id from another device.
 *
 * **[decodeBody] / [decodeEnvelope] are total.** A blank string, a truncated write, an object where an array
 * was expected, a package name that is not one, a number out of range, an enum name a newer build wrote —
 * every one of them costs the offending field or the offending record and never throws. Hard validation is
 * the point: [TextSanitizer] guards the package and label, every numeric is clamped to the range the model
 * enforces, an unrecognised enum degrades to null (or the field's default), and a record that cannot be made
 * valid is dropped and counted rather than repaired into a lie.
 *
 * **Not transferred: crosshair images.** A [CrosshairPreset.imagePath] is a device-local path to a PNG this
 * process re-encoded; the bytes are not carried, so a custom-image crosshair crosses as its geometry with no
 * image. Documented here because it is the one field that cannot round-trip.
 */
object ProfileTransferCodec {

    const val FORMAT = "gamecore.profile"
    const val VERSION = 1

    /** A ceiling on how many array entries a decode will walk, so an injected huge array cannot be. */
    private const val SCAN_LIMIT = 512

    /** Plausible panel refresh range; a value outside it is dropped to null (verified against the panel anyway). */
    private val REFRESH_RANGE = 1f..480f
    private const val MAX_TIMEOUT_MILLIS = 86_400_000L

    /**
     * The preset definitions referenced by a set of profiles, gathered from the repositories by the Android
     * layer before [encodeBody] / [encodeEnvelope]. Only referenced presets belong here — an export carries
     * what its profiles point at and nothing else.
     */
    data class PresetBundle(
        val hud: List<HudLayout> = emptyList(),
        val crosshair: List<CrosshairPreset> = emptyList(),
        val color: List<ColorPreset> = emptyList(),
    )

    /**
     * The total-decode result. [profiles] still carry the preset ids **as they appeared in the file**; the
     * importer looks each one up in [hud] / [crosshair] / [color] (keyed by that same exported id) to
     * recreate-or-match it and remap. [rejectedProfiles] is how many array entries could not be made valid.
     */
    data class Decoded(
        val profiles: List<GameProfile> = emptyList(),
        val hud: Map<Long, HudLayout> = emptyMap(),
        val crosshair: Map<Long, CrosshairPreset> = emptyMap(),
        val color: Map<Long, ColorPreset> = emptyMap(),
        val rejectedProfiles: Int = 0,
    )

    // ------------------------------------------------------------------------------------------ encode

    /** The full shareable file: the format/version envelope around [encodeBody]. */
    fun encodeEnvelope(profiles: List<GameProfile>, bundle: PresetBundle): String =
        JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("profiles", profilesToJson(profiles))
            put("presets", presetsToJson(bundle))
        }.toString()

    /**
     * The unwrapped `{ "profiles": [...], "presets": {...} }` object — the shape the #20 backup writer reuses.
     */
    fun encodeBody(profiles: List<GameProfile>, bundle: PresetBundle): JSONObject =
        JSONObject().apply {
            put("profiles", profilesToJson(profiles))
            put("presets", presetsToJson(bundle))
        }

    private fun profilesToJson(profiles: List<GameProfile>): JSONArray =
        JSONArray().apply { profiles.forEach { put(profileToJson(it)) } }

    private fun profileToJson(p: GameProfile): JSONObject = JSONObject().apply {
        put("packageName", p.packageName)
        put("label", p.label)
        put("isEnabled", p.isEnabled)
        putOpt("targetRefreshRate", p.targetRefreshRate)
        putOpt("brightnessPercent", p.brightnessPercent)
        putOpt("rotationLock", p.rotationLock?.name)
        putOpt("screenTimeoutMillis", p.screenTimeoutMillis)
        putOpt("mediaVolumePercent", p.mediaVolumePercent)
        put("enableDoNotDisturb", p.enableDoNotDisturb)
        put("showFloatingButton", p.showFloatingButton)
        put("showPerformancePill", p.showPerformancePill)
        put("showCrosshair", p.showCrosshair)
        putOpt("hudLayoutId", p.hudLayoutId)
        putOpt("crosshairPresetId", p.crosshairPresetId)
        putOpt("colorPresetId", p.colorPresetId)
        putOpt("displaySize", p.displaySize?.argument)
        put("performanceMode", p.performanceMode.name)
        put("useShizukuOptimizations", p.useShizukuOptimizations)
        put("trackSession", p.trackSession)
        put("freeRamOnLaunch", p.freeRamOnLaunch)
        putOpt("cpuAffinity", p.cpuAffinity?.name)
        put("thermalDownshiftEnabled", p.thermalDownshiftEnabled)
        putOpt("thermalLimitDeciCelsius", p.thermalLimitDeciCelsius)
        putOpt("thermalStatusFloor", p.thermalStatusFloor?.name)
        putOpt("thermalFloorRateHz", p.thermalFloorRateHz)
        putOpt("thermalHysteresisDeciCelsius", p.thermalHysteresisDeciCelsius)
        putOpt("thermalSustainHotMillis", p.thermalSustainHotMillis)
        putOpt("thermalSustainCoolMillis", p.thermalSustainCoolMillis)
        putOpt("thermalMinIntervalMillis", p.thermalMinIntervalMillis)
        put("networkCheckEnabled", p.networkCheckEnabled)
        put("networkPreLaunchWarn", p.networkPreLaunchWarn)
        put("networkAlertsEnabled", p.networkAlertsEnabled)
        put("fullPerformanceEnabled", p.fullPerformanceEnabled)
    }

    private fun presetsToJson(bundle: PresetBundle): JSONObject = JSONObject().apply {
        put("hud", JSONArray().apply { bundle.hud.forEach { put(hudToJson(it)) } })
        put("crosshair", JSONArray().apply { bundle.crosshair.forEach { put(crosshairToJson(it)) } })
        put("color", JSONArray().apply { bundle.color.forEach { put(colorToJson(it)) } })
    }

    private fun hudToJson(layout: HudLayout): JSONObject = JSONObject().apply {
        put("id", layout.id)
        put("name", layout.name)
        put("widgets", JSONArray().apply { layout.widgets.forEach { put(widgetToJson(it)) } })
    }

    private fun widgetToJson(w: HudWidget): JSONObject = JSONObject().apply {
        put("id", w.id)
        put("stat", w.stat.name)
        put("xFraction", w.xFraction.toDouble())
        put("yFraction", w.yFraction.toDouble())
        put("textSizeSp", w.textSizeSp)
        put("opacityPercent", w.opacityPercent)
        put("showLabel", w.showLabel)
        put("showBackground", w.showBackground)
        put("colorArgb", w.colorArgb)
    }

    private fun crosshairToJson(c: CrosshairPreset): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("design", c.design.name)
        put("sizeDp", c.sizeDp)
        put("thicknessDp", c.thicknessDp)
        put("centreGapDp", c.centreGapDp)
        put("opacityPercent", c.opacityPercent)
        put("rotationDegrees", c.rotationDegrees)
        put("colorArgb", c.colorArgb)
        put("showDot", c.showDot)
        put("showOutline", c.showOutline)
        put("xFraction", c.xFraction.toDouble())
        put("yFraction", c.yFraction.toDouble())
        // imagePath is deliberately not written — see the class KDoc.
    }

    private fun colorToJson(preset: ColorPreset): JSONObject = JSONObject().apply {
        put("id", preset.id)
        put("name", preset.name)
        put("correction", correctionToJson(preset.correction))
    }

    private fun correctionToJson(c: ColorCorrection): JSONObject = JSONObject().apply {
        put("redGain", c.redGain)
        put("greenGain", c.greenGain)
        put("blueGain", c.blueGain)
        put("gammaMode", c.gammaMode.name)
        put("gamma", c.gamma)
        put("redGamma", c.redGamma)
        put("greenGamma", c.greenGamma)
        put("blueGamma", c.blueGamma)
        put("saturation", c.saturation)
        put("contrast", c.contrast)
        put("hueDegrees", c.hueDegrees)
        put("brightnessOffset", c.brightnessOffset)
        put("visionFilter", c.visionFilter.name)
        put("invertColors", c.invertColors)
    }

    // ------------------------------------------------------------------------------------------ decode

    /** Parses the full envelope string. Never throws — see the class note. */
    fun decodeEnvelope(json: String?): Decoded {
        if (json.isNullOrBlank()) return Decoded()
        val obj = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return Decoded()
        }
        return decodeBody(obj)
    }

    /**
     * Parses the unwrapped `{ "profiles": [...], "presets": {...} }` body — the reusable #20 contract's read
     * half. Ignores any surrounding `format` / `version` keys, so passing the whole envelope object is fine.
     */
    fun decodeBody(obj: JSONObject?): Decoded {
        if (obj == null) return Decoded()
        val presets = obj.optJSONObject("presets")
        val hud = decodeHudMap(presets?.optJSONArray("hud"))
        val crosshair = decodeCrosshairMap(presets?.optJSONArray("crosshair"))
        val color = decodeColorMap(presets?.optJSONArray("color"))

        val array = obj.optJSONArray("profiles") ?: JSONArray()
        val scan = minOf(array.length(), SCAN_LIMIT)
        val profiles = ArrayList<GameProfile>(scan)
        var rejected = 0
        for (i in 0 until scan) {
            val entry = array.optJSONObject(i)
            val profile = entry?.let { profileFromJson(it) }
            if (profile != null) profiles += profile else rejected++
        }
        return Decoded(profiles, hud, crosshair, color, rejected)
    }

    /** A single profile, or null when its package name is not a package name — the one unrecoverable field. */
    private fun profileFromJson(o: JSONObject): GameProfile? {
        val packageName = TextSanitizer.validatePackageName(o.optString("packageName", "")) ?: return null
        val label = TextSanitizer.sanitizeName(o.optString("label", "")).ifBlank { packageName }
        return GameProfile(
            packageName = packageName,
            label = label,
            isEnabled = o.optBoolean("isEnabled", true),
            targetRefreshRate = floatOrNull(o, "targetRefreshRate")?.takeIf { it in REFRESH_RANGE },
            brightnessPercent = intOrNull(o, "brightnessPercent")?.coerceIn(0, 100),
            rotationLock = enumOrNull<ScreenOrientationLock>(o.optStringOrNull("rotationLock")),
            screenTimeoutMillis = longOrNull(o, "screenTimeoutMillis")
                ?.takeIf { it > 0L }?.coerceAtMost(MAX_TIMEOUT_MILLIS),
            mediaVolumePercent = intOrNull(o, "mediaVolumePercent")?.coerceIn(0, 100),
            enableDoNotDisturb = o.optBoolean("enableDoNotDisturb", false),
            showFloatingButton = o.optBoolean("showFloatingButton", true),
            showPerformancePill = o.optBoolean("showPerformancePill", false),
            showCrosshair = o.optBoolean("showCrosshair", false),
            hudLayoutId = idOrNull(o, "hudLayoutId"),
            crosshairPresetId = idOrNull(o, "crosshairPresetId"),
            colorPresetId = idOrNull(o, "colorPresetId"),
            displaySize = o.optStringOrNull("displaySize")?.let { DisplaySize.parse(it) },
            performanceMode = enumOrNull<PerformanceMode>(o.optStringOrNull("performanceMode"))
                ?: PerformanceMode.BALANCED,
            useShizukuOptimizations = o.optBoolean("useShizukuOptimizations", false),
            trackSession = o.optBoolean("trackSession", true),
            freeRamOnLaunch = o.optBoolean("freeRamOnLaunch", false),
            cpuAffinity = enumOrNull<CpuAffinityPreset>(o.optStringOrNull("cpuAffinity")),
            thermalDownshiftEnabled = o.optBoolean("thermalDownshiftEnabled", false),
            thermalLimitDeciCelsius = intOrNull(o, "thermalLimitDeciCelsius")?.coerceIn(0, 1_500),
            thermalStatusFloor = enumOrNull<ThermalClass>(o.optStringOrNull("thermalStatusFloor")),
            thermalFloorRateHz = floatOrNull(o, "thermalFloorRateHz")?.takeIf { it in REFRESH_RANGE },
            thermalHysteresisDeciCelsius = intOrNull(o, "thermalHysteresisDeciCelsius")?.coerceIn(0, 500),
            thermalSustainHotMillis = longOrNull(o, "thermalSustainHotMillis")?.coerceIn(0L, 600_000L),
            thermalSustainCoolMillis = longOrNull(o, "thermalSustainCoolMillis")?.coerceIn(0L, 600_000L),
            thermalMinIntervalMillis = longOrNull(o, "thermalMinIntervalMillis")?.coerceIn(0L, 600_000L),
            networkCheckEnabled = o.optBoolean("networkCheckEnabled", false),
            networkPreLaunchWarn = o.optBoolean("networkPreLaunchWarn", true),
            networkAlertsEnabled = o.optBoolean("networkAlertsEnabled", false),
            fullPerformanceEnabled = o.optBoolean("fullPerformanceEnabled", false),
        )
    }

    private fun decodeHudMap(array: JSONArray?): Map<Long, HudLayout> {
        if (array == null) return emptyMap()
        val out = LinkedHashMap<Long, HudLayout>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val id = idOrNull(o, "id") ?: continue
            val name = TextSanitizer.sanitizeName(o.optString("name", ""), HudLayout.MAX_NAME_LENGTH)
                .ifBlank { "Layout" }
            out[id] = HudLayout(id = id, name = name, widgets = decodeWidgets(o.optJSONArray("widgets")))
        }
        return out
    }

    private fun decodeWidgets(array: JSONArray?): List<HudWidget> {
        if (array == null) return emptyList()
        val out = ArrayList<HudWidget>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val stat = enumOrNull<HudStat>(o.optStringOrNull("stat")) ?: continue
            out += HudWidget(
                id = o.optStringOrNull("id") ?: "w$i",
                stat = stat,
                xFraction = floatOrNull(o, "xFraction") ?: 0.05f,
                yFraction = floatOrNull(o, "yFraction") ?: 0.05f,
                textSizeSp = intOrNull(o, "textSizeSp") ?: HudWidget.DEFAULT_TEXT_SIZE_SP,
                opacityPercent = intOrNull(o, "opacityPercent") ?: 85,
                showLabel = o.optBoolean("showLabel", true),
                showBackground = o.optBoolean("showBackground", true),
                colorArgb = intOrNull(o, "colorArgb") ?: HudWidget.DEFAULT_COLOR,
            ).normalised()
        }
        return out
    }

    private fun decodeCrosshairMap(array: JSONArray?): Map<Long, CrosshairPreset> {
        if (array == null) return emptyMap()
        val out = LinkedHashMap<Long, CrosshairPreset>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val id = idOrNull(o, "id") ?: continue
            out[id] = CrosshairPreset(
                id = id,
                name = TextSanitizer.sanitizeName(o.optString("name", ""), CrosshairPreset.MAX_NAME_LENGTH)
                    .ifBlank { "Crosshair" },
                design = enumOrNull<CrosshairDesign>(o.optStringOrNull("design")) ?: CrosshairDesign.CROSS,
                sizeDp = intOrNull(o, "sizeDp") ?: CrosshairPreset.DEFAULT_SIZE_DP,
                thicknessDp = intOrNull(o, "thicknessDp") ?: CrosshairPreset.DEFAULT_THICKNESS_DP,
                centreGapDp = intOrNull(o, "centreGapDp") ?: 0,
                opacityPercent = intOrNull(o, "opacityPercent") ?: 90,
                rotationDegrees = intOrNull(o, "rotationDegrees") ?: 0,
                colorArgb = intOrNull(o, "colorArgb") ?: CrosshairPreset.DEFAULT_COLOR,
                showDot = o.optBoolean("showDot", false),
                showOutline = o.optBoolean("showOutline", true),
                xFraction = floatOrNull(o, "xFraction") ?: 0.5f,
                yFraction = floatOrNull(o, "yFraction") ?: 0.5f,
                imagePath = null,
            ).normalised()
        }
        return out
    }

    private fun decodeColorMap(array: JSONArray?): Map<Long, ColorPreset> {
        if (array == null) return emptyMap()
        val out = LinkedHashMap<Long, ColorPreset>()
        val scan = minOf(array.length(), SCAN_LIMIT)
        for (i in 0 until scan) {
            val o = array.optJSONObject(i) ?: continue
            val id = idOrNull(o, "id") ?: continue
            out[id] = ColorPreset(
                id = id,
                name = o.optString("name", "").ifBlank { ColorPreset.DEFAULT_NAME },
                correction = decodeCorrection(o.optJSONObject("correction")),
            ).normalised()
        }
        return out
    }

    private fun decodeCorrection(o: JSONObject?): ColorCorrection {
        if (o == null) return ColorCorrection.NEUTRAL
        return ColorCorrection(
            redGain = intOrNull(o, "redGain") ?: 0,
            greenGain = intOrNull(o, "greenGain") ?: 0,
            blueGain = intOrNull(o, "blueGain") ?: 0,
            gammaMode = enumOrNull<GammaMode>(o.optStringOrNull("gammaMode")) ?: GammaMode.COMBINED,
            gamma = intOrNull(o, "gamma") ?: 0,
            redGamma = intOrNull(o, "redGamma") ?: 0,
            greenGamma = intOrNull(o, "greenGamma") ?: 0,
            blueGamma = intOrNull(o, "blueGamma") ?: 0,
            saturation = intOrNull(o, "saturation") ?: 0,
            contrast = intOrNull(o, "contrast") ?: 0,
            hueDegrees = intOrNull(o, "hueDegrees") ?: 0,
            brightnessOffset = intOrNull(o, "brightnessOffset") ?: 0,
            visionFilter = enumOrNull<ColorVisionFilter>(o.optStringOrNull("visionFilter"))
                ?: ColorVisionFilter.NONE,
            invertColors = o.optBoolean("invertColors", false),
        ).normalised()
    }

    // ------------------------------------------------------------------------------- json read helpers
    // Each rejects a value of the wrong JSON type rather than letting org.json's `opt*` coerce it into a
    // spurious default — a non-numeric "brightnessPercent" must become "leave alone" (null), never 0, which
    // is a real device write. A numeric value written as a string is still accepted, because a hand-edited
    // file commonly quotes its numbers.

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").ifBlank { null }
    }

    private fun intOrNull(o: JSONObject, key: String): Int? = when (val v = o.opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun longOrNull(o: JSONObject, key: String): Long? = when (val v = o.opt(key)) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun floatOrNull(o: JSONObject, key: String): Float? {
        val value = when (val v = o.opt(key)) {
            is Number -> v.toFloat()
            is String -> v.trim().toFloatOrNull()
            else -> null
        } ?: return null
        return if (value.isNaN() || value.isInfinite()) null else value
    }

    /** A preset-reference id: a positive [Long], or null for absent/zero/negative (all "no preset"). */
    private fun idOrNull(o: JSONObject, key: String): Long? = longOrNull(o, key)?.takeIf { it > 0L }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
        if (name == null) return null
        return enumValues<T>().firstOrNull { it.name == name }
    }
}




