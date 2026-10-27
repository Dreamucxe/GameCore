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
 * "Exit Restore" — a post-game baseline, not a snapshot restore.
 *
 * The name is the promise people expect, so the limit is stated first: a rule names an action
 * and GameCore supplies that action's fixed value. There is no action that reads a prior setting
 * and writes it back, so a plugin cannot restore *your* previous refresh rate, animation scales
 * or brightness. What this package can do is apply a conservative baseline when a game closes:
 * ask the panel for a high peak rate, and turn battery saver on if the charge is low.
 *
 * For a true per-game restore, GameCore's own profiles are the right tool. The setup screen says
 * that plainly, so a user does not mistake this lightweight baseline for a snapshot system.
 *
 * The package is data only: two rules, three read-only screens and an icon request.
 */
fun exitRestore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.exitrestore",
    name = "Exit Restore",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A post-game baseline, not a snapshot restore: peak refresh 120 and battery saver when " +
            "charge is low. Use GameCore profiles for true per-game restore."
    changelog = "Initial release."

    request(Permissions.RUN_AUTOMATIONS)
    request(Permissions.SYSTEM_ACTIONS)
    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    rules {
        // Leave the panel at a high peak rate for normal use after the game.
        rule(
            id = "exit_peak_refresh",
            trigger = Triggers.GAME_CLOSED,
            actions = listOf(Actions.SET_PEAK_REFRESH_RATE),
        )

        // If the session also ran the battery down, turn saver on at the same exit edge.
        rule(
            id = "exit_low_battery_saver",
            trigger = Triggers.GAME_CLOSED,
            actions = listOf(Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.BATTERY, RuleOperators.LTE, 30.0),
            ),
        )
    }

    screens {
        screen("overview", "Exit Restore", icon = OverlayIcons.CHECK) {
            column(gap = 12, padding = 16) {
                text("A post-game baseline", size = 22, bold = true)
                text(
                    "When a game closes, this plugin asks for a high peak refresh rate and, if " +
                        "the battery is at 30% or below, turns Android's battery saver on.",
                    size = 15,
                )
                card(padding = 14, gap = 8) {
                    text("What it does at exit", size = 16, bold = true)
                    text("Peak refresh rate: the broker's reviewed high-rate value.", size = 14)
                    text("Battery saver: on only when charge is 30% or lower.", size = 14)
                }
                card(padding = 14, gap = 8) {
                    text("What it does not do", size = 16, bold = true)
                    text(
                        "It does not read your previous settings and write them back. The " +
                            "plugin format has no action that carries a prior value.",
                        size = 14,
                    )
                }
            }
        }

        screen("setup", "True Per-Game Restore", icon = OverlayIcons.SETTINGS) {
            column(gap = 12, padding = 16) {
                text("Use profiles for a real restore", size = 20, bold = true)
                text(
                    "GameCore's per-game profiles already capture the settings a game needs and " +
                        "restore them when the session ends. That is the right tool when you " +
                        "want your own values back.",
                    size = 14,
                )
                card(padding = 14, gap = 8) {
                    text("Profile fields that restore", size = 15, bold = true)
                    text("Refresh rate, brightness, display size, orientation and volume.", size = 14)
                    text("Overlays, HUD, colour and session tracking.", size = 14)
                }
                card(padding = 14, gap = 8) {
                    text("Where to find it", size = 15, bold = true)
                    text(
                        "Create a profile for the game, set the values you want, and let " +
                            "GameCore apply and restore it. Exit Restore is the lightweight " +
                            "fallback for sessions you have not profiled.",
                        size = 14,
                    )
                }
            }
        }

        screen("limits", "The Honest Limits", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("Why this is not a snapshot", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("A rule has no value slot", size = 15, bold = true)
                    text(
                        "A rule names one of GameCore's brokered actions. The host supplies the " +
                            "action's fixed value; a plugin cannot pass an arbitrary number or a " +
                            "captured prior value through it.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Brightness is the exception", size = 15, bold = true)
                    text(
                        "The brightness action is auto-reverted by the broker. The refresh and " +
                            "battery-saver actions here are not; they apply the reviewed " +
                            "baseline and stay there until something else changes them.",
                        size = 14,
                    )
                }
                text(
                    "If the baseline is not what you want, use a profile or change the setting " +
                        "yourself. This plugin will not pretend it remembered a value it never read.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.MONO)
}
