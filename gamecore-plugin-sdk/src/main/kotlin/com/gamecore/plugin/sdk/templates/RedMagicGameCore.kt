package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "RedMagic GameCore" — a pure UI-theme plugin that repaints GameCore in the red-on-black RedMagic /
 * REDLINE palette: signature red primary, near-black page, cool dark panels.
 *
 * This is the data-only companion to the built-in **Redline skin**. It carries exactly what a
 * `.gcplugin` can carry — one [Permissions.CHANGE_UI] grant and a `theme.json` of six colours, a
 * corner radius and a font — so the red palette installs on an *unmodified* GameCore. What it cannot
 * carry is the rest of the look: the pinwheel mark, the hexagon, the jagged cut-corner shapes and the
 * restyled home banner are drawn in host code and exist only when the Redline skin is on. A theme
 * plugin has no field for a vector, a cut corner or a screen layout, so this ships the colours and
 * says so rather than faking an edge it cannot cut.
 *
 * ### Palette → the six theme roles
 * The host repaints from six colours. The RedMagic palette maps onto them; roles with no token (the
 * lifted-surface tint, success green, the warning amber) are left to Material's derivation from
 * [accent]. Every mapped text/fill pair clears WCAG AA (4.5:1):
 *
 *  - body text (`onSurface` #ECEDF2) on `surface` #161620   → 14.8:1 (AAA)
 *  - body text on `background` #08070A                      → 17.6:1 (AAA)
 *  - ink (`onPrimary` #FFFFFF) on `primary` #E8142D         → 4.52:1 (AA) — white chosen over black
 *    deliberately: the RedMagic look is white text on the red, and #E8142D carries it at AA.
 *
 * ### Format gaps (closest supported option used)
 *  - The jagged cut corners are not a theme token → smallest supported radius, `cornerRadiusDp = 3`,
 *    which is as square as the format goes; the real bevel is the host skin's.
 *  - A techno display face cannot be shipped → nearest bundled face, `mono`.
 *  - The pinwheel mark, the hero band and per-screen chrome have no tokens → omitted, not faked.
 */
fun redMagicGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.redmagic",
    name = "RedMagic GameCore",
) {
    version = 1
    versionName = "1.0.0"
    // api and minHostVersion keep the SDK defaults: Sdk.API (1) and Sdk.HOST_VERSION_CODE (28).
    developer = "Dream"
    description = "Red-on-black RedMagic theme — signature red on near-black. The jagged edges and " +
        "pinwheel logo are part of the built-in Redline skin, not this plugin."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)

    theme {
        name = "RedMagic GameCore"
        dark = true
        primary = "#E8142D"       // signature red — hero fills, selected states
        onPrimary = "#FFFFFF"     // white ink on red (4.52:1), the RedMagic way
        background = "#08070A"    // near-black page
        surface = "#161620"       // cool dark panels
        onSurface = "#ECEDF2"     // body text (14.8:1 on surface, 17.6:1 on background)
        accent = "#E8142D"        // red drives Material's derived secondary/tertiary
        cornerRadiusDp = 3        // closest supported option to the skin's cut corners
        font = ThemeFonts.MONO    // closest bundled face to the RedMagic display type
        uiScale = 1.0
    }
}
