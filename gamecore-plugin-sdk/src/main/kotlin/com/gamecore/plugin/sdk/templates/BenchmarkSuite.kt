package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlaySizes
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.Sdk
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin
import org.json.JSONObject

/**
 * "Benchmark Suite" — a read-only telemetry *visualizer* for the figures GameCore already samples.
 *
 * A worked authoring example that lives beside [Templates] in its own file, so it can be added
 * without editing the shared template library. It carries **data only**: three declarative sections
 * (`pill`, `widgets`, `screens`) and the four grants that gate them. Nothing here executes — the DSL
 * emits JSON the host interprets with its own Compose renderer, exactly as it does a theme.
 *
 * ### The honest limit, stated plainly
 * **This plugin is a visualizer, not a benchmark runner.** It runs no workload and no stress test, it
 * computes no synthetic score, and it stores nothing. All it does is draw the readings the host's own
 * performance feed publishes — frame rate, CPU and GPU load, and temperature — in three places: an
 * in-game overlay, a dashboard snapshot, and a rolling line chart. Those readings are *live* only when
 * the user has granted the data scopes below **and** a session is actually feeding them; with no grant
 * or no active feed every figure reads empty (a dashboard tile shows nothing, a screen stat an em dash).
 * The chart additionally ships a short literal series as the fallback a host with no live resolver draws.
 *
 * ### Sections, and the permission each one needs
 *  - **pill** — a telemetry badge over the game, built from live `stat` nodes (never a chart: the host's
 *    overlay audit refuses a live chart series outright). Needs [Permissions.CREATE_OVERLAY].
 *  - **widgets** — two cards authored through the DSL (a live-stat snapshot and a plain-text "about"),
 *    plus one **live-chart** widget hand-emitted through [com.gamecore.plugin.sdk.PluginBuilder.section]:
 *    the DSL's `chart()` is literal-only by construction, so naming a live series (`stat = fps`) is the
 *    one thing it cannot author and the raw file carries. Needs [Permissions.ADD_DASHBOARD_WIDGETS].
 *  - **screens** — a "visualizer" readout screen and an "about" screen, both inert this phase. Needs
 *    [Permissions.CHANGE_UI].
 *
 * The live figures ride behind [Permissions.READ_APPROVED_DATA], scoped to exactly the two apertures the
 * stats need: [DataScopes.DEVICE_STATS] (fps/cpu/gpu) and [DataScopes.THERMALS] (temp). The scope set is
 * the union of what every section's stat nodes ask for, so each section's required scopes are a subset of
 * what the manifest requests — the invariant the host registry gates every section on.
 *
 * ### api level
 * `widgets` and `screens` are API-3 sections, so each raises the manifest `api` to [Sdk.API_V3] (3). The
 * pill does **not** bump it: a pill only reaches API-3 for a chart node or a motion reading, and this one
 * draws neither. `minHostVersion` stays at the SDK default ([Sdk.HOST_VERSION_CODE], 28).
 */
