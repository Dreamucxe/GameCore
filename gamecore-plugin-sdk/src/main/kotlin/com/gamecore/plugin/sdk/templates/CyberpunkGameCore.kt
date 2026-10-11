package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "Cyberpunk GameCore" — a pure UI-theme plugin that repaints GameCore in a dark neon
 * cyberpunk palette (cyan primary, magenta accent, near-black surfaces).
 *
 * This is a worked authoring example that lives *beside* [Templates] in its own file, so it can
 * be added without editing the shared template library, and it does NOT touch the unrelated
 * `Templates.cyberpunk()` demo (a theme+rules combo built for a different, example id).
 *
 * It carries **data only**: a single [Permissions.CHANGE_UI] grant and one `theme.json`. No
 * rules, no overlay, no scoped data reads — a theme is the lowest-risk thing a plugin can be,
 * and this one asks for nothing more than the permission to recolour.
 *
 * ### Palette → the six theme roles
 * The host repaints itself from exactly six colours (see the host's `PluginTheme`). The richer
 * cyberpunk palette in the brief is mapped onto those six; the colours that have no theme role
 * (highlight yellow, success green, the spec's error red, the raised-surface tint) are left to
 * Material's own derivation from [accent]. Every mapped text/fill pair clears WCAG AA (4.5:1):
 *
 *  - body text (`onSurface` #E6F1FF) on `surface` #12121F → 16.25:1 (AAA)
 *  - body text on `background` #0A0A12                     → 17.27:1 (AAA)
 *  - ink (`onPrimary` #0A0A12) on `primary` cyan          → 14.00:1 (AAA)
 *
 * ### Format gaps (closest supported option used; see the task report)
 *  - Chamfered / cut corners are not a theme token → smallest supported radius, `cornerRadiusDp = 2`.
 *  - A techno display face (Orbitron / Rajdhani) cannot be shipped → nearest bundled face, `mono`.
 *  - 1dp neon borders, glow, per-screen palettes and motion have no tokens → omitted, not faked.
 */
fun cyberpunkGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.cyberpunk",
    name = "Cyberpunk GameCore",
) {
    version = 1
    versionName = "1.0.0"
    // api and minHostVersion keep the SDK defaults: Sdk.API (1) and Sdk.HOST_VERSION_CODE (28).
    developer = "Dream"
    description = "Dark neon cyberpunk theme — cyan and magenta on near-black."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)

    theme {
        name = "Cyberpunk GameCore"
        dark = true
        primary = "#00F0FF"       // neon cyan — hero fills, selected states
        onPrimary = "#0A0A12"     // near-black ink on cyan (14.00:1)
        background = "#0A0A12"    // near-black page
        surface = "#12121F"       // raised panels
        onSurface = "#E6F1FF"     // body text (16.25:1 on surface, 17.27:1 on background)
        accent = "#FF2BD6"        // neon magenta — drives Material's derived secondary/tertiary
        cornerRadiusDp = 2        // closest supported option to chamfered corners
        font = ThemeFonts.MONO    // closest bundled face to Share Tech Mono
        uiScale = 1.0
    }
}
