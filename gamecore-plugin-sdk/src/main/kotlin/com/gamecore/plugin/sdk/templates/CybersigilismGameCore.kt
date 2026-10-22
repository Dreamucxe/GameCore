package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Cybersigilism GameCore" — the chrome-and-void monochrome of the Cybersigilism moodboard: a silver
 * ribcage, thorned metal, and almost no hue at all.
 *
 * The one deliberately colourless theme in the set. True black background, a near-white chrome
 * primary, steel greys for every secondary role, square corners and a serif face for the sigil
 * lettering the reference implies. The spikes and thorns are shape, and the theme format has no
 * shape token beyond the corner radius — so the corner is square rather than a faked silhouette.
 *
 * Contrast:
 *  - #F2F2F4 on background #050506 → 18.22:1 (AAA)
 *  - #F2F2F4 on surface #101013    → 16.99:1 (AAA)
 *  - #000000 on primary #E8E8EC    → 17.19:1 (AAA)
 */
fun cybersigilismGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.cybersigilism",
    name = "Cybersigilism GameCore",
) {
    version = 2
    versionName = "1.1.0"
    developer = "Dream"
    description = "Monochrome chrome-on-void theme — silver, steel, true black, sharp edges, serif type."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Cybersigilism GameCore"
        dark = true
        amoled = true               // the reference background is a void
        primary = "#E8E8EC"         // chrome
        onPrimary = "#000000"
        background = "#050506"
        surface = "#101013"
        onSurface = "#F2F2F4"       // (16.99:1)
        accent = "#8A8F98"          // brushed steel
        cornerRadiusDp = 0           // thorns are sharp; the corner follows
        font = ThemeFonts.SERIF
        uiScale = 1.0

        roles {
            secondary = "#A8ADB5"
            tertiary = "#6E737B"
            surfaceVariant = "#18181C"
            onSurfaceVariant = "#9BA0A8"
            outline = "#3C3F45"
            error = "#E0453A"
            onError = "#0A0000"
        }
        typography {
            weight = ThemeWeights.MEDIUM
            scale = 1.0
        }
    }

    // The mono slot: the launcher icon GameCore wears while the sigil theme is active.
    icon(slot = IconSlots.MONO)
}
