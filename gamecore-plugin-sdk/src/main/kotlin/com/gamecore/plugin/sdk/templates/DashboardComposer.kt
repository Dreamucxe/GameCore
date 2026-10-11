package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin

/**
 * "Dashboard Composer" — a deck of composable, read-only cards for GameCore's home dashboard.
 *
 * This is a worked authoring example that lives *beside* [Templates] in its own file, so it can be
 * added without editing the shared template library. It carries **data only**: a set of
 * `widgets/<id>.json` files the host interprets with its own Compose renderer. Nothing written here
 * executes — a widget "runs" by being drawn, never by calling back into a plugin.
 *
 * ### What it ships
 * A varied deck of eight widgets, mixing every [WidgetSizes] span and every read-only node shape a
 * widget may carry:
 *  - **Live stat tiles** — `fps-tile` (SMALL), `thermal-tile` (SMALL), `perf-row` (LARGE) and the
 *    representative `vitals-grid` (MEDIUM) read GameCore's own approved figures through `stat` nodes.
 *  - **Literal charts** — `fps-history` (FULL, line) and `load-bars` (LARGE, bar) plot the numbers
 *    they ship with. The DSL's `chart()` is literal-only by construction, so a dashboard chart here
 *    shows sample data, not a live feed.
 *  - **Text cards** — `welcome-card` (MEDIUM) and `legend-note` (SMALL) label the deck and ask for
 *    no data at all.
 *
 * ### Permissions → the two apertures the live tiles read
 * [Permissions.ADD_DASHBOARD_WIDGETS] registers the cards; [Permissions.READ_APPROVED_DATA] opens
 * the two data scopes the deck's live stats need, which is the **union** of every `stat` node's
 * [OverlayStats.scopeFor]:
 *  - [DataScopes.DEVICE_STATS] — `fps`, `cpu`, `gpu`, `ram`
 *  - [DataScopes.THERMALS]    — `battery`, `temp`
 *
 * The manifest `api` is raised to 3 automatically by the `widgets { }` section (a 3.x host feature),
 * and `minHostVersion` keeps the SDK default (28).
 *
 * ### Honest limits (stated, not worked around)
 *  - **The dashboard is a host feature; this plugin only contributes cards.** A widget is read-only
 *    *by construction*: [com.gamecore.plugin.sdk.WidgetBuilder] carries the overlay component set, so
 *    `button`, `slider` and `switch` are not functions an author can call. There are no "inert
 *    controls" here — there are simply no controls, which is the whole contract of the surface.
 *  - **Live tiles fill in only where the scope is granted.** A `stat` card whose data scope the user
 *    declines is drawn empty by the host rather than failing to load; the literal charts always draw,
 *    because their numbers travel in the file.
 *  - **Full deck vs one representative.** The sideloaded `.gcplugin` carries the FULL deck — one
 *    `widgets/<id>.json` per card. The copy GameCore bundles in its APK carries ONE representative
 *    card (`vitals-grid`, flattened to a single `widgets.json`), because the host's bundled-asset
 *    path reads exactly one `widgets.json` per section where the zip path reads the whole `widgets/`
 *    directory. That asymmetry is intentional and documented — not a missing feature.
 */
