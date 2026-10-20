package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.Actions
import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlaySizes
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
 * Graphics Tuning — a guide to the graphics settings that actually move frame rate, with the
 * instrumentation to check whether they did on *your* device.
 *
 * ## Why this plugin is a guide and not a switch
 *
 * This template exists because of a question worth answering precisely: can a plugin turn on
 * trilinear texture filtering — the "Minecraft texture trick" — to boost frame rate? No, and the
 * reason is not that the plugin format is too small. **Texture filtering is sampler state the game
 * sets inside its own graphics context** (`glTexParameteri`/`glSamplerParameteri`, or the Vulkan
 * equivalent). Nothing outside that process can change it without injecting code into the game and
 * hooking its graphics driver. GameCore has no graphics-driver surface at all, a plugin executes no
 * code whatsoever, and the host's entire device vocabulary is seven brokered system settings — two
 * refresh-rate bounds, three animation scales, low power and screen brightness — none of them
 * graphical beyond the refresh rate. So the honest shape of this feature is a plugin that *explains*
 * the setting, *measures* what the user changes in the game's own video menu, and *runs* the brokered
 * automations that genuinely protect frame rate on a phone.
 *
 * The premise is also worth correcting, which the Textures screen does in the user's own words:
 * trilinear filtering is a **quality** feature, not a speed one. Bilinear samples four texels inside
 * one mip level; trilinear samples eight across two and blends them, so per sample it costs *more*.
 * The saving people attribute to it belongs to **mipmapping**, which trilinear requires — small mip
 * levels fit the texture cache and cut memory bandwidth on distant surfaces, and mobile GPUs are
 * bandwidth-bound, so mipmaps-on is usually a net win over mipmaps-off. Trilinear is the polish on
 * top that gives a little of it back. On the texture side the real saving is a **low-resolution
 * texture pack**: less texture memory and bandwidth for the same geometry.
 *
 * ## Five sections
 *
 *  - **screens** — "Tuning" pairs live FPS/GPU/temperature readouts with the settings that move frame
 *    rate, ordered by how much they move it; "Textures" is the mipmap/trilinear explainer and the
 *    boundary card above; "Limits" states what this plugin cannot do. All three are read-only — a
 *    plugin screen's buttons and sliders are drawn inert by design, so nothing here can look live and
 *    do nothing.
 *  - **pill** — a bounded in-game probe (frame rate, GPU load, temperature) so a setting change can be
 *    A/B'd where it matters, over the game, instead of taken on faith. This is the half that makes the
 *    plugin more than a page of advice.
 *  - **widgets** — a frame-rate tile, a load tile, and one live frame-rate trend chart. The chart is
 *    the reason this template reaches for [com.gamecore.plugin.sdk.PluginBuilder.section]: the DSL's
 *    `chart` leaf is literal-only, so a chart naming a *live* host feed is emitted by hand in
 *    [graphicsTrendWidget], reusing [Sdk.WIDGET_FORMAT]/[Sdk.WIDGET_VERSION] rather than inventing a
 *    builder for it.
 *  - **settings** — the preferences the guidance is phrased against. Host-owned values behind
 *    `STORE_DATA`; the plugin reads what the user set, it renders no inputs.
 *  - **rules** — three unattended automations whose actions are copied verbatim from the broker's own
 *    vocabulary. These are the only things in this package that *change* anything, they are brokered
 *    and rate-limited by the host, and each is a switch the user owns.
 *
 * ## Honest limits
 *
 * Every figure is the host's own, sampled only for the scopes granted, and an unavailable reading
 * draws as the absent em dash rather than a zero. The automations nudge the *device* through the
 * broker — refresh-rate bounds, system animation scales, low power. Not one of them reaches the game's
 * renderer, and this plugin cannot change a game's textures, filtering, resolution or draw distance.
 * The nearest real lever of that kind — scaling the render resolution with `wm size` — is a host
 * feature with no broker action behind it, so no plugin can drive it; that would be a new brokered
 * action, not a new plugin.
 */
