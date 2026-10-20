package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Cybercity GameCore" — a light theme drawn from the white utopian towers, blue sky and dusk-pink
 * moon of the Cybercity moodboard.
 *
 * A pure UI theme: one [Permissions.CHANGE_UI] grant and one `theme.json`. It is schema 2, so it uses
 * the semantic roles and a light type weight; it still carries no image, because the theme format has
 * no slot for one and the reference photography stays a reference.
 *
 * Contrast (WCAG, checked in the package test):
 *  - ink #10233A on background #F2F6FC → 14.63:1 (AAA)
 *  - ink #10233A on surface #FFFFFF    → 15.87:1 (AAA)
 *  - white on primary #1D5FD1         → 5.82:1 (AA)
 */
fun cybercityGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.cybercity",
    name = "Cybercity GameCore",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description = "Light sky-and-pearl theme — tower blue, dusk pink, domed corners."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)

    theme {
        name = "Cybercity GameCore"
        dark = false
        primary = "#1D5FD1"       // tower glass blue
        onPrimary = "#FFFFFF"
        background = "#F2F6FC"    // pale sky
        surface = "#FFFFFF"       // white architecture
        onSurface = "#10233A"     // navy ink (15.87:1)
        accent = "#E85D9E"        // the dusk-pink moon
        cornerRadiusDp = 20        // curved domes and saucer towers
        font = ThemeFonts.ROUNDED
        uiScale = 1.0

        roles {
            secondary = "#2B6CB0"      // deeper sky
            tertiary = "#7FA8D9"       // hazy horizon blue
            surfaceVariant = "#E4ECF7"
            onSurfaceVariant = "#48586E"
            outline = "#C2CFE0"
            error = "#B3261E"
            onError = "#FFFFFF"
        }
        typography {
            weight = ThemeWeights.LIGHT  // airy, unweighted like the towers
            scale = 1.03
        }
    }
}