fun dashboardComposer(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.dashboardcomposer",
    name = "Dashboard Composer",
) {
    version = 1
    versionName = "1.0.0"
    // minHostVersion keeps the SDK default (28). `api` is NOT set here: the widgets { } section
    // raises it to Sdk.API_V3 (3) automatically, so the manifest and the feature can never disagree.
    developer = "Dream"
    description = "A deck of read-only cards for GameCore's home dashboard - live performance tiles " +
        "plus sample charts you pick and arrange yourself."
    changelog = "Initial release."

    // ADD_DASHBOARD_WIDGETS registers the cards; READ_APPROVED_DATA opens the two apertures the live
    // tiles read. The scope set is the UNION of every live stat in the deck - a card whose scope the
    // user declines renders empty, which is the honest behaviour, not a failure.
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )

    widgets {
        // 1 — SMALL live tile: a single big frame-rate readout. Scope: device_stats.
        widget("fps-tile", "Frame rate", WidgetSizes.SMALL) {
            card(padding = 12) {
                column(gap = 2, align = "center") {
                    text("FPS", color = "#99FFFFFF", size = 11)
                    stat(OverlayStats.FPS, size = 28)
                }
            }
        }

        // 2 — MEDIUM 2x2 vitals grid. The REPRESENTATIVE card: it needs BOTH scopes
        //     (device_stats for cpu/gpu/ram, thermals for temp), so it is the one the bundled
        //     copy ships and the one whose appetite exercises the whole requested union.
        widget("vitals-grid", "Vitals", WidgetSizes.MEDIUM) {
            grid(columns = 2, gap = 10, padding = 12) {
                column(gap = 2) {
                    text("CPU", color = "#99FFFFFF", size = 11)
                    stat(OverlayStats.CPU, size = 20)
                }
                column(gap = 2) {
                    text("GPU", color = "#99FFFFFF", size = 11)
                    stat(OverlayStats.GPU, size = 20)
                }
                column(gap = 2) {
                    text("RAM", color = "#99FFFFFF", size = 11)
                    stat(OverlayStats.RAM, size = 20)
                }
                column(gap = 2) {
                    text("Temp", color = "#99FFFFFF", size = 11)
                    stat(OverlayStats.TEMP, size = 20)
                }
            }
        }

        // 3 — SMALL thermals tile: an icon beside battery and temperature. Scope: thermals.
        widget("thermal-tile", "Thermals", WidgetSizes.SMALL) {
            card(padding = 12) {
                row(gap = 10, align = "center") {
                    image(OverlayIcons.THERMOSTAT, size = 20)
                    column(gap = 2) {
                        text("Battery", color = "#99FFFFFF", size = 11)
                        stat(OverlayStats.BATTERY, size = 18)
                    }
                    column(gap = 2) {
                        text("Temp", color = "#99FFFFFF", size = 11)
                        stat(OverlayStats.TEMP, size = 18)
                    }
                }
            }
        }

        // 4 — FULL literal line chart: a frame-rate sparkline drawn from the numbers it ships with.
        widget("fps-history", "Frame rate history", WidgetSizes.FULL) {
            column(gap = 6, padding = 12) {
                text("Last 20 ticks", size = 12)
                chart(
                    values = listOf(58, 60, 59, 61, 60, 62, 60, 59, 60, 61, 60, 58, 57, 60, 62, 61, 60, 60, 59, 60),
                    kind = ChartKinds.LINE,
                    min = 0,
                    max = 90,
                    label = "fps",
                    note = "Sample data - a widget chart carries the numbers it ships with.",
                )
            }
        }

        // 5 — LARGE literal bar chart: two sample series compared side by side.
        widget("load-bars", "Load split", WidgetSizes.LARGE) {
            column(gap = 6, padding = 12) {
                text("Typical load split", size = 12)
                chart(
                    values = listOf(45, 52, 60, 55, 48),
                    kind = ChartKinds.BAR,
                    values2 = listOf(70, 75, 82, 78, 72),
                    min = 0,
                    max = 100,
                    label = "CPU %",
                    label2 = "GPU %",
                )
            }
        }

        // 6 — MEDIUM text card: a label-only tile that asks for no data at all.
        widget("welcome-card", "Welcome", WidgetSizes.MEDIUM) {
            card(padding = 14) {
                column(gap = 6) {
                    text("Dashboard Composer", size = 15)
                    text(
                        "A deck of read-only cards for your home dashboard. Pick the ones you want - each is a bounded tile.",
                        color = "#CCFFFFFF",
                        size = 12,
                    )
                }
            }
        }

        // 7 — LARGE live stat row: three figures across one wide tile. Scope: device_stats.
        widget("perf-row", "Performance", WidgetSizes.LARGE) {
            card(padding = 12) {
                row(gap = 16, align = "center") {
                    column(gap = 2, align = "center") {
                        text("FPS", color = "#99FFFFFF", size = 11)
                        stat(OverlayStats.FPS, size = 20)
                    }
                    column(gap = 2, align = "center") {
                        text("CPU", color = "#99FFFFFF", size = 11)
                        stat(OverlayStats.CPU, size = 20)
                    }
                    column(gap = 2, align = "center") {
                        text("RAM", color = "#99FFFFFF", size = 11)
                        stat(OverlayStats.RAM, size = 20)
                    }
                }
            }
        }

        // 8 — SMALL caption: the honest note, read-only and data-free.
        widget("legend-note", "About", WidgetSizes.SMALL) {
            card(padding = 12) {
                row(gap = 8, align = "center") {
                    image(OverlayIcons.INFO, size = 16)
                    text(
                        "Live tiles fill in when you grant data access; charts show sample data.",
                        color = "#CCFFFFFF",
                        size = 11,
                    )
                }
            }
        }
    }
}
