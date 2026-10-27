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
 * "Cooldown Ladder" — three staged thermal rules that shed system load as the device heats,
 * plus a low-battery backstop.
 *
 * A rule cannot carry its own value: it names one of GameCore's brokered actions, and the host
 * supplies that action's fixed, reviewed value. So the ladder is staged by *which* actions run,
 * not by arbitrary thresholds written into a shell command. Stage 1 disables the three system
 * animation scales; stage 2 turns battery saver on; stage 3 adds a brightness write, which the
 * broker auto-reverts when the game exits. The low-battery rule is the same battery-saver action
 * at 15% or below.
 *
 * **Which temperature.** The rule variable `temp` is the **battery** sensor, not the CPU junction
 * temperature. That is the figure the host publishes to the automation feed, and the screens say
 * so rather than implying a die temperature.
 *
 * **The honest limits.** There is no fan-speed action, no charging-current action, no per-rule
 * value, and no automatic restore for battery saver or the animation scales. Brightness is the
 * one action here with an automatic revert. The limits screen says this in the user's own terms
 * rather than implying the plugin can cool the phone by itself.
 */
fun cooldownLadder(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.cooldownladder",
    name = "Cooldown Ladder",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "Three staged thermal rules that shed system load as the device heats, plus a " +
            "low-battery backstop. Uses only GameCore's brokered actions."
    changelog = "Initial release."

    request(Permissions.RUN_AUTOMATIONS)
    request(Permissions.SYSTEM_ACTIONS)
    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    rules {
        // Stage 1: reduce system animation work before the device is hot enough to need saver.
        rule(
            id = "warm_42_animations",
            trigger = Triggers.THERMAL_HIGH,
            actions = listOf(
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 42.0),
            ),
        )

        // Stage 2: bring in Android's own battery saver as the next rung.
        rule(
            id = "warm_45_saver",
            trigger = Triggers.THERMAL_HIGH,
            actions = listOf(
                Actions.SET_LOW_POWER,
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0),
            ),
        )

        // Stage 3: add the brightness write, which the broker restores when the game exits.
        rule(
            id = "warm_48_brightness",
            trigger = Triggers.THERMAL_HIGH,
            actions = listOf(
                Actions.SET_LOW_POWER,
                Actions.SET_SCREEN_BRIGHTNESS,
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 48.0),
            ),
        )

        // A low battery is its own ladder: use the same saver action without waiting for heat.
        rule(
            id = "battery_15_saver",
            trigger = Triggers.BATTERY_LOW,
            actions = listOf(Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.BATTERY, RuleOperators.LTE, 15.0),
            ),
        )
    }

    screens {
        screen("ladder", "Cooldown Ladder", icon = OverlayIcons.THERMOSTAT) {
            column(gap = 12, padding = 16) {
                text("Cooldown Ladder", size = 22, bold = true)
                text(
                    "A staged response to heat. Each rung uses actions GameCore's broker already " +
                        "owns; the plugin never supplies a command or a value. The temperature " +
                        "is the battery sensor the automation feed publishes, not the CPU die.",
                    size = 15,
                )
                card(padding = 14, gap = 8) {
                    text("42 °C battery — shed animation work", size = 15, bold = true)
                    text(
                        "The three system animation scales go to zero. This is the smallest " +
                            "intervention and the first one to try.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("45 °C battery — battery saver on", size = 15, bold = true)
                    text(
                        "Android's own battery saver joins the animation cut. It reduces " +
                            "background work the system would otherwise do.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("48 °C battery — brightness write", size = 15, bold = true)
                    text(
                        "The broker sets a reviewed brightness level and restores the prior " +
                            "value when the game exits. It is the only action here with an " +
                            "automatic revert.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("15% battery — saver backstop", size = 15, bold = true)
                    text(
                        "The same battery-saver action, triggered by charge rather than heat.",
                        size = 14,
                    )
                }
            }
        }

        screen("limits", "What It Can't Do", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("The honest limits", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("No fan or charging control", size = 15, bold = true)
                    text(
                        "There is no broker action for fan speed, charging current, CPU " +
                            "frequency or GPU frequency. A plugin cannot reach them.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("No arbitrary values", size = 15, bold = true)
                    text(
                        "A rule names an action; GameCore supplies the action's fixed value. " +
                            "That is what keeps the broker's surface closed, and it is why the " +
                            "ladder is staged by actions rather than by numbers.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Not everything restores itself", size = 15, bold = true)
                    text(
                        "Brightness auto-reverts. Battery saver and the animation scales do " +
                            "not; turn them back off in Android settings or GameCore when the " +
                            "session is over.",
                        size = 14,
                    )
                }
                text(
                    "A cooler phone is mostly the room, the case, the charger and the workload. " +
                        "These rules reduce system overhead; they are not a cooling system.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.REDLINE)
}
