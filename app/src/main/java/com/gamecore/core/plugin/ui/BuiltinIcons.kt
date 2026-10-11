package com.gamecore.core.plugin.ui

/**
 * The closed vocabulary of icon names a plugin screen may name for its chrome.
 *
 * A plugin screen declares an optional `icon` the host draws beside the screen's title in its
 * [androidx.compose.material3.TopAppBar] and in the entry row on the plugin detail screen. Like
 * every other string a plugin supplies, it is untrusted: the only safe thing to do with an
 * `icon` the host does not recognise is to draw nothing, because a plugin cannot ship a vector
 * and there is no path by which the host loads one — an `icon` names one of the glyphs already
 * compiled into GameCore, and names nothing else.
 *
 * This set is the codec's validation source, and it is deliberately a *pure* mirror of the
 * renderer's own private `BUILTIN_ICONS` map (`com.gamecore.ui.plugins.render.PluginNodeRenderer`)
 * rather than a reference to it: that map is Android — it holds `ImageVector` values and lives in
 * the UI layer — and the codec is pure `org.json` + the plugin models, JVM-unit-testable with no
 * `android.*` on its classpath. The names are transcribed here and pinned by the codec's own
 * tests, exactly as the SDK's `OverlayIcons` mirrors the same vocabulary on the plugin-author
 * side. The twenty names, their order, and the one-to-one correspondence with the renderer map
 * are the contract; a name present here but absent from the renderer would draw the renderer's
 * neutral placeholder, which is the one outcome this validation exists to prevent.
 *
 * **An unknown icon is resolved to null, not rejected.** This is the single place the screens
 * codec departs from the platform's usual "unknown key is a hard rejection" doctrine, and the
 * reason is that an icon is pure chrome: a screen with an unrecognised icon is a screen with no
 * icon, which is a complete, usable screen, whereas refusing the whole file over a decorative
 * glyph would deny the user working content for a cosmetic typo. The forward-compatibility
 * mechanism for everything load-bearing is still the manifest's `api`; an icon simply is not
 * load-bearing.
 */
object BuiltinIcons {

    /**
     * The twenty icon names GameCore can draw, mirroring the renderer's `BUILTIN_ICONS` keys in
     * their declared order. Lower-case because an icon id is a key matched against this set, not a
     * label shown to anyone.
     */
    val NAMES: Set<String> = setOf(
        "speed",
        "memory",
        "thermostat",
        "battery",
        "bolt",
        "timer",
        "timeline",
        "data",
        "insights",
        "sensors",
        "storage",
        "shield",
        "extension",
        "info",
        "warning",
        "block",
        "check",
        "dot",
        "game",
        "settings",
    )

    /**
     * The canonical icon id for [name], or null when the plugin named nothing the host can draw.
     *
     * Trimmed and lower-cased before the lookup so `" Speed "` and `"speed"` resolve to the same
     * glyph — the same normalisation [ComponentType.fromId] and the overlay module id apply, so a
     * screen icon cannot be made to miss a match by stray case or whitespace. A null, blank, or
     * unrecognised name all return null, which the codec stores as "no icon" rather than refusing
     * the screen.
     */
    fun resolve(name: String?): String? {
        if (name == null) return null
        val id = name.trim().lowercase()
        return if (id in NAMES) id else null
    }
}
