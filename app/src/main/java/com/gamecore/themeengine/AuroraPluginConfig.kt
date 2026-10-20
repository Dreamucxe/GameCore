package com.gamecore.themeengine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Theme Engine's configuration model: the four colour vectors it can wear, and the calibration
 * preset a user can save and reload.
 *
 * This file is deliberately plain Kotlin over plain data — `org.json` is the one dependency and it is
 * an Android platform class, not a library. Nothing here touches Compose, Android or a repository, so
 * every rule in it (what a value may be, what a preset means, how it is written down) is testable on a
 * plain JVM. That matters because these are the numbers the live backdrop is painted from: a velocity
 * of 42x or a colour of rgb(900, -4, 12) would be a rendering accident, and the bounds belong next to
 * the type rather than in whichever screen happened to construct it.
 */

/** The four looks the engine can paint. */
enum class ColorVectorMode(val label: String) {
    AURORA_VIOLET("Aurora Violet"),
    ICE_GLACIER("Ice Glacier"),
    MOLTEN_LAVA("Molten Lava"),
    CUSTOM("Custom"),
}

/**
 * A saved calibration (a "save point").
 *
 * [vectorMode] records the mode that was active when the preset was saved. Loading a preset restores
 * the thresholds and the base velocity; the engine still resolves the live mode from the metrics, so a
 * preset cannot leave the backdrop frozen in a colour that contradicts the reading beside it. The one
 * exception is [ColorVectorMode.CUSTOM], which is the user's explicit override: it uses
 * [customColorRgb] verbatim, and velocity alone continues to follow load.
 *
 * Every field is normalised by [CalibrationPreset.normalised] before it is used or written down, so a
 * preset that arrives from an old preference file — or from the hand of someone with a text editor —
 * cannot put the engine outside its bounds.
 */
data class CalibrationPreset(
    val id: String,
    val name: String,
    val vectorMode: ColorVectorMode,
    val fluidVelocity: Float,
    val cpuThreshold: Float,
    val ramThreshold: Float,
    val tempThreshold: Float,
    val customColorRgb: Triple<Int, Int, Int>? = null,
) {

    /** This preset with every value inside the bounds the renderer can actually honour. */
    fun normalised(): CalibrationPreset = copy(
        name = name.trim()
            .take(AuroraBounds.MAX_NAME_LENGTH)
            .ifBlank { AuroraBounds.DEFAULT_PRESET_NAME },
        fluidVelocity = fluidVelocity.coerceIn(AuroraBounds.MIN_VELOCITY, AuroraBounds.MAX_VELOCITY),
        cpuThreshold = cpuThreshold.coerceIn(AuroraBounds.MIN_CPU_THRESHOLD, AuroraBounds.MAX_CPU_THRESHOLD),
        ramThreshold = ramThreshold.coerceIn(AuroraBounds.MIN_RAM_THRESHOLD, AuroraBounds.MAX_RAM_THRESHOLD),
        tempThreshold = tempThreshold.coerceIn(AuroraBounds.MIN_TEMP_THRESHOLD, AuroraBounds.MAX_TEMP_THRESHOLD),
        // A custom colour is the only place a component outside 0..255 could come from; the blend below
        // assumes bytes, and an out-of-range channel would silently wrap on its way to a Color.
        customColorRgb = customColorRgb?.let { (r, g, b) ->
            Triple(
                r.coerceIn(0, 255),
                g.coerceIn(0, 255),
                b.coerceIn(0, 255),
            )
        },
    )
}

/**
 * Every limit the renderer and the preset store share.
 *
 * Named here rather than scattered through the screen so the slider ranges, the clamp in
 * [CalibrationPreset.normalised] and the test all read the same constants.
 */
object AuroraBounds {

    // ---------- fluid velocity -------------------------------------------------------------------

    /** Slowest the backdrop may drift. Below this the wave stops reading as motion at all. */
    const val MIN_VELOCITY = 0.25f

