package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin

/**
 * "GameCore Personal OS" — a home-surface bundle that dresses GameCore as a personal
 * dashboard: a cohesive dark theme, a pair of read-only home screens, four dashboard widgets
 * and a set of typed layout preferences.
 *
 * A worked authoring example beside [Templates], in its own file so it can be added without
 * editing the shared template library. Like every plugin on this platform it carries **data
 * only** — JSON the host interprets with its own Compose renderer. Nothing here executes: no
 * callback, no shell, no file or network access, no plugin code of any kind. A "Personal OS"
 * composes GameCore's own surfaces; it is not a launcher and replaces no part of the real OS.
 *
 * ### What it asks for, and why each grant
 *  - [Permissions.CHANGE_UI] — repaint the app (the `theme`) and draw the declarative `screens`.
 *  - [Permissions.ADD_DASHBOARD_WIDGETS] — place the four cards on the home dashboard.
 *  - [Permissions.STORE_DATA] — keep the typed `settings` in the plugin's own isolated store.
 *  - [Permissions.READ_APPROVED_DATA], scoped to exactly **{device_stats, thermals}** — the union
 *    of the scopes every live `stat` in the screens and widgets reads through. fps/cpu/gpu/ram ride
 *    [DataScopes.DEVICE_STATS]; battery/temp ride [DataScopes.THERMALS]. The manifest requests that
 *    union and nothing wider: the host derives each widget's appetite from its own node tree and
 *    drops any widget needing a scope the user declined, so an over-broad request would name a
 *    reading nothing shows and an under-broad one would silently hide a card.
 *
 * ### Honest limits
 *  - **Live readings need the grant.** A widget `stat` shows a real figure only where its scope is
 *    granted; the registry hides a widget whose scope was declined rather than drawing a blank. The
 *    two stat-free cards (`welcome`, `trend`) always show — they ask for no data.
 *  - **Screens draw stats absent for now.** In this host phase a screen `stat` renders as an em
 *    dash; the live screen feed lands later, and the grant is what lets it fill then.
 *  - **Settings are stored, not self-applied.** The host keeps these typed values and validates
 *    every read/write against them, but a data-only plugin has no runtime to re-read them and
 *    re-lay-out itself — they take effect only as far as the host interprets a given key. They are
 *    an honest declaration of preferences, not a program that rearranges the dashboard on its own.
 *
 * ### Theme -> the six roles (every text/fill pair clears WCAG AA 4.5:1)
 *  - body text (`onSurface` #E8ECF2) on `surface` #171B22 -> ~14.6:1 (AAA)
 *  - body text on `background` #0E1116                     -> ~16.0:1 (AAA)
 *  - ink (`onPrimary` #0B1324) on `primary` #6AA6FF        -> ~7.5:1 (AAA)
 *  `accent` #8B7CFF has no dedicated role; it seeds Material's derived secondary/tertiary.
 */
