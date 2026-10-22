package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.GlassSprings
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "Liquid Glass GameCore" — an iOS-inspired dark glass theme: a true-black page, a translucent
 * near-black grouped-card surface, white ink, a system-blue primary and an indigo accent, worn as a
 * real material rather than as a flat tint.
 *
 * Like [cyberpunkGameCore] and [comicGameCore], this is a worked authoring example that lives
 * *beside* [Templates] in its own file, so it can be added without editing the shared template
 * library. It carries **data only**: a `theme.json`, one `settings.json` and one `icon.json`. No
 * rules, no overlay, no scoped data reads, and — because the package format admits no font or code
 * entry — no shipped font, artwork or script. The entire deliverable is three JSON files, so there
 * is nothing to license and nothing of Apple's (SF Pro / SF Symbols / the logo) is or can be
 * redistributed. The id and name carry no "iOS"/"Apple"/"iPhone"; it is only *inspired by* iOS.
 *
 * ### The material (schema 4)
 *
 * This theme was written when the format had six opaque colours and no way to describe a material,
 * so its own documentation used to list "translucent glass, backdrop blur, specular rim, soft
 * shadow, inner top-edge highlight" as a gap it refused to fake. Schema 4 closes that gap, and the
 * plugin now *asks* for the material it is named after:
 *
 *  - `transparency = 0.62` — iOS glass is genuinely see-through; the surface colour sets the
 *    ceiling and the host's own fill floor stops it dissolving into the backdrop.
 *  - `gloss = 0.55` — the specular rim and the highlight falling across the face. This is the
 *    "glossy panel", and it is what still reads as glass on a device that cannot blur.
 *  - `blurRadiusDp = 16` — the backdrop blur. **Android 12 and up only.** Below API 31 the host
 *    draws the same material with a sharp backdrop and the Control Center says so, which is why
 *    this is honest to ask for on a device that will not get it.
 *  - `spring = snappy` — iOS motion is a low-bounce spring, not an ease. A panel settling onto a
 *    new intensity arrives quickly and stops; nothing overshoots.
 *  - `sheen = true`, `sheenPeriodMs = 9000` — one slow drift of the highlight across the face, so
 *    the surface moves like a liquid rather than sitting as a fill. Slow on purpose: iOS's own
 *    glass is static, and a fast drift would read as a broken gradient rather than as material.
 *  - `hardwareReactive = false` — glass is the one effect here that must never thin itself out on
 *    a warm device. A material that faded under load would look like a rendering fault, and this
 *    theme's whole point is that it looks the same on every panel. The host takes the frame cost.
 *
 * Every one of those is a *degree*, not a recipe: the host composites, budgets frames and reads the
 * device's own capabilities. See the host's `PluginGlass`.
 *
 * ### The Control Center
 *
 * `intensitySetting = "glass_intensity"` names a float this plugin declares in its own `settings`,
 * which is what turns that slider into the app's Glass Control Center: the host reads the setting
 * live and scales transparency, gloss and blur radius together, so the user's finger moves the app
 * in front of them. It needs no extra permission and no code — but it *does* need `STORE_DATA`,
 * because the value lives in the plugin's own isolated store and the write goes through the same
 * gated, audited door every other setting does.
 *
 * The default is 0.7 rather than 1.0: the theme is designed a little heavier than most people want
 * and the slider moves *down* from there, which is the direction that is always available.
 *
 * ### Palette → the six theme roles
 * The host repaints itself from six colours plus this material (see the host's `PluginTheme`). The
 * brief's iOS dark system palette maps onto them; the colours that have no theme role — the
 * semantic accent SET (green/red/orange/yellow), the raised-row tone #2C2C2E and the separator —
 * have no home in a six-role model (only one [accent] exists and it merely SEEDS Material's derived
 * roles), so they are reported as gaps, not faked. Every enforced text/fill pair clears WCAG AA
 * (4.5:1); the two ink pairs clear AAA:
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
 * ### Remaining format gaps (closest supported option used; see the task report)
 *  - **Light + dark variants** cannot both ship in one plugin — `dark` is a single boolean and a
 *    theme carries ONE palette. The gaming-first dark palette ships; the light palette would need a
 *    separate sibling plugin.
 *  - **Continuous/squircle corners and per-component radii** (cards 16-20, sheets 28, capsule
 *    buttons) are not expressible — there is one global `cornerRadiusDp` that scales a circular
 *    `RoundedCornerShape` ladder. `cornerRadiusDp = 20` gives an iOS-round card (house medium
 *    16→20) and sheet (extraLarge 28→35); capsule buttons would need a radius that over-rounds
 *    every component, so they are a reported gap. The glass material rounds with the same ladder.
 *  - **SF Pro / Inter and the whole type scale** (Large Title 34 ... Caption 12), weights and
 *    tabular numerals cannot ship — `font` is a single family enum and the loader admits no font
 *    file. `rounded` is chosen because the host maps it to `FontFamily.SansSerif` (the device's
 *    clean neutral sans, nearest to SF Pro / Inter) and the plugin screen labels it by the family
 *    it actually gets. No font file ships, so there is no OFL obligation.
 *  - **The accent SET, segmented controls, grouped inset lists and grabber sheets** have no tokens
 *    → omitted, not faked.
 *  - **"Reduce transparency"** has no counterpart; the nearest thing the host offers is the user's
 *    own Animations setting (which stops the sheen and the settle) and the Control Center's slider,
 *    which reaches the same end from the other direction. It is not claimed as an accessibility
 *    equivalent.
 *  - `uiScale` stays 1.0: it is a global density multiplier on top of the user's own accessibility
 *    setting, not a way to add iOS's airy spacing (which is layout, not density), so the user's
 *    density is left untouched rather than scaled.
 */
fun liquidGlassGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.liquidglass",
    name = "Liquid Glass GameCore",
) {
    version = 3
    versionName = "1.2.0"
    // `api` and `minHostVersion` are left at their SDK defaults, and the `glass {}` block below is
    // what raises the second one: the builder lifts it to Sdk.HOST_VERSION_CODE_GLASS the moment a
    // theme asks for a material. That matters because a host older than that parses `effects.glass`
    // as an unknown key and refuses the whole theme — so the bump is what turns the failure into
    // "not installable yet", an honest verdict on the plugin screen, rather than a theme that
    // installs and then silently reverts the app to GameCore's own colours.
    developer = "Dream"
    description = "iOS-inspired dark glass theme: a translucent glossy material, iOS spring motion " +
        "and a Control Center slider for how much of it the app wears."
    changelog = "1.2.0 — the theme now asks for the material it is named after: a translucent, " +
        "glossed surface with a drifting highlight that settles on an iOS-style spring, plus an " +
        "Intensity control in the Glass Control Center. Needs GameCore 3.16 or newer; on Android 11 " +
        "and below the panels are translucent, rimmed and glossed against a sharp backdrop, because " +
        "backdrop blur is an Android 12 feature."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for
    // The intensity control's own home. A setting lives in the plugin's isolated store, and every
    // read and write of it goes through the same gated door — so without this grant the Control
    // Center's slider would have nothing it was allowed to move.
    request(Permissions.STORE_DATA)

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

        // Schema 4. Everything here is a degree; the host owns the pixels, the frame budget and what
        // this device can actually do. See the file header for why each number is what it is.
        glass {
            intensity = 0.7
            transparency = 0.62
            gloss = 0.55
            blurRadiusDp = 16
            sheen = true
            sheenPeriodMs = 9000
            spring = GlassSprings.SNAPPY
            hardwareReactive = false
            intensitySetting = GLASS_INTENSITY_SETTING
        }
    }

    // The one control this plugin has. `intensitySetting` above names it, and the package refuses to
    // build if it does not — so the slider the Control Center draws and the value the material is
    // scaled by are the same declaration by construction.
    settings {
        float(
            GLASS_INTENSITY_SETTING,
            default = 0.7,
            min = 0.0,
            max = 1.0,
        )
    }

    // The launcher icon this plugin asks for: one of the slots GameCore ships,
    // switched in Settings and outranked by the user's own choice the moment they pick one.
    icon(slot = IconSlots.MONO)
}

/** The id of the setting the material's intensity is read from. Named once, used twice. */
private const val GLASS_INTENSITY_SETTING = "glass_intensity"