fun graphicsTuning(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.graphics",
    name = "Graphics Tuning",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description = "A guide to the graphics settings that actually move frame rate, with an in-game " +
        "probe to check whether they did on your device. It explains and measures; it cannot change " +
        "a game's renderer."
    changelog = "Initial release."

    // Seven permissions. CHANGE_UI carries the screens, CREATE_OVERLAY the in-game probe,
    // ADD_DASHBOARD_WIDGETS the tiles; READ_APPROVED_DATA opens exactly the two apertures the
    // readings ride behind — device_stats for FPS/GPU/RAM, thermals for the temperature — and no
    // more; STORE_DATA backs the settings; RUN_AUTOMATIONS + SYSTEM_ACTIONS let the three rules reach
    // the broker.
    request(Permissions.CHANGE_UI)
    request(Permissions.CREATE_OVERLAY)
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.STORE_DATA)
    request(Permissions.RUN_AUTOMATIONS)
    request(Permissions.SYSTEM_ACTIONS)

    screens {
        screen("tuning", "Tuning", icon = OverlayIcons.SPEED) {
            column(gap = 12, padding = 16) {
                text("Graphics Tuning", size = 20, bold = true)
                text(
                    "The settings below are ordered by how much they actually move frame rate on a " +
                        "phone. You change them in the game's own video menu — GameCore cannot reach " +
                        "another app's renderer. What it can do is show you what happened.",
                    size = 14,
                )
                card(background = "#14181F", padding = 12, gap = 8) {
                    text("Live readings", size = 13, bold = true)
                    row(gap = 12) {
                        text("Frame rate")
                        stat(OverlayStats.FPS)
                    }
                    row(gap = 12) {
                        text("GPU load")
                        stat(OverlayStats.GPU)
                    }
                    row(gap = 12) {
                        text("Temperature")
                        stat(OverlayStats.TEMP)
                    }
                }
                text("What actually moves frame rate", size = 16, bold = true)
                grid(columns = 2, gap = 8) {
                    text("Render distance", bold = true)
                    text("Largest single win. Halving it can double frame rate.")
                    text("Graphics: Fast, not Fancy", bold = true)
                    text("Large. Drops transparency and lighting passes.")
                    text("Simulation distance", bold = true)
                    text("Large, and mostly CPU — the fix when GPU load is low but frames are not.")
                    text("Particles: minimal", bold = true)
                    text("Moderate. Many small blended quads are expensive on mobile.")
                    text("Entity shadows: off", bold = true)
                    text("Moderate in crowded scenes.")
                    text("Smooth lighting: off", bold = true)
                    text("Small to moderate; costs a lighting pass.")
                    text("Mipmap levels", bold = true)
                    text("Roughly neutral. A quality setting — see the Textures screen.")
                    text("VSync off", bold = true)
                    text("Uncaps frames above the panel's rate; raises heat, so watch the probe.")
                }
                card(background = "#1A1410", padding = 12, gap = 6) {
                    text("Change one thing at a time", size = 13, bold = true)
                    text(
                        "Turn on the in-game probe, note the frame rate, change one setting, and " +
                            "compare after two or three minutes of real play — long enough for the " +
                            "device to warm up. A figure taken in the first thirty seconds is a " +
                            "figure taken before throttling starts.",
                        size = 13,
                    )
                }
            }
        }
        screen("textures", "Textures", icon = OverlayIcons.INSIGHTS) {
            column(gap = 12, padding = 16) {
                text("Mipmaps, trilinear and texture size", size = 20, bold = true)
                text(
                    "Trilinear filtering is a quality setting, not a speed one. Bilinear takes four " +
                        "texel samples inside one mip level; trilinear takes eight across two levels " +
                        "and blends them, so per sample it costs more. What it buys is the " +
                        "disappearance of the visible seam where one mip level pops to the next.",
                    size = 14,
                )
                text(
                    "The speed-up usually credited to it belongs to mipmapping, which trilinear " +
                        "requires. Small mip levels fit the texture cache and cut memory bandwidth on " +
                        "distant surfaces, and mobile GPUs are bandwidth-bound — so mipmaps on is " +
                        "generally a net win over mipmaps off, and trilinear is the polish on top that " +
                        "gives a little of it back.",
                    size = 14,
                )
                text(
                    "On the texture side the real saving is resolution. A low-resolution texture pack " +
                        "asks for less texture memory and less bandwidth for exactly the same " +
                        "geometry, which is why those packs help most on the weakest devices.",
                    size = 14,
                )
                card(background = "#14181F", padding = 12, gap = 6) {
                    text("GameCore cannot change a game's texture filtering", size = 13, bold = true)
                    text(
                        "Filtering is sampler state the game sets inside its own graphics context. No " +
                            "app outside that process can change it without injecting code into the " +
                            "game and hooking its graphics driver, and GameCore does neither. This " +
                            "plugin runs no code at all: it is declarative data the host renders. So " +
                            "this screen explains the setting, and the game's own video menu changes it.",
                        size = 13,
                    )
                }
            }
        }
        screen("limits", "Limits", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("What this plugin can and cannot do", size = 20, bold = true)
                card(background = "#101A14", padding = 12, gap = 6) {
                    text("It can", size = 13, bold = true)
                    text(
                        "Show your own frame rate, GPU load and temperature; draw a bounded probe over " +
                            "the game; and run the three brokered automations on the Tuning screen, " +
                            "each of which you switch on yourself.",
                        size = 13,
                    )
                }
                card(background = "#1A1012", padding = 12, gap = 6) {
                    text("It cannot", size = 13, bold = true)
                    text(
                        "Change a game's textures, filtering, resolution or draw distance; press a " +
                            "button for you, since every control on these screens is drawn read-only " +
                            "by design; or report a figure it cannot measure — an unavailable reading " +
                            "shows as a dash, never as a zero.",
                        size = 13,
                    )
                }
                text(
                    "The automations act on the device through GameCore's broker — refresh-rate " +
                        "bounds, system animation scales, battery saver. None of them reaches inside a " +
                        "game.",
                    size = 13,
                )
            }
        }
    }

    pill {
        id = "graphics-probe"
        title = "Graphics probe"
        anchor(x = 0.5, y = 0.06)
        size = OverlaySizes.MEDIUM
        opacity = 85
        row(gap = 12, align = "center") {
            column(gap = 2, align = "center") {
                text("FPS", color = "#99FFFFFF", size = 10)
                stat(OverlayStats.FPS, size = 16)
            }
            column(gap = 2, align = "center") {
                text("GPU", color = "#99FFFFFF", size = 10)
                stat(OverlayStats.GPU, size = 16)
            }
            column(gap = 2, align = "center") {
                text("TEMP", color = "#99FFFFFF", size = 10)
                stat(OverlayStats.TEMP, size = 16)
            }
        }
    }

    widgets {
        widget("graphics_fps", "Frame rate", WidgetSizes.SMALL) {
            column(gap = 4, padding = 12) {
                text("Frame rate", size = 12)
                stat(OverlayStats.FPS, size = 22)
            }
        }
        widget("graphics_load", "Graphics load", WidgetSizes.MEDIUM) {
            column(gap = 6, padding = 12) {
                row(gap = 8) {
                    text("GPU", size = 12)
                    stat(OverlayStats.GPU, size = 16)
                }
                row(gap = 8) {
                    text("RAM", size = 12)
                    stat(OverlayStats.RAM, size = 16)
                }
            }
        }
    }

    // The live-chart widget, emitted by hand for the same reason the advisor's is: the DSL's chart()
    // leaf takes `values` and never a `stat`, so it can only ever draw a literal series. The host
    // dashboard is the one surface that resolves a chart's live `stat` and folds it into the widget's
    // required scopes, so the device_stats grant is what makes this plot live.
    section("widgets/graphics_trend.json", "widgets", graphicsTrendWidget())

    settings {
        int("target_fps", default = 60, min = 30, max = 240)
        int("warn_temp_c", default = 42, min = 30, max = 95)
        enum(
            "device_tier",
            options = listOf("budget", "midrange", "flagship"),
            default = "midrange",
        )
        enum(
            "guide_detail",
            options = listOf("brief", "standard", "verbose"),
            default = "standard",
        )
        bool("show_trend_widget", default = true)
    }

    rules {
        // Launch boost: the one rule with no conditions, matching the Performance Pack's own launch
        // rule. Raising the peak refresh bound is the single brokered action that can lift a frame
        // rate; everything else here protects one.
        rule("launch_boost", Triggers.GAME_LAUNCHED, actions = listOf(Actions.SET_PEAK_REFRESH_RATE))
        // Sustain: a phone's frame rate collapses from heat, not from settings, after twenty minutes.
        // On THERMAL_HIGH at or above 45 C, ask the broker to drop the minimum refresh bound and
        // enable low power so the device recovers instead of throttling harder.
        rule(
            "sustain_fps",
            Triggers.THERMAL_HIGH,
            actions = listOf(Actions.SET_MIN_REFRESH_RATE, Actions.SET_LOW_POWER),
            conditions = listOf(
                RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0),
            ),
        )
        // Trim the system's own animation work for the length of a session. This touches Android's
        // compositor, not the game's renderer — an honest, small win, and named so it cannot be
        // mistaken for one.
        rule(
            "trim_system_animations",
            Triggers.SESSION_STARTED,
            actions = listOf(
                Actions.SET_WINDOW_ANIMATION_SCALE,
                Actions.SET_TRANSITION_ANIMATION_SCALE,
                Actions.SET_ANIMATOR_DURATION_SCALE,
            ),
        )
    }
}

