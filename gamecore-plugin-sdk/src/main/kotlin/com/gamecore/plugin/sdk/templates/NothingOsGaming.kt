package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Actions
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.RuleOperators
import com.gamecore.plugin.sdk.RuleVariables
import com.gamecore.plugin.sdk.RulesBuilder
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.Triggers
import com.gamecore.plugin.sdk.plugin

/**
 * "Nothing OS Gaming" — a stark monochrome theme that reserves a single Nothing-style red for the
 * accent, paired with two broker-driven automations that trade frames for battery the way a gaming
 * phone's own OS would.
 *
 * Like [cyberpunkGameCore] this lives *beside* [Templates] in its own file so it can be added without
 * editing the shared template library. It carries **data only**: three permission grants, one v2
 * `theme.json`, and one `rules/rules.json`. There is no code path from here into the running app —
 * the host repaints itself from the theme and runs the rules through its gated action broker, and
 * this file never holds a shell, a callback or a byte of native.
 *
 * ### Why a schema-2 theme (api 2)
 * The one visual decision that defines Nothing's look is *true black*, not a dark grey — the OS leans
 * on an AMOLED panel's ability to switch a pixel fully off. [theme.amoled][com.gamecore.plugin.sdk.ThemeBuilder.amoled]
 * = true asks the host for exactly that true-black base, and because `amoled` is a schema-2 feature the
 * DSL stamps `schema: 2` into the theme and raises the manifest `api` to 2 on its own (see
 * [com.gamecore.plugin.sdk.PluginBuilder.theme]). We do not — and must not — set `api` by hand; it is
 * reached automatically, and a build that predates schema 2 would read these keys as unknown and reject
 * the whole theme, which is exactly what the bump prevents.
 *
 * ### Palette → the six v1 colours
 * Monochrome by construction: every surface and every ink is a pure grey, so the only chromatic pixel
 * in the whole scheme is the accent. Every mapped text/fill pair clears WCAG AA (4.5:1) with room to
 * spare:
 *
 *  - body text (`onSurface` #F2F2F2) on `surface` #0A0A0A   → 17.7:1 (AAA)
 *  - body text on true-black `background` #000000           → 18.8:1 (AAA)
 *  - ink (`onPrimary` #0A0A0A) on near-white `primary`      → 16.9:1 (AAA)
 *
 * `primary` is the near-white, because the hero surface of a monochrome UI is light-on-dark text and
 * fills, **not** the red. The red is the [accent] alone.
 *
 * ### The accent, and why the v2 `roles` block exists
 * `accent` = #D71921 (a Nothing-style red) is where every drop of colour in the scheme is spent. Left
 * to itself the host derives Material's *secondary* and *tertiary* from that accent, which would bleed
 * the red back into chips, toggles and secondary fills — the opposite of "red as a reserved accent".
 * So the schema-2 [roles][com.gamecore.plugin.sdk.ThemeRolesBuilder] block pulls those derived roles
 * back to pure greys, keeping the red for the places that earn it. `error` is the one role that *keeps*
 * the red on purpose: an alert is exactly what a lone red is for, and white ink on it clears AA (5.19:1).
 *
 *  - `secondary` #C8C8C8 / `tertiary` #8A8A8A — monochrome, so the accent never leaks into them
 *  - `surfaceVariant` #141414 / `onSurfaceVariant` #B5B5B5 — a mid grey tier (text pair 9.0:1, AAA)
 *  - `outline` #2A2A2A — the dim hairline dividers the look is built on
 *  - `error` #D71921 / `onError` #FFFFFF — the brand red doubles as the alert colour (white ink 5.19:1)
 *
 * ### Typography
 * [font][com.gamecore.plugin.sdk.ThemeFonts.MONO] is the nearest bundled face to Nothing's dot-matrix
 * display type (which cannot be shipped — see the limits below), and the schema-2
 * [typography][com.gamecore.plugin.sdk.ThemeTypographyBuilder] block sets one global `medium` weight so
 * the whole ramp reads at the single, even, technical weight the mono face wants. The type *scale* is
 * left at its default (so no `scale` key is emitted) — this theme leans the UI, it does not resize it.
 *
 * ### The two automations
 * Both name only actions the host's broker already enumerates (copied from the shipped
 * `performance/rules.json`); a rule names an action, it never carries one, so the plugin reaches
 * nothing the broker does not already gate, clamp and confirm.
 *
 *  - `game_launch_boost` — on [Triggers.GAME_LAUNCHED], ask for peak refresh and the three
 *    animation-scale actions (the "snappy, no-animation-lag" launch a gaming OS makes).
 *  - `battery_saver` — on [Triggers.BATTERY_LOW], *and only when* battery ≤ 15, ask for low-power mode.
 *    The condition's variable is named from [RuleVariables], not spelled by hand: the host does not
 *    resolve rule variables, so a typo there would install a rule that silently never fires.
 *
 * ### Honest limits (closest supported option used; no faked capability)
 * This is a monochrome visual theme plus two host automations, and nothing more. The data-only host
 * has no surface for Nothing's signature hardware, so none of it is driven or implied:
 *  - **No Glyph LEDs.** There is no plugin API that addresses the rear Glyph lights, so they are untouched.
 *  - **No Ndot / dot-matrix font.** A theme names one of GameCore's four bundled faces; it cannot ship a
 *    font file, so `mono` is the nearest face, not the real Nothing display type.
 *  - **No Nothing-brand hardware, logo or trademarked marks** are shipped or required — the red is a
 *    plain `#D71921` fill, named here only to describe the palette, and no artwork of any kind is bundled.
 */