    /** Fastest the backdrop may drift. Above this it reads as strobing rather than fluid. */
    const val MAX_VELOCITY = 3.0f

    /** Neutral: a preset at 1.0x leaves each mode's own velocity exactly as the reference states it. */
    const val DEFAULT_VELOCITY = 1.0f

    // ---------- thresholds -----------------------------------------------------------------------

    const val MIN_CPU_THRESHOLD = 0f
    const val MAX_CPU_THRESHOLD = 100f

    /** CPU load at or above this steps the vector to Aurora Violet with default settings. */
    const val DEFAULT_CPU_THRESHOLD = 55f

    const val MIN_RAM_THRESHOLD = 0f
    const val MAX_RAM_THRESHOLD = 100f
    const val DEFAULT_RAM_THRESHOLD = 70f

    const val MIN_TEMP_THRESHOLD = 0f
    const val MAX_TEMP_THRESHOLD = 120f

    /** At or above this, with default settings, the vector goes molten rather than violet. */
    const val DEFAULT_TEMP_THRESHOLD = 90f

    /**
     * The metrics sliders' own ranges, and the scale the engine reads a temperature against.
     *
     * 120 °C is the top of the temperature slider on the calibration screen and a temperature no
     * phone should reach; using it as the denominator keeps severity comparable with a 0–100 score.
     */
    const val MAX_TEMP_METRIC = 120f
    const val MAX_PERCENT_METRIC = 100f

    // ---------- presets --------------------------------------------------------------------------

    /** A preset name is a label on a chip, not a paragraph. */
    const val MAX_NAME_LENGTH = 40

    /** The store keeps a bounded number so a runaway writer cannot grow the preference file forever. */
    const val MAX_PRESETS = 32

    /** What an unnamed save point is called. */
    const val DEFAULT_PRESET_NAME = "Save point"
}

/**
 * The palette and the per-mode velocity the reference specifies.
 *
 * These are the only three colours the automatic rules select, and they are exact: CPU 63 % under the
 * default thresholds must be `rgb(148, 90, 180)` at `1.92x`, not a smooth approximation of it. The
 * transition between modes is the engine's job — see [AuroraVisualEngine] and
 * [blendVector] — so the endpoints can stay exact.
 */
object AuroraPalette {

    /** Low load, cold device: soft ice blue. */
    val ICE: Triple<Int, Int, Int> = Triple(96, 208, 255)

    /** Moderate load: the reference violet, rgb(148, 90, 180). */
    val VIOLET: Triple<Int, Int, Int> = Triple(148, 90, 180)

    /** Extreme load or overheating: molten crimson. */
    val LAVA: Triple<Int, Int, Int> = Triple(226, 48, 32)

    const val ICE_VELOCITY = 0.5f
    const val VIOLET_VELOCITY = 1.92f
    const val LAVA_VELOCITY = 2.8f

    /** The colour a mode paints when nothing overrides it. */
    fun colourOf(mode: ColorVectorMode): Triple<Int, Int, Int> = when (mode) {
        ColorVectorMode.ICE_GLACIER -> ICE
        ColorVectorMode.AURORA_VIOLET -> VIOLET
        ColorVectorMode.MOLTEN_LAVA -> LAVA
        // Custom without a colour has nothing to paint from; the engine resolves it before this is
        // reached, and violet is the reference's own "active" colour for an undefined vector.
        ColorVectorMode.CUSTOM -> VIOLET
    }

    /** The velocity the reference names for a mode, before a preset's own multiplier. */
    fun velocityOf(mode: ColorVectorMode): Float = when (mode) {
        ColorVectorMode.ICE_GLACIER -> ICE_VELOCITY
        ColorVectorMode.AURORA_VIOLET -> VIOLET_VELOCITY
        ColorVectorMode.MOLTEN_LAVA -> LAVA_VELOCITY
        ColorVectorMode.CUSTOM -> VIOLET_VELOCITY
    }
}

/** The preset the engine starts from on a fresh install. */
object AuroraDefaults {

