package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Cybercore GameCore" — the Y2K blue-screen aesthetic of the Cybercore moodboard: Winamp-era chrome
 * on a CRT, a monochrome blue glow, and the dense information layout of a 2000s desktop.
 *
 * Schema 2 supplies the extra blue roles and a slightly tight, mono type ramp. Square corners and a
 * lower UI scale are the closest the format gets to a scanline console; the format has no glow and no
 * background texture, and the theme does not pretend to carry either.
 *
 * Contrast:
 *  - #BFE4FF on background #000A1A → 14.89:1 (AAA)
 *  - #BFE4FF on surface #06142B    → 13.79:1 (AAA)
 *  - ink #001018 on primary #00A8FF → 7.40:1 (AAA)
 */
fun cybercoreGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.cybercore",
    name = "Cybercore GameCore",
) {
    version = 2
    versionName = "1.1.0"
    developer = "Dream"
    description = "Y2K blue-screen theme — CRT navy, screen-blue glow, mono type, square corners."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    theme {
        name = "Cybercore GameCore"
        dark = true
        primary = "#00A8FF"       // CRT screen blue
        onPrimary = "#001018"
        background = "#000A1A"    // powered-off tube
        surface = "#06142B"       // dark UI chrome
        onSurface = "#BFE4FF"     // phosphor text (13.79:1)
        accent = "#8BD4FF"        // highlight scanline
        cornerRadiusDp = 0         // square, beveled panels
        font = ThemeFonts.MONO
        uiScale = 0.95

        roles {
            secondary = "#4FC3F7"
            tertiary = "#E0F7FF"
            surfaceVariant = "#0A1F3D"
            onSurfaceVariant = "#7FA6C9"
            outline = "#1E3A5F"
            error = "#FF5252"
            onError = "#1A0000"
        }
        typography {
            weight = ThemeWeights.NORMAL
            scale = 0.96               // dense, terminal-like
        }
    }

    // The neon slot is the plugin's own launcher entry — the icon the launcher shows for it.
    icon(slot = IconSlots.NEON)
}
