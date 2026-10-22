package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Chromecore GameCore" — liquid chrome on a true-black void, drawn from the Chromecore moodboard:
 * silver highlights, a cold blue reflection and the occasional red glint.
 *
 * AMOLED black because the reference's void is true black, a near-white chrome primary with black ink,
 * and the largest corner radius in the set — the one shape a theme can make liquid. The format has no
 * gradient or specular highlight, so the chrome reads as flat silver; that is stated, not faked.
 *
 * Contrast:
 *  - #EDF3F8 on background #000000 → 18.78:1 (AAA)
 *  - #EDF3F8 on surface #0D0D10    → 17.35:1 (AAA)
 *  - #05070A on primary #C9D6E8    → 13.70:1 (AAA)
 */
fun chromecoreGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.chromecore",
    name = "Chromecore GameCore",
) {
    version = 2
    versionName = "1.1.0"
    developer = "Dream"
    description = "Liquid chrome on true black — silver primary, blue reflection, red glint, round forms."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Chromecore GameCore"
        dark = true
        amoled = true               // the reference void is true black
        primary = "#C9D6E8"         // polished chrome
        onPrimary = "#05070A"
        background = "#000000"
        surface = "#0D0D10"
        onSurface = "#EDF3F8"       // bright chrome text (17.35:1)
        accent = "#4D8BFF"          // the blue reflection
        cornerRadiusDp = 28          // liquid — the roundest the format allows
        font = ThemeFonts.SYSTEM
        uiScale = 1.0

        roles {
            secondary = "#4D8BFF"
            tertiary = "#D34A3A"       // the red glint
            surfaceVariant = "#16181D"
            onSurfaceVariant = "#9AA6B6"
            outline = "#3A4250"
            error = "#FF6B5E"
            onError = "#120404"
        }
        typography {
            weight = ThemeWeights.LIGHT
            scale = 1.0
        }
    }

    // The neon slot: the launcher icon GameCore wears while the chrome theme is active.
    icon(slot = IconSlots.NEON)
}