/**
 * The one live-chart widget, built by hand because the DSL cannot build it.
 *
 * `OverlayNodeScope.chart` is literal-only — it accepts a `values` series but no `stat`, so it can
 * never name a live host feed. A dashboard chart may name one, so the only way to author one from the
 * SDK is to emit the widget JSON directly, which this does with `org.json` and the existing
 * [Sdk.WIDGET_FORMAT] / [Sdk.WIDGET_VERSION] symbols; no new builder or constant is introduced.
 *
 * The node carries both a live series and a literal fallback on purpose. `stat` = [OverlayStats.FPS]
 * is the live frame-rate feed, which the host plots when `device_stats` is granted; `values` is a
 * sample series drawn instead when it is not, so the tile is never blank. The series is kept short
 * enough to sit inside the host's tightest string cap, since a hand-emitted chart gets no help from
 * the DSL's bounds check.
 */
private fun graphicsTrendWidget(): ByteArray {
    val props = JSONObject()
        .put("kind", ChartKinds.LINE)
        .put("stat", OverlayStats.FPS)
        .put("values", "48,52,57,60,59,61,58,54,49,46,44,47")
        .put("min", 0)
        .put("max", 120)
        .put("label", "Frame rate")
        .put("note", "Live when device_stats is granted; sample shown otherwise.")
        .put("color", "#4F8CFF")
    val content = JSONObject()
        .put("type", "chart")
        .put("props", props)
    return JSONObject()
        .put("format", Sdk.WIDGET_FORMAT)
        .put("version", Sdk.WIDGET_VERSION)
        .put("id", "graphics_trend")
        .put("title", "Frame rate trend")
        .put("size", WidgetSizes.FULL)
        .put("content", content)
        .toString(2)
        .toByteArray()
}
