package com.gamecore.core.plugin.theme

import com.gamecore.core.common.TextSanitizer
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses and clamps a plugin's `theme.json` into a trusted [PluginTheme].
 *
 * The job here is the same contract as every other codec in the platform: untrusted JSON in,
 * a value the rest of the app may trust out, or a typed rejection. Three things are enforced
 * and none can be skipped by the caller — a theme with one bad field is rejected whole rather
 * than loaded with that field defaulted silently, because a half-applied theme is how you get
 * white text on a white surface:
 *
 *  - **Colours** are parsed from `#RRGGBB` / `#AARRGGBB` hex only, and a colour given without
 *    an alpha is forced opaque. A string that is not a hex colour is a rejection, not a
 *    fallback to black, so a typo cannot quietly turn a surface invisible.
 *  - **Numbers** (corner radius, UI scale) are clamped into [PluginTheme]'s bounds rather than
 *    rejected, because a request slightly outside the range is best honoured at the edge.
 *  - **Enums** (font) resolve against the bundled set or reject; a theme cannot name a font
 *    file to load.
 *
 * Pure Kotlin + org.json, no Android, so the parse rules are unit-tested on the JVM.
 */
object PluginThemeCodec {

    enum class Reason { EMPTY, MALFORMED, MISSING_FIELD, BAD_COLOR, BAD_FONT, UNKNOWN_KEY }

    sealed interface Result {
        data class Parsed(val theme: PluginTheme) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    private val KNOWN_KEYS = setOf(
        "name", "dark", "primary", "onPrimary", "background", "surface",
        "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
    )

    private val REQUIRED_COLORS = listOf("primary", "onPrimary", "background", "surface", "onSurface", "accent")

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        root.keys().asSequence().toList().firstOrNull { it !in KNOWN_KEYS }?.let {
            return Result.Rejected(Reason.UNKNOWN_KEY, "unknown key \"$it\"")
        }

        for (c in REQUIRED_COLORS) {
            if (!root.has(c)) return Result.Rejected(Reason.MISSING_FIELD, "missing colour \"$c\"")
        }

        val colors = HashMap<String, Int>()
        for (c in REQUIRED_COLORS) {
            val argb = parseColor(root.optString(c))
                ?: return Result.Rejected(Reason.BAD_COLOR, "\"$c\" is not a #RRGGBB or #AARRGGBB colour")
            colors[c] = argb
        }

        val font = PluginTheme.Font.fromId(root.optString("font", "system"))
            ?: return Result.Rejected(Reason.BAD_FONT, "\"${root.optString("font")}\" is not a bundled font")

        val name = TextSanitizer.sanitizeName(root.optString("name", "Theme"), 48)
        val dark = root.optBoolean("dark", true)
        val corner = root.optInt("cornerRadiusDp", 16)
            .coerceIn(PluginTheme.MIN_CORNER_DP, PluginTheme.MAX_CORNER_DP)
        val scale = root.optDouble("uiScale", 1.0).toFloat()
            .coerceIn(PluginTheme.MIN_UI_SCALE, PluginTheme.MAX_UI_SCALE)

        return Result.Parsed(
            PluginTheme(
                name = name,
                dark = dark,
                primary = colors.getValue("primary"),
                onPrimary = colors.getValue("onPrimary"),
                background = colors.getValue("background"),
                surface = colors.getValue("surface"),
                onSurface = colors.getValue("onSurface"),
                accent = colors.getValue("accent"),
                cornerRadiusDp = corner,
                font = font,
                uiScale = scale,
            ),
        )
    }

    /**
     * Parse `#RGB`-grammar hex to an ARGB int, or null. Accepts `#RRGGBB` (forced opaque) and
     * `#AARRGGBB`. Deliberately strict: no named colours, no `rgb()`, no 3-digit shorthand —
     * one unambiguous grammar is easier to validate than several and harder to fool.
     */
    fun parseColor(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("#") ?: return null
        if (!HEX.matches(s)) return null
        return when (s.length) {
            6 -> 0xFF000000.toInt() or s.toLong(16).toInt()
            8 -> s.toLong(16).toInt()
            else -> null
        }
    }

    private val HEX = Regex("^[0-9a-fA-F]+$")
}