    const val DEFAULT_PRESET_ID = "default"

    fun preset(): CalibrationPreset = CalibrationPreset(
        id = DEFAULT_PRESET_ID,
        name = "Balanced",
        vectorMode = ColorVectorMode.AURORA_VIOLET,
        fluidVelocity = AuroraBounds.DEFAULT_VELOCITY,
        cpuThreshold = AuroraBounds.DEFAULT_CPU_THRESHOLD,
        ramThreshold = AuroraBounds.DEFAULT_RAM_THRESHOLD,
        tempThreshold = AuroraBounds.DEFAULT_TEMP_THRESHOLD,
    )
}

/**
 * The on-disk form of a user's save points.
 *
 * A JSON array under one preference key, written and read by [AuroraThemeRepository]. Decoding is
 * total: a malformed entry is dropped rather than failing the whole file, because one corrupt save
 * point should not cost the user the other nineteen, and a preference file is exactly the kind of
 * thing a half-finished write can leave damaged.
 */
object AuroraPresetCodec {

    private const val KEY_PRESETS = "presets"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_MODE = "mode"
    private const val KEY_VELOCITY = "velocity"
    private const val KEY_CPU = "cpu"
    private const val KEY_RAM = "ram"
    private const val KEY_TEMP = "temp"
    private const val KEY_COLOUR = "colour"

    fun encode(presets: List<CalibrationPreset>): String {
        val array = JSONArray()
        presets.take(AuroraBounds.MAX_PRESETS).forEach { preset ->
            val normalised = preset.normalised()
            array.put(
                JSONObject().apply {
                    put(KEY_ID, normalised.id)
                    put(KEY_NAME, normalised.name)
                    put(KEY_MODE, normalised.vectorMode.name)
                    put(KEY_VELOCITY, normalised.fluidVelocity.toDouble())
                    put(KEY_CPU, normalised.cpuThreshold.toDouble())
                    put(KEY_RAM, normalised.ramThreshold.toDouble())
                    put(KEY_TEMP, normalised.tempThreshold.toDouble())
                    normalised.customColorRgb?.let { (r, g, b) ->
                        put(KEY_COLOUR, JSONArray().put(r).put(g).put(b))
                    }
                },
            )
        }
        return JSONObject().put(KEY_PRESETS, array).toString()
    }

    /** Never throws: anything unreadable decodes to an empty list. */
    fun decode(raw: String?): List<CalibrationPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONObject(raw).optJSONArray(KEY_PRESETS) ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: continue
                    presetOf(entry)?.let { add(it) }
                }
            }
                .distinctBy { it.id }
                .take(AuroraBounds.MAX_PRESETS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun presetOf(entry: JSONObject): CalibrationPreset? {
        val id = entry.optString(KEY_ID).takeIf { it.isNotBlank() } ?: return null
        val mode = ColorVectorMode.entries.firstOrNull { it.name == entry.optString(KEY_MODE) }
            ?: return null
        return CalibrationPreset(
            id = id,
            name = entry.optString(KEY_NAME),
            vectorMode = mode,
            fluidVelocity = entry.optDouble(KEY_VELOCITY, AuroraBounds.DEFAULT_VELOCITY.toDouble()).toFloat(),
            cpuThreshold = entry.optDouble(KEY_CPU, AuroraBounds.DEFAULT_CPU_THRESHOLD.toDouble()).toFloat(),
            ramThreshold = entry.optDouble(KEY_RAM, AuroraBounds.DEFAULT_RAM_THRESHOLD.toDouble()).toFloat(),
            tempThreshold = entry.optDouble(KEY_TEMP, AuroraBounds.DEFAULT_TEMP_THRESHOLD.toDouble()).toFloat(),
            customColorRgb = entry.optJSONArray(KEY_COLOUR)?.let { colour ->
                if (colour.length() < 3) return@let null
                Triple(colour.optInt(0, 0), colour.optInt(1, 0), colour.optInt(2, 0))
            },
        ).normalised()
    }
}