fun gameCorePersonalOs(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.personalos",
    name = "GameCore Personal OS",
) {
    version = 2
    versionName = "1.1.0"
    // api is raised to Sdk.API_V3 (3) automatically by the screens/widgets/settings sections;
    // minHostVersion keeps the SDK default, Sdk.HOST_VERSION_CODE (28).
    developer = "Dream"
    description =
        "A personal home dashboard: a dark theme, home screens, live-vitals widgets and layout settings."
    changelog = "Initial release."

    // Exactly the four grants the sections below need. The READ_APPROVED_DATA scope set is the
    // union of every live stat's scope and is kept in lockstep with the trees further down.
    request(Permissions.CHANGE_UI)
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(Permissions.STORE_DATA)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    theme {
        name = "GameCore Personal OS"
        dark = true
        primary = "#6AA6FF"      // soft blue — hero fills, selected states
        onPrimary = "#0B1324"    // dark navy ink on blue (~7.5:1)
        background = "#0E1116"   // near-black slate page
        surface = "#171B22"      // raised panels and cards
        onSurface = "#E8ECF2"    // body text (~14.6:1 on surface, ~16.0:1 on background)
        accent = "#8B7CFF"       // soft violet — drives Material's derived secondary/tertiary
        cornerRadiusDp = 20      // soft, rounded home-OS cards
        font = ThemeFonts.ROUNDED
        uiScale = 1.0
    }

    // Two read-only home screens. Every stat reads through the same {device_stats, thermals}
    // union the manifest requests; nothing here introduces a scope the widgets do not also need.
    screens {
        screen("home", "Home", icon = OverlayIcons.INSIGHTS) {
            column(gap = 16, padding = 16) {
                text("Your Personal OS", size = 22, bold = true)
                text("A calm home for your games, vitals and layout.", size = 14, color = "#A9B2C0")
                card(background = "#171B22", padding = 16, gap = 12) {
                    row(gap = 12, align = "center") {
                        image(OverlayIcons.SPEED, size = 20)
                        text("Frame rate")
                        stat(OverlayStats.FPS, label = "now")
                    }
                    row(gap = 12, align = "center") {
                        image(OverlayIcons.BATTERY, size = 20)
                        text("Battery")
                        stat(OverlayStats.BATTERY, label = "now")
                    }
                }
            }
        }
        screen("system", "System", icon = OverlayIcons.SENSORS) {
            column(gap = 16, padding = 16) {
                text("System vitals", size = 20, bold = true)
                grid(columns = 2, gap = 12) {
                    card(background = "#171B22", padding = 14, gap = 6) {
                        text("CPU", size = 12, color = "#A9B2C0")
                        stat(OverlayStats.CPU)
                    }
                    card(background = "#171B22", padding = 14, gap = 6) {
                        text("GPU", size = 12, color = "#A9B2C0")
                        stat(OverlayStats.GPU)
                    }
                    card(background = "#171B22", padding = 14, gap = 6) {
                        text("RAM", size = 12, color = "#A9B2C0")
                        stat(OverlayStats.RAM)
                    }
                    card(background = "#171B22", padding = 14, gap = 6) {
                        text("Temp", size = 12, color = "#A9B2C0")
                        stat(OverlayStats.TEMP)
                    }
                }
            }
        }
    }

    // Four dashboard widgets. "vitals" is the representative one flattened into the bundled
    // widgets.json; it alone exercises both scopes, so its requiredScopes == the requested union.
    widgets {
        widget("vitals", "System vitals", WidgetSizes.FULL) {
            column(gap = 10, padding = 12) {
                text("System vitals", size = 14)
                grid(columns = 2, gap = 8) {
                    card(padding = 10) { row(gap = 8) { text("FPS"); stat(OverlayStats.FPS) } }
                    card(padding = 10) { row(gap = 8) { text("CPU"); stat(OverlayStats.CPU) } }
                    card(padding = 10) { row(gap = 8) { text("RAM"); stat(OverlayStats.RAM) } }
                    card(padding = 10) { row(gap = 8) { text("Temp"); stat(OverlayStats.TEMP) } }
                }
            }
        }
        widget("thermals", "Thermals", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12) {
                text("Thermals", size = 14)
                row(gap = 8) { text("Battery"); stat(OverlayStats.BATTERY) }
                row(gap = 8) { text("Temp"); stat(OverlayStats.TEMP) }
            }
        }
        widget("welcome", "Welcome", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12, align = "center") {
                image(OverlayIcons.INSIGHTS, size = 28)
                text("Welcome to", size = 12, color = "#A9B2C0")
                text("GameCore Personal OS", size = 16)
            }
        }
        widget("trend", "Recent frames", WidgetSizes.FULL) {
            column(gap = 10, padding = 12) {
                text("Recent frames (sample)", size = 12, color = "#A9B2C0")
                chart(
                    values = listOf(58, 60, 59, 61, 60, 62, 60, 61),
                    kind = ChartKinds.LINE,
                    min = 0,
                    max = 90,
                )
            }
        }
    }

    // Typed layout + preference controls, kept in the plugin's own isolated store behind STORE_DATA.
    settings {
        enum("home_layout", options = listOf("grid", "list", "compact"), default = "grid")
        bool("show_vitals", default = true)
        bool("show_thermals", default = true)
        int("widget_columns", default = 2, min = 1, max = 4)
        enum("temperature_units", options = listOf("celsius", "fahrenheit"), default = "celsius")
        string("greeting", default = "Welcome back", maxLength = 48)
    }
    // The launcher icon this plugin asks for: one of the slots GameCore ships,
    // switched in Settings and outranked by the user's own choice the moment they pick one.
    icon(slot = IconSlots.MONO)
}
