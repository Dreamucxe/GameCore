package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin

/**
 * "Minimal Vitals" — five small, useful Home cards built from GameCore's approved readings.
 *
 * The set is intentionally small in both senses. The widgets are compact tiles, and the plugin
 * asks only for the two scopes its figures ride: `device_stats` for CPU and memory, `thermals`
 * for battery and temperature. There is no chart, no overlay and no rule, so the plugin has no
 * way to change the device and no reason to run when the dashboard is closed.
 *
 * The snapshot tile is the one bundled representative. The host's bundled-asset path reads one
 * `widgets.json` per plugin, while a sideloaded `.gcplugin` can carry all five; that asymmetry is
 * a host limit, not a missing widget.
 */
fun minimalVitals(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.minimalvitals",
    name = "Minimal Vitals",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "Five compact Home cards for the figures that matter: a 2×2 snapshot plus CPU, memory, " +
            "battery and heat tiles."
    changelog = "Initial release."

    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_ICON)

    widgets {
        widget("vitals_snapshot", "Vitals snapshot", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12) {
                text("Vitals", size = 13)
                grid(columns = 2, gap = 8) {
                    column(gap = 2) {
                        text("CPU", size = 11)
                        stat(OverlayStats.CPU, size = 20)
                    }
                    column(gap = 2) {
                        text("Memory", size = 11)
                        stat(OverlayStats.RAM, size = 20)
                    }
                    column(gap = 2) {
                        text("Battery", size = 11)
                        stat(OverlayStats.BATTERY, size = 20)
                    }
                    column(gap = 2) {
                        text("Heat", size = 11)
                        stat(OverlayStats.TEMP, size = 20)
                    }
                }
            }
        }

        widget("vitals_cpu", "CPU", WidgetSizes.SMALL) {
            column(gap = 2, padding = 12) {
                text("CPU", size = 11)
                stat(OverlayStats.CPU, size = 22)
            }
        }

        widget("vitals_ram", "Memory", WidgetSizes.SMALL) {
            column(gap = 2, padding = 12) {
                text("Memory", size = 11)
                stat(OverlayStats.RAM, size = 22)
            }
        }

        widget("vitals_battery", "Battery", WidgetSizes.SMALL) {
            column(gap = 2, padding = 12) {
                text("Battery", size = 11)
                stat(OverlayStats.BATTERY, size = 22)
            }
        }

        widget("vitals_heat", "Heat", WidgetSizes.SMALL) {
            column(gap = 2, padding = 12) {
                text("Heat", size = 11)
                stat(OverlayStats.TEMP, size = 22)
            }
        }
    }

    icon(slot = IconSlots.DEFAULT)
}
