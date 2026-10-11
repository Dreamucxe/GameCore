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

    enum class Reason { EMPTY, MALFORMED, MISSING_FIELD, BAD_COLOR, BAD_FONT, UNKNOWN_KEY, BAD_SCHEMA, BAD_TYPOGRAPHY }

    sealed interface Result {
        data class Parsed(val theme: PluginTheme) : Result
        data class Rejected(val reason: Reason, val detail: String = "") : Result
    }

    private val KNOWN_KEYS_V1 = setOf(
        "name", "dark", "primary", "onPrimary", "background", "surface",
        "onSurface", "accent", "cornerRadiusDp", "font", "uiScale",
    )

    /**
     * A v1 theme may carry the `schema` tag (declaring itself v1) and nothing else new. A theme
     * that omits `schema`, or sets it to 1, is still held to exactly the v1 surface — so a v1
     * theme that reaches for a v2 key is an UNKNOWN_KEY rejection, not a silently-ignored field.
     */
    private val KNOWN_KEYS_V1_AND_SCHEMA = KNOWN_KEYS_V1 + "schema"

    /** The v2 surface: the v1 keys, the schema tag, and the three v2 extras. */
    private val KNOWN_KEYS_V2 = KNOWN_KEYS_V1_AND_SCHEMA + setOf("amoled", "roles", "typography")

    /** The seven semantic roles a v2 `roles` object may name; any other key rejects the theme. */
    private val ROLE_KEYS = setOf(
        "secondary", "tertiary", "surfaceVariant", "onSurfaceVariant", "outline", "error", "onError",
    )

    /** The two keys a v2 `typography` object may name. */
    private val TYPO_KEYS = setOf("scale", "weight")

    private val REQUIRED_COLORS = listOf("primary", "onPrimary", "background", "surface", "onSurface", "accent")

    fun decode(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Rejected(Reason.EMPTY)
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return Result.Rejected(Reason.MALFORMED, e.message.orEmpty())
        }

        val schema = root.optInt("schema", PluginTheme.SCHEMA_V1)
        if (schema < PluginTheme.SCHEMA_V1 || schema > PluginTheme.SCHEMA_CURRENT) {
            return Result.Rejected(
                Reason.BAD_SCHEMA,
                "schema $schema is outside ${PluginTheme.SCHEMA_V1}..${PluginTheme.SCHEMA_CURRENT}",
            )
        }

        // A v1 theme is held to the v1 surface plus the schema tag itself, so a theme that reaches
        // for a v2 key without declaring v2 is rejected rather than quietly ignored. Only a theme
        // that declares schema >= 2 opens up the v2 keys.
        val knownKeys = if (schema >= PluginTheme.SCHEMA_V2) KNOWN_KEYS_V2 else KNOWN_KEYS_V1_AND_SCHEMA
        root.keys().asSequence().toList().firstOrNull { it !in knownKeys }?.let {
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

        // The v2 extras are parsed only when the theme declared schema >= 2, so a v1 theme is
        // byte-for-byte the value it was before these fields existed. Each one rejects the whole
        // theme on a bad value rather than defaulting it — the same contract the v1 fields hold to.
        var amoled = false
        var roles: PluginThemeRoles? = null
        var typography: PluginThemeTypography? = null
        if (schema >= PluginTheme.SCHEMA_V2) {
            amoled = root.optBoolean("amoled", false)

            if (root.has("roles")) {
                val obj = root.optJSONObject("roles")
                    ?: return Result.Rejected(Reason.MALFORMED, "\"roles\" is not an object")
                obj.keys().asSequence().toList().firstOrNull { it !in ROLE_KEYS }?.let {
                    return Result.Rejected(Reason.UNKNOWN_KEY, "unknown role \"$it\"")
                }
                val fill = HashMap<String, Int>()
                for (key in ROLE_KEYS) {
                    if (!obj.has(key)) continue
                    fill[key] = parseColor(obj.optString(key))
                        ?: return Result.Rejected(Reason.BAD_COLOR, "role \"$key\" is not a #RRGGBB or #AARRGGBB colour")
                }
                roles = PluginThemeRoles(
                    secondary = fill["secondary"], tertiary = fill["tertiary"],
                    surfaceVariant = fill["surfaceVariant"], onSurfaceVariant = fill["onSurfaceVariant"],
                    outline = fill["outline"], error = fill["error"], onError = fill["onError"],
                )
            }

            if (root.has("typography")) {
                val obj = root.optJSONObject("typography")
                    ?: return Result.Rejected(Reason.MALFORMED, "\"typography\" is not an object")
                obj.keys().asSequence().toList().firstOrNull { it !in TYPO_KEYS }?.let {
                    return Result.Rejected(Reason.UNKNOWN_KEY, "unknown typography key \"$it\"")
                }
                val typeScale = obj.optDouble("scale", PluginThemeTypography.DEFAULT_SCALE.toDouble()).toFloat()
                    .coerceIn(PluginThemeTypography.MIN_SCALE, PluginThemeTypography.MAX_SCALE)
                val weight = if (obj.has("weight")) {
                    PluginThemeTypography.Weight.fromId(obj.optString("weight"))
                        ?: return Result.Rejected(Reason.BAD_TYPOGRAPHY, "\"${obj.optString("weight")}\" is not a known weight")
                } else {
                    null
                }
                typography = PluginThemeTypography(scale = typeScale, weight = weight)
            }
        }
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
                schema = schema,
                amoled = amoled,
                roles = roles,
                typography = typography,
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
