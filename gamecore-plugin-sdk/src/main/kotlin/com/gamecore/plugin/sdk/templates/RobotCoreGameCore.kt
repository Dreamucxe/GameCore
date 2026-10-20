package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Robot Core GameCore" — white ceramic shell and cool instrument light from the Robot Core
 * moodboard, with its human hand as the one warm note.
 *
 * A light theme: porcelain surfaces, graphite ink, an eye-glow teal for anything actionable, and the
 * moodboard's warm skin tone as the accent against all that machined white. Small radii and a medium
 * weight keep it engineered rather than friendly.
 *
 * Contrast:
 *  - #1C2428 on background #F4F6F7 → 14.54:1 (AAA)
 *  - #1C2428 on surface #FFFFFF    → 15.76:1 (AAA)
 *  - white on primary #0E7C9E      → 4.78:1 (AA)
 *  - white on accent #B9543A       → 4.78:1 (AA)
 */
fun robotCoreGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.robotcore",
    name = "Robot Core GameCore",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description = "Porcelain-and-chrome light theme — instrument teal, warm accent, engineered edges."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)

    theme {
        name = "Robot Core GameCore"
        dark = false
        primary = "#0E7C9E"       // eye-glow teal
        onPrimary = "#FFFFFF"
        background = "#F4F6F7"    // ceramic body
        surface = "#FFFFFF"
        onSurface = "#1C2428"     // graphite ink (15.76:1)
        accent = "#B9543A"        // the human hand against the shell
        cornerRadiusDp = 4          // machined edges, not soft ones
        font = ThemeFonts.SYSTEM
        uiScale = 1.0

        roles {
            secondary = "#5E8A9C"
            tertiary = "#C99A86"
            surfaceVariant = "#E7ECEE"
            onSurfaceVariant = "#48585F"
            outline = "#C4CFD4"
            error = "#B3261E"
            onError = "#FFFFFF"
        }
        typography {
            weight = ThemeWeights.MEDIUM
            scale = 1.0
        }
    }
}
