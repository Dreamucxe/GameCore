package com.gamecore.core.plugin.theme

/**
 * A plugin's theme, parsed and clamped from its `theme.json`.
 *
 * A theme is the lowest-risk thing a plugin can carry: colours, a corner radius, a font
 * choice, a density nudge, and — from schema 2 — a handful of semantic colour roles, a type
 * scale and weight, and an AMOLED preset, each of them optional and absent on a v1 theme. It is
 * still untrusted data, so this model holds only values that
 * have already passed [PluginThemeCodec] — every colour is a valid opaque-or-alpha ARGB int,
 * the radius and the scales are inside sane bounds, and the font is one of a fixed set the app
 * actually bundles. The renderer maps these onto the real `MaterialTheme` and never has to
 * re-validate; a `PluginTheme` instance is a value the UI layer may trust.
 *
 * There is deliberately no "raw CSS", no arbitrary font URL, and no per-component style blob:
 * a theme can recolour and re-shape the official component set, not introduce styling the app
 * has no code to apply.
 */
data class PluginTheme(
    val name: String,
    val dark: Boolean,
    val primary: Int,
    val onPrimary: Int,
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val accent: Int,
    val cornerRadiusDp: Int,
    val font: Font,
    val uiScale: Float,
    val schema: Int = SCHEMA_V1,
    val amoled: Boolean = false,
    val roles: PluginThemeRoles? = null,
    val typography: PluginThemeTypography? = null,
) {
    /** The fonts GameCore bundles. A theme may pick one; it cannot ship a font file. */
    enum class Font(val id: String) {
        SYSTEM("system"),
        ROUNDED("rounded"),
        MONO("mono"),
        SERIF("serif");

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): Font? = byId[id.trim().lowercase()]
        }
    }

    companion object {
        /** The v1 theme — six colours, a corner, a font, a scale — and the v2 superset of it. */
        const val SCHEMA_V1 = 1
        const val SCHEMA_V2 = 2

        /** The newest schema this build understands; [PluginThemeCodec] rejects anything past it. */
        const val SCHEMA_CURRENT = 2

        const val MIN_CORNER_DP = 0
        const val MAX_CORNER_DP = 48
        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.4f
    }
}
