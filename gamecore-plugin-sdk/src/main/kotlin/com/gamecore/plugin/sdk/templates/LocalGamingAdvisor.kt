package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Actions
import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.RuleOperators
import com.gamecore.plugin.sdk.RuleVariables
import com.gamecore.plugin.sdk.RulesBuilder
import com.gamecore.plugin.sdk.Sdk
import com.gamecore.plugin.sdk.Triggers
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin
import org.json.JSONObject

/**
 * Local Gaming Advisor — a read-only coach built from your own device readings.
 *
 * This is a data-only plugin: there is no plugin code on the device, only declarative JSON the host
 * renders and runs. The advisor's whole job is to *report* — it reads the performance and thermal
 * figures you have granted and lays them out as advice. It never writes a device setting on its own.
 * The one thing it can *do* is ask the host's automation broker to ease the device when it runs hot,
 * and that is an unattended rule you can switch off, not a button this plugin presses.
 *
 * Four sections make that up:
 *
 *  - **screens** — an "Advice" screen pairing live FPS/CPU/temperature readouts with plain guidance,
 *    and a "Reference" screen of recommended thresholds. Both are read-only; there is no apply
 *    button, because a plugin screen cannot write anything.
 *  - **widgets** — two dashboard tiles authored through the DSL (a frame-rate tile and a CPU/GPU load
 *    tile) plus one live-chart tile. The chart is the reason this template reaches for [section]:
 *    the DSL's `chart` leaf is literal-only, so a chart that names a *live* host feed is emitted by
 *    hand through [advisorTrendWidget] with `org.json`, reusing the existing [Sdk.WIDGET_FORMAT] and
 *    [Sdk.WIDGET_VERSION] symbols rather than inventing a builder for it.
 *  - **settings** — the thresholds the advice is phrased against (warn/critical temperature, target
 *    frame rate, a smoothing factor, a detail level and a widget toggle). These are host-owned
 *    controls behind `STORE_DATA`; the plugin reads what the user sets, it does not render inputs.
 *  - **rules** — two unattended automations whose actions are copied verbatim from the broker's own
 *    vocabulary: a thermal cooldown and a battery saver. Nothing here fires without the trigger the
 *    host raises.
 *
 * Honest limits. The advice is read-only and the figures are the host's, sampled only for the scopes
 * granted. The temperature chart is live *only* when the `thermals` scope is granted; without it the
 * host draws the literal sample series shipped in the file. The cooldown automation nudges the device
 * through the broker — it does not, and this plugin cannot, tune the hardware directly.
 */
