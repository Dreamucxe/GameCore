package com.gamecore.core.plugin.theme

/**
 * A plugin's theme, parsed and clamped from its `theme.json`.
 *
 * A theme is the lowest-risk thing a plugin can carry: colours, a corner radius, a font
 * choice, a density nudge, — from schema 2 — a handful of semantic colour roles, a type
 * scale and weight, and an AMOLED preset, and — from schema 3 — an optional [PluginGlitch]
 * motion block. Each is optional and absent on a theme that predates it. It is
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
    val glitch: PluginGlitch? = null,
    val glass: PluginGlass? = null,
) {
    /**
     * The font names a theme may pick. A theme cannot ship a font file of its own.
     *
     * The bundled faces are [MINECRAFT], [CINZEL], [COMIC_NEUE], [INTER], [JETBRAINS_MONO],
     * [ORBITRON], [PRESS_START_2P], [RUBIK] and [VT323]; each ships with its SIL Open Font License
     * text under `assets/licenses/`, and the theme card names the family it actually gets.
     * [CURSIVE] is the platform's own cursive family, which means the exact face varies by device.
     */
    enum class Font(val id: String) {
        SYSTEM("system"),
        ROUNDED("rounded"),
        MONO("mono"),
        SERIF("serif"),
        CURSIVE("cursive"),
        MINECRAFT("minecraft"),
        CINZEL("cinzel"),
        COMIC_NEUE("comic-neue"),
        INTER("inter"),
        JETBRAINS_MONO("jetbrains-mono"),
        ORBITRON("orbitron"),
        PRESS_START_2P("press-start-2p"),
        RUBIK("rubik"),
        VT323("vt323");

        /**
         * The name the UI prints for this face.
         *
         * The id is what a theme file spells, and it is not always what a person would call the
         * family: `jetbrains-mono` is "JetBrains Mono" and `press-start-2p` is "Press Start 2P".
         * The platform families keep the plain names they have always had.
         */
        val displayName: String
            get() = when (this) {
                CINZEL -> "Cinzel"
                COMIC_NEUE -> "Comic Neue"
                INTER -> "Inter"
                JETBRAINS_MONO -> "JetBrains Mono"
                ORBITRON -> "Orbitron"
                PRESS_START_2P -> "Press Start 2P"
                RUBIK -> "Rubik"
                VT323 -> "VT323"
                else -> id.replaceFirstChar { it.uppercaseChar() }
            }

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): Font? = byId[id.trim().lowercase()]
        }
    }

    companion object {
        /** The v1 theme — six colours, a corner, a font, a scale — and the v2 superset of it. */
        const val SCHEMA_V1 = 1
        const val SCHEMA_V2 = 2

        /**
         * Schema 3 is schema 2 plus `effects`, whose only member today is the `glitch` block.
         *
         * It is a new schema rather than an optional key on v2 because of what it asks for. A v2
         * theme that this build quietly ignored would render as a plain palette on some devices
         * and as a tearing HUD on others, with nothing on screen to say which — and the author
         * would have no number to raise to get the behaviour they asked for. A v3 theme on a host
         * that predates it is an UNKNOWN_KEY rejection with a reason, which is the honest answer.
         */
        const val SCHEMA_V3 = 3

        /**
         * Schema 4 is schema 3 plus the `glass` material block.
         *
         * A new schema for the same reason v3 was: a material is not a decoration on a palette, it
         * is what the palette is made of, and a host that ignored the block would render a flat
         * opaque theme where its author designed a translucent one — with nothing on screen to say
         * the two had ever been different. A v4 theme on an older host is an UNKNOWN_KEY rejection
         * carrying a reason, which is the honest answer, and a v4 theme carries no `glass` block at
         * all if it did not ask for one.
         */
        const val SCHEMA_V4 = 4

        /** The newest schema this build understands; [PluginThemeCodec] rejects anything past it. */
        const val SCHEMA_CURRENT = SCHEMA_V4

        const val MIN_CORNER_DP = 0
        const val MAX_CORNER_DP = 48
        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.4f
    }
}
