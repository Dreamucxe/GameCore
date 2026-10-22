package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Retro Futurism GameCore" — the 1980s-anime future of the Retro futurism moodboard: cream print
 * paper, teal machinery, coral-red highlights and a visor-green note.
 *
 * Light, warm and printed rather than screen-lit, with a serif ramp to echo the era's posters. The
 * beige-and-teal pairing is the moodboard's own; the CD rainbow and hand-drawn grain have no token
 * and are left out.
 *
 * Contrast:
 *  - #2A2118 on background #F2E7D5 → 12.92:1 (AAA)
 *  - #2A2118 on surface #FFF6E8    → 14.75:1 (AAA)
 *  - white on primary #0E7C74      → 5.06:1 (AA)
 *  - white on accent #C43F27       → 5.14:1 (AA)
 */
fun retroFuturismGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.retrofuturism",
    name = "Retro Futurism GameCore",
) {
    version = 2
    versionName = "1.1.0"
    developer = "Dream"
    description = "Cream-paper 80s future — teal machinery, coral print, visor green, serif type."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    // The launcher icon is visible with the app closed; a slot asks for an icon GameCore ships.
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Retro Futurism GameCore"
        dark = false
        primary = "#0E7C74"       // teal machinery
        onPrimary = "#FFFFFF"
        background = "#F2E7D5"    // aged print paper
        surface = "#FFF6E8"       // lit page
        onSurface = "#2A2118"     // sepia-black ink (14.75:1)
        accent = "#C43F27"        // coral-red print
        cornerRadiusDp = 10         // beveled 80s hardware
        font = ThemeFonts.SERIF
        uiScale = 1.0

        roles {
            secondary = "#B4703A"      // amber indicator
            tertiary = "#7A9C3D"       // visor green
            surfaceVariant = "#E8DCC8"
            onSurfaceVariant = "#6B5A46"
            outline = "#C9B79C"
            error = "#A83226"
            onError = "#FFFFFF"
        }
        typography {
            weight = ThemeWeights.MEDIUM
            scale = 1.0
        }
    }

    // The glitched reticle GameCore already ships — the only thing that can become the app icon.
    icon(slot = IconSlots.GLITCH)
}
