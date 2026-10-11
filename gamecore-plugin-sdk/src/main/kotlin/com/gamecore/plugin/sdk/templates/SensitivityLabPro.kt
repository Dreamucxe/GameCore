package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlaySizes
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.plugin

/**
 * "Sensitivity Lab Pro" — a reference and measurement aid for tuning aim sensitivity, assembled
 * from three data-only sections: a live motion overlay, two read-only reference screens, and a
 * typed settings panel the host stores on the plugin's behalf.
 *
 * Like every plugin on this platform it carries **data, not code**. Nothing written below runs:
 * the DSL emits JSON and the host interprets it. That shapes what this plugin honestly is, so the
 * limits are stated here rather than discovered on a device:
 *
 *  - **The overlay is read-only.** It draws the device's live rotation-rate and movement readings
 *    (and the frame rate, for context) over a running game so you can *feel out* how a sensitivity
 *    change lands. Those figures read empty unless the pill is actually shown over a game — the
 *    motion feed only runs there — and the pill itself takes no touches.
 *  - **The screens are inert.** They are copy-ready preset tables and a short guide, drawn under
 *    GameCore's own theme on real navigation routes. Anything the renderer draws from plugin data
 *    is drawn non-interactive.
 *  - **The settings are stored, not applied.** The preset, DPI and multiplier controls are the one
 *    genuinely interactive surface — host-owned controls, which is what [Permissions.STORE_DATA]
 *    buys — and the host keeps their values in the plugin's own store. But **nothing applies them
 *    to any game.** A data-only plugin has no path into another app's input pipeline. This is a
 *    lab you read off and then type into each game yourself, not a sensitivity injector.
 *
 * ### Sections, permissions and the api level
 * The pill needs [Permissions.CREATE_OVERLAY]; its live `stat` nodes need
 * [Permissions.READ_APPROVED_DATA] scoped to exactly what they read — `motion` for the rotation
 * and movement readings, `device_stats` for the frame rate (see [OverlayStats.scopeFor]). The
 * screens need [Permissions.CHANGE_UI]; the settings need [Permissions.STORE_DATA]. The manifest
 * requests every scope its stat nodes need, which is the one agreement no codec checks for us: a
 * module whose data appetite outruns its grants loads and then silently never fills those fields.
 *
 * `api` lands at 3 on its own — a settings section, declarative screens, and an overlay carrying a
 * **motion** stat are each API-3 features, and the builder raises the manifest to match. Nothing
 * sets it by hand, and `minHostVersion` keeps the SDK default.
 */
