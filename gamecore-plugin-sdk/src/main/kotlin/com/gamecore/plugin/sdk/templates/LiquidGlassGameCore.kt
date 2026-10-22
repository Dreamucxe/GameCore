package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "Liquid Glass GameCore" — a pure UI-theme plugin that repaints GameCore in an iOS-inspired
 * dark "liquid glass" palette: true-black page, a near-black grouped-card surface, white ink,
 * a system-blue primary and an indigo accent.
 *
 * Like [cyberpunkGameCore] and [comicGameCore], this is a worked authoring example that lives
 * *beside* [Templates] in its own file, so it can be added without editing the shared template
 * library. It carries **data only**: a single [Permissions.CHANGE_UI] grant and one `theme.json`.
 * No rules, no overlay, no scoped data reads, and — because the package format admits no font or
 * code entry — no shipped font, artwork or script. The entire deliverable is two JSON files, so
 * there is nothing to license and nothing of Apple's (SF Pro / SF Symbols / the logo) is or can
 * be redistributed. The id and name carry no "iOS"/"Apple"/"iPhone"; it is only *inspired by* iOS.
 *
 * ### Palette → the six theme roles
 * The host repaints itself from exactly six colours (see the host's `PluginTheme`). The brief's
 * iOS dark system palette maps onto them; the colours that have no theme role — the semantic
 * accent SET (green/red/orange/yellow), the raised-row tone #2C2C2E, the separator and the
 * secondary-label alpha — have no home in a six-role model (only one [accent] exists and it
 * merely SEEDS Material's derived roles), so they are reported as gaps, not faked. Every enforced
 * text/fill pair clears WCAG AA (4.5:1); the two ink pairs clear AAA:
 *
 *  - white ink (`onSurface` #FFFFFF) on `surface` #1C1C1E → 17.01:1 (AAA)
 *  - white ink on `background` #000000                     → 21.00:1 (AAA)
 *  - white label (`onPrimary`) on `primary` blue #0A6FE0   → 4.81:1  (AA, +0.31 over the 4.5 floor)
 *
 * `primary` is NOT the brief's literal iOS blue. White-on-#0A84FF (iOS dark blue) is only 3.65:1
 * and white-on-#007AFF (iOS light blue) 4.02:1 — both FAIL the brief's AA 4.5 bar. So, per the
 * brief's own "verify and adjust for contrast / darken anything that fails" rule, the blue is
 * deepened the minimum needed to clear AA while keeping the iOS blue hue: #0A6FE0 (4.81:1). The
 * AAA option (~#0062CC, 5.80:1) is recorded in the task report but not used — it costs vividness
 * for margin the brief does not require.
 *
 * ### Format gaps (closest supported option used; see the task report)
 *  - **Translucent "liquid glass", backdrop blur, specular-rim gradient border, soft shadow and
 *    the inner top-edge highlight** have NO tokens → omitted, not faked. A six-opaque-colour theme
 *    cannot carry a material; the surface is a solid near-black #1C1C1E, the closest flat stand-in.
 *  - **Light + dark variants** cannot both ship in one plugin — `dark` is a single boolean and a
 *    theme carries ONE palette. The gaming-first dark palette ships; the light palette is recorded
 *    in the report and would need a separate sibling plugin (or a core two-palette change).
 *  - **Continuous/squircle corners and per-component radii** (cards 16-20, sheets 28, capsule
 *    buttons) are not expressible — there is one global `cornerRadiusDp` that scales a circular
 *    `RoundedCornerShape` ladder. `cornerRadiusDp = 20` gives an iOS-round card (house medium
 *    16→20) and sheet (extraLarge 28→35); capsule buttons would need a radius that over-rounds
 *    every component, so they are a reported gap.
 *  - **SF Pro / Inter and the whole type scale** (Large Title 34 … Caption 12), weights and
 *    tabular numerals cannot ship — `font` is a single family enum and the loader admits no font
 *    file. `rounded` is chosen because the host maps it to `FontFamily.SansSerif` (the device's
 *    clean neutral sans, nearest to SF Pro / Inter) and the plugin screen labels it by the family
 *    it actually gets. No font file ships, so there is no OFL obligation.
 *  - **The accent SET, segmented controls, grouped inset lists, grabber sheets, spring motion and
 *    the "reduce transparency" / "remove animations" respects** have no tokens → omitted.
 *  - `uiScale` stays 1.0: it is a global density multiplier on top of the user's own accessibility
 *    setting, not a way to add iOS's airy spacing (which is layout, not density), so the user's
 *    density is left untouched rather than scaled.
 */
fun liquidGlassGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.liquidglass",
    name = "Liquid Glass GameCore",
) {
    version = 2
    versionName = "1.1.0"
    // api and minHostVersion keep the SDK defaults: Sdk.API (1) and Sdk.HOST_VERSION_CODE (28).
    developer = "Dream"
    description = "iOS-inspired dark glass theme: deep blacks, system blue and indigo accents."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    theme {
        name = "Liquid Glass GameCore"
        dark = true
        primary = "#0A6FE0"       // system blue, darkened to clear AA (white label 4.81:1)
        onPrimary = "#FFFFFF"     // white label on blue (4.81:1)
        background = "#000000"    // iOS dark base — true black page (21.00:1)
        surface = "#1C1C1E"       // iOS dark grouped card (white ink 17.01:1)
        onSurface = "#FFFFFF"     // iOS dark label — white
        accent = "#5E5CE6"        // iOS dark indigo — seeds Material's derived secondary/tertiary
        cornerRadiusDp = 20       // iOS-round cards/sheets (global shape-ladder scale off medium 16)
        font = ThemeFonts.ROUNDED // host maps to FontFamily.SansSerif — nearest clean sans to SF Pro
        uiScale = 1.0
    }
    // The launcher icon this plugin asks for: one of the slots GameCore ships,
    // switched in Settings and outranked by the user's own choice the moment they pick one.
    icon(slot = IconSlots.MONO)
}
