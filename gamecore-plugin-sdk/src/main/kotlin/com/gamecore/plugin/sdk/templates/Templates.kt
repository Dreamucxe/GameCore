package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Actions
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlaySizes
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.RuleOperators
import com.gamecore.plugin.sdk.RuleVariables
import com.gamecore.plugin.sdk.RulesBuilder
import com.gamecore.plugin.sdk.Triggers
import com.gamecore.plugin.sdk.plugin

/**
 * Worked templates an author can copy. Each returns a [PluginDefinition] built entirely
 * through the DSL — nothing here hand-writes JSON, so these double as the SDK's own examples
 * and as fixtures its tests package and (via the host) validate.
 */
object Templates {

    /** Minimal: a clean dark theme, one low-risk permission. */
    fun minimal(): PluginDefinition = plugin("com.example.minimal", "Minimal") {
        developer = "You"
        description = "A clean, low-chrome dark theme."
        request(Permissions.CHANGE_UI)
        theme {
            name = "Minimal"; dark = true
            primary = "#4F8CFF"; accent = "#4F8CFF"
            background = "#000000"; surface = "#0E1013"; onSurface = "#E6E8EC"
            cornerRadiusDp = 12; font = "system"
        }
    }

    /** Pixel: a warm rounded light theme. */
    fun pixel(): PluginDefinition = plugin("com.example.pixel", "Pixel") {
        developer = "You"
        description = "A warm, rounded light theme."
        request(Permissions.CHANGE_UI)
        theme {
            name = "Pixel"; dark = false
            primary = "#2E7D32"; accent = "#43A047"
            background = "#FBFDF7"; surface = "#EEF3E8"; onSurface = "#1A1C19"
            cornerRadiusDp = 28; font = "rounded"
        }
    }

    /** Cyberpunk: a theme plus a broker-driven performance rule set. */
    fun cyberpunk(): PluginDefinition = plugin("com.example.cyberpunk", "Cyberpunk") {
        developer = "You"
        description = "High-contrast neon theme that also boosts refresh rate in game."
        request(Permissions.CHANGE_UI)
        request(Permissions.SYSTEM_ACTIONS)
        request(Permissions.RUN_AUTOMATIONS)
        request(Permissions.READ_APPROVED_DATA, scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS))
        theme {
            name = "Cyberpunk"; dark = true
            primary = "#FF2E88"; accent = "#00E5FF"
            background = "#05010A"; surface = "#120820"; onSurface = "#F2E9FF"
            cornerRadiusDp = 4; font = "mono"
        }
        rules {
            rule("boost", Triggers.GAME_LAUNCHED, listOf(Actions.SET_PEAK_REFRESH_RATE))
            // The variable is named from RuleVariables, not spelled by hand: the host does not
            // resolve it, so "temp_c" here would be a rule that installs and never fires.
            rule(
                "cool_down", Triggers.THERMAL_HIGH,
                actions = listOf(Actions.SET_MIN_REFRESH_RATE),
                conditions = listOf(
                    RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0),
                ),
            )
        }
    }

    /**
     * Overlay: a data-only plugin whose whole content is one bounded module drawn over the game.
     *
     * The permission pair is the part worth copying. [Permissions.CREATE_OVERLAY] is the gate
     * for drawing the module at all; the `stat` nodes then need
     * [Permissions.READ_APPROVED_DATA] scoped to wherever those figures come from —
     * `device_stats` for the frame rate, `thermals` for the temperature — because the module
     * loads without them and simply renders nothing where the numbers should be.
     */
    fun overlay(): PluginDefinition = plugin("com.example.overlay", "Overlay Readout") {
        developer = "You"
        description = "A small frame-rate and temperature readout drawn over your game."
        request(Permissions.CREATE_OVERLAY)
        request(Permissions.READ_APPROVED_DATA, scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS))
        pill {
            id = "fps-badge"
            title = "Frame rate"
            anchor(x = 0.5, y = 0.08)
            size = OverlaySizes.SMALL
            opacity = 85
            row(gap = 10, align = "center") {
                column(gap = 2, align = "center") {
                    text("FPS", color = "#99FFFFFF", size = 10)
                    stat(OverlayStats.FPS, size = 16)
                }
                column(gap = 2, align = "center") {
                    text("TEMP", color = "#99FFFFFF", size = 10)
                    stat(OverlayStats.TEMP, size = 16)
                }
            }
        }
    }
}
