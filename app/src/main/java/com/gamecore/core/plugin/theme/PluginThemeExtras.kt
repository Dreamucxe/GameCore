package com.gamecore.core.plugin.theme

/**
 * The v2 theme extras: the semantic colour roles and the type controls a plugin may add on top of
 * the v1 six-colour theme.
 *
 * These are the fields [PluginThemeCodec] parses only when a theme declares `schema` >= 2, kept in
 * their own file for the same reason the rest of the model is pure Kotlin: no Android type leaks
 * in, so every bound here is unit-tested on the JVM. The host bridge (`PluginThemeBridge`) is the
 * only place these turn into Compose values, and the mapping from [PluginThemeTypography.Weight]
 * to a Compose `FontWeight` lives there, not here, so this file never imports a UI type.
 *
 * Both are additive by construction: a role or a weight the theme does not name is null, and the
 * bridge then leaves the house scheme's own value in place. That is what lets a v2 theme carry an
 * opinion about three roles and inherit the other thirty-odd, exactly as the v1 six already do.
 */

/**
 * The seven Material roles a v2 theme may recolour beyond the v1 six.
 *
 * Each is a nullable opaque-or-alpha ARGB int, and null means "leave the house scheme's value".
 * The bridge applies a supplied *fill* ([secondary], [tertiary], [surfaceVariant], [outline],
 * [error]) directly and guards a supplied *ink* ([onSurfaceVariant], [onError]) against its own
 * backdrop the same way it guards the v1 inks — so a role a theme sets to something illegible
 * degrades to a readable value rather than being painted blind.
 */
data class PluginThemeRoles(
    val secondary: Int? = null,
    val tertiary: Int? = null,
    val surfaceVariant: Int? = null,
    val onSurfaceVariant: Int? = null,
    val outline: Int? = null,
    val error: Int? = null,
    val onError: Int? = null,
)

/**
 * A v2 theme's type controls: a scale on the whole type ramp and an optional global weight.
 *
 * [scale] multiplies every role's font size and line height together, so the ramp keeps its
 * proportions — a theme nudges the type up or down, it does not redraw the scale. It is clamped
 * into [MIN_SCALE]..[MAX_SCALE] by the codec and again by the bridge, the same belt-and-braces the
 * corner radius gets.
 *
 * [weight] is null unless the theme asks for one global weight. Null means "keep each role's own
 * weight" — the Material ramp draws display and label text at different weights on purpose, and a
 * theme that only wanted larger text has no reason to flatten that. A non-null weight is the theme
 * deliberately asking for one weight across the whole app, which the bridge then applies to every
 * role.
 */
data class PluginThemeTypography(
    val scale: Float = DEFAULT_SCALE,
    val weight: Weight? = null,
) {
    /** The global weights a theme may name. [id] is the string the theme's JSON carries. */
    enum class Weight(val id: String) {
        LIGHT("light"),
        NORMAL("normal"),
        MEDIUM("medium"),
        BOLD("bold");

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): Weight? = byId[id.trim().lowercase()]
        }
    }

    companion object {
        /** The ramp may lean up or down, not be rebuilt — the same spirit as the UI-scale clamp. */
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.30f
        const val DEFAULT_SCALE = 1f
    }
}