fun sensitivityLabPro(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.sensitivitylab",
    name = "Sensitivity Lab Pro",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description = "A sensitivity and DPI reference lab: a live motion overlay to feel out aim " +
        "changes, copy-ready preset tables, and saved preferences. It measures and remembers — " +
        "it cannot change any game's sensitivity."
    changelog = "Initial release."

    // A pill drawn over a game (CREATE_OVERLAY) whose live readings come from the motion and
    // device-stats apertures; the screens ride on CHANGE_UI; the typed settings live in the
    // plugin's own store behind STORE_DATA. READ_APPROVED_DATA is scoped to exactly the two
    // apertures the pill's stat nodes read and no more.
    request(Permissions.CREATE_OVERLAY)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.MOTION, DataScopes.DEVICE_STATS),
    )
    request(Permissions.CHANGE_UI)
    request(Permissions.STORE_DATA)

    // ---------------------------------------------------------------- overlay (live motion)
    // Rotation-rate and movement are the two `motion` readings; the frame rate rides along for
    // context and is the one `device_stats` reading. Labels are their own text nodes because the
    // overlay `stat()` has no label of its own.
    pill {
        id = "sensitivity.monitor"
        title = "Sensitivity monitor"
        anchor(x = 0.5, y = 0.12)
        size = OverlaySizes.LARGE
        card(padding = 8) {
            column(gap = 6) {
                row(gap = 6, align = "center") {
                    image(OverlayIcons.SENSORS, size = 12)
                    text("Sensitivity Lab", size = 11, color = "#CCFFFFFF")
                }
                row(gap = 14, align = "center") {
                    column(align = "center") {
                        stat(OverlayStats.ROTATION_RATE, size = 16)
                        text("rot/s", size = 9, color = "#99FFFFFF")
                    }
                    column(align = "center") {
                        stat(OverlayStats.MOVEMENT, size = 16)
                        text("move", size = 9, color = "#99FFFFFF")
                    }
                    column(align = "center") {
                        stat(OverlayStats.FPS, size = 16)
                        text("fps", size = 9, color = "#99FFFFFF")
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- screens (reference)
    screens {
        screen("presets", "Sensitivity presets", icon = OverlayIcons.SPEED) {
            column(gap = 16, padding = 16) {
                text("Sensitivity presets", size = 22, bold = true)
                text(
                    "Reference values to copy into each game's own settings. GameCore cannot " +
                        "apply them for you.",
                    size = 13,
                    color = "#B0FFFFFF",
                )
                card(padding = 12, background = "#141821") {
                    grid(columns = 4, gap = 10) {
                        text("Preset", size = 13, bold = true)
                        text("Multiplier", size = 13, bold = true)
                        text("DPI", size = 13, bold = true)
                        text("eDPI", size = 13, bold = true)

                        text("Low", size = 13)
                        text("1.0x", size = 13)
                        text("400", size = 13)
                        text("400", size = 13)

                        text("Medium", size = 13)
                        text("1.0x", size = 13)
                        text("800", size = 13)
                        text("800", size = 13)

                        text("High", size = 13)
                        text("1.5x", size = 13)
                        text("800", size = 13)
                        text("1200", size = 13)

                        text("Pro", size = 13)
                        text("2.0x", size = 13)
                        text("1600", size = 13)
                        text("3200", size = 13)
                    }
                }
            }
        }
        screen("guide", "How it works", icon = OverlayIcons.INFO) {
            column(gap = 14, padding = 16) {
                text("How Sensitivity Lab works", size = 20, bold = true)
                card(padding = 12, background = "#141821") {
                    column(gap = 6) {
                        text("Live overlay", size = 15, bold = true)
                        text(
                            "The pill reads your device's rotation rate and movement while a " +
                                "game is in the foreground, so you can compare how a change feels.",
                            size = 13,
                            color = "#B0FFFFFF",
                        )
                    }
                }
                card(padding = 12, background = "#141821") {
                    column(gap = 6) {
                        text("Saved preferences", size = 15, bold = true)
                        text(
                            "Your preset, DPI and multipliers are kept in the plugin's own " +
                                "store, so you can refer back to the numbers you settled on.",
                            size = 13,
                            color = "#B0FFFFFF",
                        )
                    }
                }
                card(padding = 12, background = "#141821") {
                    column(gap = 6) {
                        text("Honest limits", size = 15, bold = true)
                        text(
                            "This is a measurement and reference aid. It cannot change the " +
                                "sensitivity of any game — GameCore plugins are data only, so you " +
                                "still enter these values in each game yourself.",
                            size = 13,
                            color = "#B0FFFFFF",
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- settings (stored, inert)
    // Real, interactive host-owned controls kept in the plugin's own store (STORE_DATA). They are
    // the one touchable surface this plugin has — and the host applies them to nothing, because a
    // data-only plugin cannot reach a game's input. They record what you decided, nothing more.
    settings {
        enum("preset", options = listOf("low", "medium", "high", "pro"), default = "medium")
        int("dpi", default = 800, min = 100, max = 16000)
        float("multiplier", default = 1.0, min = 0.1, max = 10.0)
        float("ads_multiplier", default = 1.0, min = 0.1, max = 5.0)
        bool("invert_y", default = false)
    }
}