fun benchmarkSuite(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.benchmark",
    name = "Benchmark Suite",
) {
    version = 1
    versionName = "1.0.0"
    // api becomes 3 via the widgets{} and screens{} sections; minHostVersion keeps the default (28).
    developer = "Dream"
    description = "A read-only visualizer for GameCore's performance feed — live FPS, CPU, GPU and " +
        "temperature as an overlay, dashboard widgets and a rolling chart. It runs no benchmark " +
        "workload and computes no score."
    changelog = "Initial release."

    // One grant per section, plus the single scoped read that unlocks every live figure the three
    // sections draw. device_stats covers fps/cpu/gpu; thermals covers temp.
    request(Permissions.CREATE_OVERLAY)
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_UI)

    // ── pill ──────────────────────────────────────────────────────────────────────────────────
    // A telemetry badge over the game: one card holding a four-column row, each column a label over
    // a live stat node (fps/cpu/gpu/temp). It carries no chart and no motion reading, so the pill
    // never bumps the manifest api on its own — it stays API-2, and widgets{}/screens{} are what
    // carry this plugin to api 3. The overlay audit would refuse a live chart series here outright.
    pill {
        id = "bench-telemetry"
        title = "Benchmark telemetry"
        anchor(x = 0.5, y = 0.08)
        size = OverlaySizes.LARGE
        card(padding = 10, background = "#CC0B0F17") {
            row(gap = 14, align = "center") {
                column(gap = 2, align = "center") {
                    text("FPS", color = "#8AA0B4", size = 10)
                    stat(OverlayStats.FPS, size = 16, color = "#E6F1FF")
                }
                column(gap = 2, align = "center") {
                    text("CPU", color = "#8AA0B4", size = 10)
                    stat(OverlayStats.CPU, size = 16, color = "#E6F1FF")
                }
                column(gap = 2, align = "center") {
                    text("GPU", color = "#8AA0B4", size = 10)
                    stat(OverlayStats.GPU, size = 16, color = "#E6F1FF")
                }
                column(gap = 2, align = "center") {
                    text("TEMP", color = "#8AA0B4", size = 10)
                    stat(OverlayStats.TEMP, size = 16, color = "#E6F1FF")
                }
            }
        }
    }

    // ── widgets ───────────────────────────────────────────────────────────────────────────────
    // Two cards authored through the DSL; the live-chart widget is hand-emitted below.
    widgets {
        // A dashboard snapshot of the same four live readings, two to a row.
        widget("bench_snapshot", "Live snapshot", WidgetSizes.MEDIUM) {
            column(gap = 10, padding = 12) {
                text("Live device telemetry", color = "#CFD3DC", size = 13)
                grid(columns = 2, gap = 10) {
                    column(gap = 2) {
                        text("FPS", color = "#8A90A0", size = 11)
                        stat(OverlayStats.FPS, size = 18)
                    }
                    column(gap = 2) {
                        text("CPU", color = "#8A90A0", size = 11)
                        stat(OverlayStats.CPU, size = 18)
                    }
                    column(gap = 2) {
                        text("GPU", color = "#8A90A0", size = 11)
                        stat(OverlayStats.GPU, size = 18)
                    }
                    column(gap = 2) {
                        text("Temp", color = "#8A90A0", size = 11)
                        stat(OverlayStats.TEMP, size = 18)
                    }
                }
            }
        }
        // A plain-text card stating the honest limit — no stat node, so it needs no data scope.
        widget("bench_about", "About", WidgetSizes.LARGE) {
            column(gap = 6, padding = 12) {
                text("Visualizer, not a benchmark runner", color = "#F2F4F8", size = 14)
                text(
                    "Shows live host telemetry and recent history. It runs no workload and " +
                        "computes no score.",
                    color = "#AEB4C0",
                    size = 12,
                )
            }
        }
    }

    // ── widgets (live chart) ──────────────────────────────────────────────────────────────────
    // The one widget the DSL's literal-only chart() cannot author: a line series named by a live
    // stat (fps), carrying a short literal series as the no-resolver fallback. Hand-emitted as a raw
    // widget section so it declares `widgets` without a second api path. The dashboard chart audit
    // *allows* a live series (unlike the overlay's), validating `stat` against the host's ModuleStat.
    // This file is also the bundled representative the host ships and BenchmarkSuiteBundledTest reads.
    section("widgets/bench_trend.json", "widgets", benchTrendWidgetJson())

    // ── screens ───────────────────────────────────────────────────────────────────────────────
    // A read-only visualizer readout and an "about" page. Both are inert this phase: a screen's
    // stat draws an em dash until the measurement feed lands, which is the honest state said plainly.
    screens {
        screen("visualizer", "Visualizer", icon = OverlayIcons.INSIGHTS) {
            column(gap = 12, padding = 16) {
                text("Performance visualizer", size = 18, bold = true)
                text(
                    "Live readings from GameCore's own performance feed. Grant Device stats and " +
                        "Thermals and they fill during a session; otherwise each reads as a dash.",
                    color = "#AEB4C0",
                    size = 13,
                )
                grid(columns = 2, gap = 12) {
                    card(padding = 12) {
                        text("Frame rate", color = "#8A90A0", size = 12)
                        stat(OverlayStats.FPS, label = "FPS", size = 20)
                    }
                    card(padding = 12) {
                        text("CPU load", color = "#8A90A0", size = 12)
                        stat(OverlayStats.CPU, label = "CPU", size = 20)
                    }
                    card(padding = 12) {
                        text("GPU load", color = "#8A90A0", size = 12)
                        stat(OverlayStats.GPU, label = "GPU", size = 20)
                    }
                    card(padding = 12) {
                        text("Temperature", color = "#8A90A0", size = 12)
                        stat(OverlayStats.TEMP, label = "Temp", size = 20)
                    }
                }
            }
        }
        screen("about", "About", icon = OverlayIcons.INFO) {
            column(gap = 10, padding = 16) {
                text("About Benchmark Suite", size = 18, bold = true)
                text(
                    "A read-only window onto the telemetry GameCore already samples: an in-game " +
                        "overlay, dashboard widgets and a rolling frame-rate chart.",
                    color = "#AEB4C0",
                    size = 13,
                )
                text(
                    "What it does: draws live FPS, CPU, GPU and temperature, plus recent history.",
                    color = "#AEB4C0",
                    size = 13,
                )
                text(
                    "What it does not do: it runs no benchmark workload, computes no score, and " +
                        "stores nothing.",
                    color = "#AEB4C0",
                    size = 13,
                )
            }
        }
    }
}

/**
 * The one widget the DSL cannot author: a dashboard line chart whose series is a **live** stat.
 *
 * [OverlayNodeScope.chart] is literal-only by construction (it has no `stat` parameter), because an
 * overlay chart's live series would be invisible to the overlay's data-appetite walk and the host
 * refuses it. A dashboard chart is the opposite case — its codec validates a `stat` prop against the
 * host's own `ModuleStat` and folds it into the widget's required scopes — so the live series is
 * authored here as raw JSON and attached through [com.gamecore.plugin.sdk.PluginBuilder.section].
 *
 * `stat = fps` makes it live (needs [DataScopes.DEVICE_STATS]); the literal `values` string is the
 * fallback a host with no live resolver draws, and `min`/`max` pin the axis so an empty feed still
 * frames correctly. The emitted keys — `format, version, id, title, size, content` — are exactly the
 * host dashboard codec's known set, and the chart props exactly what its chart reader names.
 */
private fun benchTrendWidgetJson(): ByteArray =
    JSONObject()
        .put("format", Sdk.WIDGET_FORMAT)
        .put("version", Sdk.WIDGET_VERSION)
        .put("id", "bench_trend")
        .put("title", "Frame-rate trend")
        .put("size", WidgetSizes.FULL)
        .put(
            "content",
            JSONObject()
                .put("type", "chart")
                .put(
                    "props",
                    JSONObject()
                        .put("kind", ChartKinds.LINE)
                        .put("stat", OverlayStats.FPS)
                        .put("values", "58,60,59,61,60,62,59,60,61,59")
                        .put("min", 0)
                        .put("max", 120)
                        .put("label", "FPS")
                        .put("note", "Live frame rate when Device stats is granted; sample values until then.")
                        .put("color", "#00E5FF"),
                ),
        )
        .toString(2)
        .toByteArray()