fun nothingOsGaming(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.nothingos",
    name = "Nothing OS Gaming",
) {
    version = 2
    versionName = "1.1.0"
    // api is NOT set here: the amoled v2 theme below raises it to 2 automatically, and
    // minHostVersion keeps the SDK default (Sdk.HOST_VERSION_CODE, 28).
    developer = "Dream"
    description = "Monochrome true-black theme with a reserved Nothing-style red accent, " +
        "plus in-game performance and battery-saver automations. No Glyph, Ndot font or Nothing hardware."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)        // gates the theme
    request(Permissions.RUN_AUTOMATIONS)  // gates the rules
    request(Permissions.SYSTEM_ACTIONS)   // gates the broker actions those rules name
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    theme {
        name = "Nothing OS Gaming"
        dark = true
        amoled = true             // true-black base; this is the schema-2 feature that bumps api to 2
        primary = "#EDEDED"       // near-white — hero fills, selected states, light-on-dark text
        onPrimary = "#0A0A0A"     // near-black ink on near-white (16.9:1)
        background = "#000000"    // true black, for the AMOLED panel to switch off
        surface = "#0A0A0A"       // raised panels, a hair off black
        onSurface = "#F2F2F2"     // body text (17.7:1 on surface, 18.8:1 on background)
        accent = "#D71921"        // the one chromatic colour: a Nothing-style red, reserved for accents
        cornerRadiusDp = 4        // sharp, minimal corners
        font = ThemeFonts.MONO    // nearest bundled face to Nothing's dot-matrix display type
        uiScale = 1.0

        // v2 roles: keep the derived accents monochrome so the red never leaks out of [accent],
        // and let the brand red double as the alert colour.
        roles {
            secondary = "#C8C8C8"        // light grey — secondary UI stays monochrome
            tertiary = "#8A8A8A"         // mid grey — tertiary stays monochrome
            surfaceVariant = "#141414"   // a layered grey tier above surface
            onSurfaceVariant = "#B5B5B5" // muted grey for secondary text/icons (9.0:1 on surfaceVariant)
            outline = "#2A2A2A"          // dim hairline dividers, the Nothing-style grid
            error = "#D71921"            // the red is exactly what an alert wants
            onError = "#FFFFFF"          // white ink on red (5.19:1)
        }
        // v2 typography: one even, technical weight across the ramp to suit the mono face.
        typography {
            weight = ThemeWeights.MEDIUM
        }
    }

    rules {
        // Snappy launch: peak refresh and the three animation-scale actions, as the shipped
        // performance pack does on GAME_LAUNCHED. No conditions — it fires on every launch.
        rule(
            id = "game_launch_boost",
            trigger = Triggers.GAME_LAUNCHED,
            actions = listOf(
                Actions.SET_PEAK_REFRESH_RATE,
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
        )
        // Battery saver: low-power mode, but only once the charge is genuinely low. The variable is
        // named from RuleVariables so the host's unresolved-variable trap cannot swallow it silently.
        rule(
            id = "battery_saver",
            trigger = Triggers.BATTERY_LOW,
            actions = listOf(Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.BATTERY, RuleOperators.LTE, 15.0),
            ),
        )
    }
    // The launcher icon this plugin asks for: one of the slots GameCore ships,
    // switched in Settings and outranked by the user's own choice the moment they pick one.
    icon(slot = IconSlots.MONO)
}