fun localGamingAdvisor(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.advisor",
    name = "Local Gaming Advisor",
) {
    version = 2
    versionName = "1.1.0"
    developer = "Dream"
    description = "Read-only gaming advice drawn from your own device's performance and thermal " +
        "readings, with an optional cooldown automation you stay in control of. It reports; it " +
        "does not tune your device for you."
    changelog = "Initial release."

    // Six permissions. CHANGE_UI + ADD_DASHBOARD_WIDGETS carry the screens and the dashboard tiles;
    // READ_APPROVED_DATA opens exactly the two apertures the readings ride behind and no more;
    // STORE_DATA backs the settings; RUN_AUTOMATIONS + SYSTEM_ACTIONS let the cooldown rule reach the
    // broker. The data scopes are the narrowest that cover what is drawn: device_stats for FPS/CPU/GPU
    // and thermals for the temperature readout and the live chart.
    request(Permissions.CHANGE_UI)
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.STORE_DATA)
    request(Permissions.RUN_AUTOMATIONS)
    request(Permissions.SYSTEM_ACTIONS)
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    screens {
        screen("advice", "Advice", icon = OverlayIcons.INSIGHTS) {
            column(gap = 12, padding = 16) {
                text("Local Gaming Advisor", size = 20, bold = true)
                text(
                    "Guidance drawn from your own device readings. Everything here is read-only — " +
                        "the advisor reports, it never changes a setting for you.",
                    size = 14,
                )
                card(background = "#14181F", padding = 12, gap = 8) {
                    text("Live readings", size = 13, bold = true)
                    row(gap = 12) {
                        text("Frame rate")
                        stat(OverlayStats.FPS)
                    }
                    row(gap = 12) {
                        text("CPU load")
                        stat(OverlayStats.CPU)
                    }
                    row(gap = 12) {
                        text("Temperature")
                        stat(OverlayStats.TEMP)
                    }
                }
                text(
                    "When the device runs hot, the cooldown automation eases the refresh rate and " +
                        "turns on battery saver to let it recover. Switch it off any time under " +
                        "Settings.",
                    size = 13,
                )
            }
        }
        screen("reference", "Reference", icon = OverlayIcons.THERMOSTAT) {
            column(gap = 12, padding = 16) {
                text("Recommended thresholds", size = 18, bold = true)
                grid(columns = 2, gap = 8) {
                    text("Warn temperature")
                    text("42 C")
                    text("Critical temperature")
                    text("48 C")
                    text("Target frame rate")
                    text("60 fps")
                }
                text(
                    "Starting points for a mid-range phone. Tune them to your own device under " +
                        "Settings.",
                    size = 13,
                )
            }
        }
    }

    widgets {
        widget("advisor_fps", "Frame rate", WidgetSizes.SMALL) {
            column(gap = 4, padding = 12) {
                text("Frame rate", size = 12)
                stat(OverlayStats.FPS, size = 22)
            }
        }
        widget("advisor_load", "System load", WidgetSizes.MEDIUM) {
            column(gap = 6, padding = 12) {
                row(gap = 8) {
                    text("CPU", size = 12)
                    stat(OverlayStats.CPU, size = 16)
                }
                row(gap = 8) {
                    text("GPU", size = 12)
                    stat(OverlayStats.GPU, size = 16)
                }
            }
        }
    }

    // The live-chart widget. The DSL's chart() leaf is literal-only (it takes `values`, never a
    // `stat`), so a chart that names one of the host's live feeds is emitted by hand here through
    // section(), which writes the bytes verbatim and declares the "widgets" section without a second
    // schema pass. The host dashboard is the one surface that resolves a chart's stat/stat2 live
    // series (the overlay surface refuses one) and folds it into the widget's required scopes, so the
    // `thermals` grant is what makes this plot live; absent it, the host draws the literal `values`.
    section("widgets/advisor_trend.json", "widgets", advisorTrendWidget())

    settings {
        int("warn_temp_c", default = 42, min = 30, max = 95)
        int("critical_temp_c", default = 48, min = 30, max = 99)
        int("target_fps", default = 60, min = 30, max = 240)
        float("trend_smoothing", default = 0.5, min = 0.0, max = 1.0)
        enum(
            "advice_detail",
            options = listOf("brief", "standard", "verbose"),
            default = "standard",
        )
        bool("show_trend_widget", default = true)
    }

    rules {
        // Thermal cooldown: when the host raises THERMAL_HIGH and the temperature is at or above
        // 45 C, ask the broker to drop the minimum refresh rate and enable low power. Both action ids
        // are the broker's own, copied verbatim from the performance plugin's rules.
        rule(
            "thermal_cooldown",
            Triggers.THERMAL_HIGH,
            actions = listOf(Actions.SET_MIN_REFRESH_RATE, Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0),
            ),
        )
        // Battery saver: when the battery falls to 15% or below on a BATTERY_LOW trigger, enable low
        // power. One action, one condition — the smallest useful unattended rule.
        rule(
            "battery_saver",
            Triggers.BATTERY_LOW,
            actions = listOf(Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.BATTERY, RuleOperators.LTE, 15.0),
            ),
        )
    }
    // The launcher icon this plugin asks for: one of the slots GameCore ships,
    // switched in Settings and outranked by the user's own choice the moment they pick one.
    icon(slot = IconSlots.DEFAULT)
}

/**
 * The one live-chart widget, built by hand because the DSL cannot build it.
 *
 * `OverlayNodeScope.chart` is literal-only: it accepts a `values` series but no `stat`, so it can
 * never name a live host feed. A dashboard chart may name one — the host resolves `stat`/`stat2`
 * against its stat vocabulary and folds the series into the widget's required scopes — so the only
 * way to author one from the SDK is to emit the widget JSON directly. This does that with `org.json`
 * and the existing [Sdk.WIDGET_FORMAT] / [Sdk.WIDGET_VERSION] symbols; no new builder or constant is
 * introduced.
 *
 * The node carries both a live series and a literal fallback on purpose. `stat` = [OverlayStats.TEMP]
 * is the live temperature feed, which the host plots when the `thermals` scope is granted; `values`
 * is a sample series the host draws instead when it is not, so the tile is never blank. `min`/`max`
 * fix the axis in degrees Celsius and `color` tints the line. The emitted file is `widget.json`'s
 * shape exactly, so [com.gamecore.plugin.sdk.PluginPackager] writes it unchanged and the host's
 * dashboard codec accepts it as a live-series chart.
 */
private fun advisorTrendWidget(): ByteArray {
    val props = JSONObject()
        .put("kind", ChartKinds.LINE)
        .put("stat", OverlayStats.TEMP)
        .put("values", "38,40,41,43,44,46,47,46,48,49,50,48")
        .put("min", 20)
        .put("max", 70)
        .put("label", "Device temperature")
        .put("note", "Live when thermals is granted; sample shown otherwise.")
        .put("color", "#FF8A65")
    val content = JSONObject()
        .put("type", "chart")
        .put("props", props)
    return JSONObject()
        .put("format", Sdk.WIDGET_FORMAT)
        .put("version", Sdk.WIDGET_VERSION)
        .put("id", "advisor_trend")
        .put("title", "Temperature trend")
        .put("size", WidgetSizes.FULL)
        .put("content", content)
        .toString(2)
        .toByteArray()
}


