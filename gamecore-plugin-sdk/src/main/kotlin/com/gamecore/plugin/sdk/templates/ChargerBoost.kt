package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Actions
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.RuleOperators
import com.gamecore.plugin.sdk.RuleVariables
import com.gamecore.plugin.sdk.RulesBuilder
import com.gamecore.plugin.sdk.Triggers
import com.gamecore.plugin.sdk.plugin

/**
 * "Charger Boost" — a performance profile that runs when a charger connects.
 *
 * The name is easy to misread, so the boundary is explicit: this is **not a fast-charge tool**.
 * No plugin can change charging voltage, current, negotiation or the charger's own protocol;
 * those live below Android's app surface. What this rule can do is pin the panel's refresh bounds
 * high and cut the three system animation scales while the device is charging, charging is below
 * 80%, and the device is not already warm. Those are brokered system settings, and the broker
 * owns their values.
 *
 * The package is data only: one rule, two read-only screens and an icon request.
 */
fun chargerBoost(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.chargerboost",
    name = "Charger Boost",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A performance profile when a charger connects, not a fast-charge tool. Pins high " +
            "refresh and cuts animation overhead while charging."
    changelog = "Initial release."

    request(Permissions.RUN_AUTOMATIONS)
    request(Permissions.SYSTEM_ACTIONS)
    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    rules {
        rule(
            id = "charger_boost",
            trigger = Triggers.CHARGER_CONNECTED,
            actions = listOf(
                Actions.SET_PEAK_REFRESH_RATE,
                Actions.SET_MIN_REFRESH_RATE,
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.BATTERY, RuleOperators.LTE, 80.0),
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.LTE, 44.0),
            ),
        )
    }

    screens {
        screen("overview", "Charger Boost", icon = OverlayIcons.BOLT) {
            column(gap = 12, padding = 16) {
                text("Charger Boost", size = 22, bold = true)
                text(
                    "When a charger connects, GameCore can pin the panel high and cut system " +
                        "animation work for the session. This plugin asks for that profile only " +
                        "when the battery is at 80% or below and the battery sensor is at 44 °C " +
                        "or below.",
                    size = 15,
                )
                card(padding = 14, gap = 8) {
                    text("The conditions", size = 16, bold = true)
                    text("Battery at 80% or below.", size = 14)
                    text("Battery temperature at 44 °C or below.", size = 14)
                    text("A charger-connected event.", size = 14)
                }
                card(padding = 14, gap = 8) {
                    text("What it changes", size = 16, bold = true)
                    text("Peak and minimum refresh rate: the broker's high-rate value.", size = 14)
                    text("The three system animation scales: zero.", size = 14)
                }
            }
        }

        screen("limits", "Not A Fast Charger", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("What it cannot do", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("It cannot change charging speed", size = 15, bold = true)
                    text(
                        "Charging voltage, current, USB-PD negotiation and battery temperature " +
                            "limits are below the app surface. No GameCore plugin can change " +
                            "them, and this one does not try.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("It cannot bypass heat limits", size = 15, bold = true)
                    text(
                        "The temperature condition is a guard, not a cooling system. If the " +
                            "device is already warm, the rule stays off.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("It cannot restore the animation scales", size = 15, bold = true)
                    text(
                        "The refresh and animation actions apply the broker's reviewed values " +
                            "and stay there. Turn the animation scales back on in Android " +
                            "settings or GameCore when you want them.",
                        size = 14,
                    )
                }
                text(
                    "If you want the fastest charge your phone supports, use the charger and " +
                        "cable the manufacturer specifies and leave the device cool.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.NEON)
}
