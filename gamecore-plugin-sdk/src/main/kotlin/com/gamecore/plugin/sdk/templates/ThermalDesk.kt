package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.Sdk
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin
import org.json.JSONObject

/**
 * "Thermal Desk" — a thermal-focused dashboard built from GameCore's own readings.
 *
 * The deck has four cards: a live heat tile, a load/heat card, a full-width temperature trend,
 * and a small three-stage plan. The trend is authored by hand because the SDK's chart leaf is
 * literal-only; a dashboard chart may name a live feed, so this one names `temp` and carries a
 * sample series for the case where the `thermals` grant is missing. That is the same pattern the
 * Graphics Tuning and Benchmark Suite packages use, not a new format.
 *
 * **Which temperature.** The plugin feed's `temp` is the **battery** sensor, not the CPU junction
 * temperature. That is the figure the host exposes to overlays and dashboard widgets, and the
 * labels below say so rather than implying a die temperature. The plugin cannot read a sensor
 * directly, change fan speed or charging current, or promise a SoC reading.
 */
fun thermalDesk(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.thermaldesk",
    name = "Thermal Desk",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A thermal-focused dashboard: battery temperature, load, a temperature trend and a " +
            "three-stage plan built from GameCore's approved readings."
    changelog = "Initial release."

    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_ICON)

    widgets {
        widget("thermal_now", "Battery heat", WidgetSizes.SMALL) {
            column(gap = 2, padding = 12) {
                text("Batt. temp", size = 11)
                stat(OverlayStats.TEMP, size = 24)
            }
        }

        widget("thermal_load", "Load and battery heat", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12) {
                text("Load and battery heat", size = 13)
                grid(columns = 3, gap = 8) {
                    column(gap = 2) {
                        text("CPU", size = 11)
                        stat(OverlayStats.CPU, size = 19)
                    }
                    column(gap = 2) {
                        text("GPU", size = 11)
                        stat(OverlayStats.GPU, size = 19)
                    }
                    column(gap = 2) {
                        text("Batt. temp", size = 11)
                        stat(OverlayStats.TEMP, size = 19)
                    }
                }
            }
        }

        widget("thermal_plan", "Thermal plan", WidgetSizes.MEDIUM) {
            column(gap = 7, padding = 12) {
                text("A simple ladder", size = 13)
                text("42 °C battery — cut animation load", size = 12)
                text("45 °C battery — battery saver on", size = 12)
                text("48 °C battery — stop and let it cool", size = 12)
                text(
                    "Battery-sensor figures, not CPU junction. Cooldown Ladder can automate " +
                        "the first two stages.",
                    size = 11,
                )
            }
        }
    }

    // The one live chart: a full-width temperature trend with a literal fallback series.
    section("widgets/thermal_trend.json", "widgets", thermalTrendWidget())

    icon(slot = IconSlots.REDLINE)
}

/**
 * The hand-emitted live chart. The `stat` field names the host's `temp` feed; `values` is the
 * sample series drawn when the `thermals` scope is not granted. Kept short enough to fit the
 * host's tightest chart-string cap.
 */
private fun thermalTrendWidget(): ByteArray {
    val props = JSONObject()
        .put("kind", ChartKinds.LINE)
        .put("stat", OverlayStats.TEMP)
        .put("values", "38,40,41,43,44,46,45,44,43,42,41,40")
        .put("min", 20)
        .put("max", 80)
        .put("label", "Battery temperature")
        .put("note", "Live battery temperature when thermals is granted; sample shown otherwise.")
        .put("color", "#FF7A45")
    val content = JSONObject()
        .put("type", "chart")
        .put("props", props)
    return JSONObject()
        .put("format", Sdk.WIDGET_FORMAT)
        .put("version", Sdk.WIDGET_VERSION)
        .put("id", "thermal_trend")
        .put("title", "Temperature trend")
        .put("size", WidgetSizes.FULL)
        .put("content", content)
        .toString(2)
        .toByteArray()
}
