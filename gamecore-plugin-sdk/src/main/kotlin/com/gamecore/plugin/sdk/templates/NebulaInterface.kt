package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.ChartKinds
import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.GlassSprings
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlayNodeScope
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ScreenNodeScope
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.WidgetSizes
import com.gamecore.plugin.sdk.plugin

/**
 * "Nebula Interface Suite" — a large, data-only look-and-feel pack built around a true-black
 * lavender/pink palette, a schema-4 glass material, a live control centre, five design-reference
 * screens and five dashboard widgets.
 *
 * The design brief is deliberately broad: the reference screens show a modern storefront's
 * look-and-feel language (theme cards, palette swatches, performance modes, motion language,
 * dock sizing and a glass control centre) without shipping any of that app's artwork, names or
 * marks. Everything here is authored from GameCore's own closed component set.
 *
 * ### What is actually live
 *  - **The theme.** A schema-4 glass material, AMOLED base, semantic roles and a medium rounded
 *    type treatment are applied by the host.
 *  - **The Glass Control Center.** `glass_intensity` is a real setting and the theme's
 *    `intensitySetting` points at it. Moving the host's Control Center slider changes the
 *    transparency, gloss and blur of the app in front of the user.
 *  - **Live figures.** Screens and widgets name only GameCore's approved readings. The manifest
 *    requests exactly `device_stats` + `thermals`, the union of every `stat` used below.
 *  - **Dashboard widgets.** Five read-only cards, one of which is the representative bundled
 *    copy; the `.gcplugin` carries all five.
 *
 * ### What is a preview, not a control
 *  - Theme cards, palette swatches, dock-size studies and performance-mode cards are **read-only
 *    design references**. The plugin format carries one theme per package and a screen node
 *    dispatches no action, so they cannot switch the app. The screens say that in plain text.
 *  - The glass slider on the Control Center screen is a static readout of the authored value.
 *    The live slider is the host's own Glass Control Center, backed by the setting below.
 *  - There is no custom font, artwork, network access, WebView, script or executable entry in the
 *    package. The format admits none of them.
 *
 * ### Palette
 * The six required roles are a true-black page, a near-black glass surface, lavender ink,
 * orchid primary, a pink pulse and a deep violet accent. Every enforced text/fill pair clears
 * WCAG AA; the body pairs clear AAA. The seven extra roles pin the secondary/tertiary colours
 * and the hairline the six-role model cannot express on its own.
 *
 * ### Format gaps (reported, not faked)
 *  - One theme per package: the four theme previews are a comparison screen, not a switcher.
 *  - One global corner radius and UI scale: the dock-size cards are layout studies, not dock
 *    controls; the host's own density setting is left untouched.
 *  - Screens are read-only by construction. The only interactive surface this plugin owns is the
 *    host-rendered Glass Control Center slider.
 *  - Performance modes are host-owned. The screen explains the language; it does not change
 *    refresh rate, resolution or thermals.
 */
fun nebulaInterfaceSuite(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.nebula",
    name = "Nebula Interface Suite",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description = "A true-black lavender-and-pink interface suite: glass theme, live control " +
        "centre, design screens, five dashboard widgets and one real glass-intensity control."
    changelog = "1.0.0 — first release: schema-4 glass theme, live control centre, look-and-feel, " +
        "motion, dock, type, performance and design-notes screens, plus five dashboard widgets."

    request(Permissions.CHANGE_UI)
    request(Permissions.ADD_DASHBOARD_WIDGETS)
    request(Permissions.STORE_DATA)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS, DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Nebula Interface Suite"
        dark = true
        primary = NEBULA_ORCHID
        onPrimary = NEBULA_DEEP_INK
        background = NEBULA_BLACK
        surface = NEBULA_SURFACE
        onSurface = NEBULA_INK
        accent = NEBULA_PINK
        cornerRadiusDp = 28
        font = ThemeFonts.ROUNDED
        uiScale = 1.0

        amoled = true
        roles {
            secondary = NEBULA_IRIS
            tertiary = NEBULA_SKY
            surfaceVariant = NEBULA_CARD
            onSurfaceVariant = NEBULA_MUTED
            outline = NEBULA_LINE
            error = NEBULA_ERROR
            onError = NEBULA_ERROR_INK
        }
        typography {
            weight = ThemeWeights.MEDIUM
        }
        glass {
            // The block is the ceiling; the setting below starts it at 72%. Scaling the authored
            // 1.0 by 0.72 gives 0.72 intensity and about 12dp of the 18dp blur, which is the
            // material the Control Center readout describes.
            intensity = 1.0
            transparency = 0.58
            gloss = 0.60
            blurRadiusDp = 18
            sheen = true
            sheenPeriodMs = 7200
            spring = GlassSprings.GENTLE
            hardwareReactive = false
            intensitySetting = GLASS_INTENSITY_SETTING
        }
    }

    settings {
        float(
            GLASS_INTENSITY_SETTING,
            default = 0.72,
            min = 0.0,
            max = 1.0,
        )
    }

    screens {
        screen("control", "Control Center", icon = OverlayIcons.INSIGHTS) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.INSIGHTS, size = 28)
                    column(gap = 2) {
                        text("Nebula Control Center", size = 24, bold = true, color = NEBULA_INK)
                        text("Live readings, material status and the honest limits.", size = 13, color = NEBULA_MUTED)
                    }
                }

                card(background = NEBULA_CARD, padding = 16, gap = 12) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.SENSORS, size = 18)
                        text("Live vitals", size = 16, bold = true, color = NEBULA_LAVENDER)
                        text("scope-gated", size = 11, color = NEBULA_MUTED)
                    }
                    grid(columns = 2, gap = 10) {
                        statTile(OverlayIcons.SPEED, "Frame rate", OverlayStats.FPS)
                        statTile(OverlayIcons.BOLT, "CPU load", OverlayStats.CPU)
                        statTile(OverlayIcons.INSIGHTS, "GPU load", OverlayStats.GPU)
                        statTile(OverlayIcons.MEMORY, "Memory", OverlayStats.RAM)
                        statTile(OverlayIcons.BATTERY, "Battery", OverlayStats.BATTERY)
                        statTile(OverlayIcons.THERMOSTAT, "Battery temp", OverlayStats.TEMP)
                    }
                    text(
                        "A figure appears only when the matching scope is granted and the device " +
                            "can read it; otherwise GameCore draws an em dash.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 10) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.EXTENSION, size = 20)
                        text("Glass material", size = 16, bold = true, color = NEBULA_PINK)
                        text("schema 4", size = 11, color = NEBULA_MUTED)
                    }
                    text("72% user intensity · up to 18dp blur · 7.2s sheen · gentle settle", size = 14, color = NEBULA_INK)
                    slider(value = 0.72, min = 0.0, max = 1.0, label = "Glass intensity (theme preview)", color = NEBULA_LAVENDER)
                    text(
                        "The live control is the host's Glass Control Center, backed by the " +
                            "plugin setting glass_intensity. This bar is a static readout.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                    text(
                        "Android 12 and newer can blur the backdrop. Below Android 12 the same " +
                            "translucency, gloss and rim render against a sharp backdrop.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 10) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.CHECK, size = 18)
                        text("What is real", size = 16, bold = true, color = NEBULA_MINT)
                    }
                    text("Theme repaint, semantic roles and typography.", size = 13, color = NEBULA_INK)
                    text("Live vitals on this screen and on the dashboard widgets.", size = 13, color = NEBULA_INK)
                    text("The Glass Control Center slider for glass_intensity.", size = 13, color = NEBULA_INK)
                    text(
                        "Everything else in this suite is a read-only design reference. No screen " +
                            "node dispatches an action, and no plugin code runs.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Quick profiles", size = 16, bold = true, color = NEBULA_LAVENDER)
                    profileRow(OverlayIcons.GAME, "Balanced", "Everyday look, all five widgets visible")
                    profileRow(OverlayIcons.SPEED, "Focus", "Command card and live vitals in reach")
                    profileRow(OverlayIcons.BATTERY, "Battery saver", "Fewer cards, calmer motion")
                    text(
                        "Profile names are a design study, not switches. GameCore's own profiles " +
                            "are the only ones this plugin can change, and it does not change them.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }

        screen("look", "Look & Feel", icon = OverlayIcons.EXTENSION) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.EXTENSION, size = 28)
                    column(gap = 2) {
                        text("Look & Feel", size = 24, bold = true, color = NEBULA_INK)
                        text("Theme language, palette and shape — previewed, not switched.", size = 13, color = NEBULA_MUTED)
                    }
                }

                text("Theme previews", size = 18, bold = true, color = NEBULA_LAVENDER)
                grid(columns = 2, gap = 12) {
                    themeCard("Dusk", "#1A1430", "#E9DEFF", NEBULA_LAVENDER, false)
                    themeCard("Dark", "#0B0B10", NEBULA_INK, NEBULA_PINK, false)
                    themeCard("OLED", NEBULA_BLACK, NEBULA_INK, NEBULA_LAVENDER, true)
                    themeCard("Light", "#F7F3FF", NEBULA_DEEP_INK, NEBULA_IRIS, false)
                }
                text(
                    "The active package carries one theme: OLED Nebula. The other cards show " +
                        "where a sibling theme package could take the same layout language.",
                    size = 12,
                    color = NEBULA_MUTED,
                )

                text("Palette", size = 18, bold = true, color = NEBULA_LAVENDER)
                grid(columns = 3, gap = 10) {
                    swatch("Orchid", NEBULA_ORCHID, NEBULA_DEEP_INK)
                    swatch("Iris", NEBULA_IRIS, "#0B0716")
                    swatch("Sky", NEBULA_SKY, "#06202B")
                    swatch("Rose", NEBULA_PINK, "#2A0710")
                    swatch("Mint", NEBULA_MINT, "#062018")
                    swatch("Sand", NEBULA_SAND, "#2A1A05")
                    swatch("Mono", NEBULA_INK, "#111118")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Shape & type", size = 18, bold = true, color = NEBULA_PINK)
                    text("Aa", size = 40, bold = true, color = NEBULA_INK)
                    text("Rounded family · medium weight · 28dp corners", size = 14, color = NEBULA_INK)
                    text(
                        "The format carries one global radius and one bundled font family. " +
                            "A theme can lean those; it cannot ship a font file or set a per-card radius.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.INFO, size = 18)
                        text("Read-only by design", size = 16, bold = true, color = NEBULA_LAVENDER)
                    }
                    text(
                        "These cards are comparisons, not controls. GameCore's plugin screens " +
                            "attach no gesture, so a tap changes nothing here.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }

        screen("motion", "Motion Lab", icon = OverlayIcons.TIMER) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.TIMER, size = 28)
                    column(gap = 2) {
                        text("Motion Lab", size = 24, bold = true, color = NEBULA_INK)
                        text("One slow sheen, one soft landing, no fake toggles.", size = 13, color = NEBULA_MUTED)
                    }
                }

                card(background = NEBULA_CARD, padding = 16, gap = 12) {
                    text("The motion contract", size = 18, bold = true, color = NEBULA_LAVENDER)
                    row(gap = 10, align = "center") {
                        image(OverlayIcons.TIMELINE, size = 20)
                        text("Sheen period", size = 13, color = NEBULA_MUTED)
                        text("7.2 s", size = 16, bold = true, color = NEBULA_INK)
                    }
                    row(gap = 10, align = "center") {
                        image(OverlayIcons.BOLT, size = 20)
                        text("Spring", size = 13, color = NEBULA_MUTED)
                        text("Gentle", size = 16, bold = true, color = NEBULA_INK)
                    }
                    row(gap = 10, align = "center") {
                        image(OverlayIcons.SENSORS, size = 20)
                        text("Hardware reactive", size = 13, color = NEBULA_MUTED)
                        text("Off", size = 16, bold = true, color = NEBULA_INK)
                    }
                    text(
                        "The host reads these values and decides what the device can render. " +
                            "A user who turns animations off gets no sheen and no settle.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                text("Principles", size = 18, bold = true, color = NEBULA_PINK)
                grid(columns = 1, gap = 10) {
                    principleCard(OverlayIcons.TIMELINE, "Settle, don't bounce", "A panel should arrive and stop. The gentle spring is a landing, not a performance.")
                    principleCard(OverlayIcons.INSIGHTS, "One highlight, slowly", "A single sheen drift makes the surface read as material; faster reads as a broken gradient.")
                    principleCard(OverlayIcons.SHIELD, "Respect the user", "System animation settings win over the theme. Motion is never a prerequisite for reading the screen.")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.INFO, size = 18)
                        text("No motion switches here", size = 16, bold = true, color = NEBULA_LAVENDER)
                    }
                    text(
                        "The plugin format exposes no animation toggle to a theme. The sheen and " +
                            "spring above are the authored contract; the host owns the frames and " +
                            "the accessibility override.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }

        screen("dock", "Dock & Density", icon = OverlayIcons.GAME) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.GAME, size = 28)
                    column(gap = 2) {
                        text("Dock & Density", size = 24, bold = true, color = NEBULA_INK)
                        text("Three dock studies and the tokens the host really applies.", size = 13, color = NEBULA_MUTED)
                    }
                }

                text("Dock size study", size = 18, bold = true, color = NEBULA_LAVENDER)
                dockStudy("Compact", 14, "Tighter icons for a smaller footprint")
                dockStudy("Standard", 18, "The suite's reference balance")
                dockStudy("Expanded", 24, "More presence, same glass material")

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.EXTENSION, size = 18)
                        text("Glass dock", size = 16, bold = true, color = NEBULA_PINK)
                    }
                    text(
                        "The dock inherits the same translucent, rimmed material as every panel. " +
                            "There is no separate dock-glass toggle in this package.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("What the host applies", size = 18, bold = true, color = NEBULA_LAVENDER)
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.CHECK, size = 18)
                        text("28dp global corner radius", size = 13, color = NEBULA_INK)
                    }
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.CHECK, size = 18)
                        text("1.0× UI scale; your density setting is untouched", size = 13, color = NEBULA_INK)
                    }
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.CHECK, size = 18)
                        text("Rounded bundled face, medium weight", size = 13, color = NEBULA_INK)
                    }
                    text(
                        "The dock cards are layout studies, not dock controls. A data-only plugin " +
                            "cannot resize the host dock or move its icons.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }

        screen("type", "Type & Palette", icon = OverlayIcons.DATA) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.DATA, size = 28)
                    column(gap = 2) {
                        text("Type & Palette", size = 24, bold = true, color = NEBULA_INK)
                        text("A ramp, a swatch book and the pairs that carry the look.", size = 13, color = NEBULA_MUTED)
                    }
                }

                card(background = NEBULA_CARD, padding = 16, gap = 10) {
                    text("Ramp", size = 18, bold = true, color = NEBULA_LAVENDER)
                    text("Display", size = 32, bold = true, color = NEBULA_INK)
                    text("Title", size = 24, bold = true, color = NEBULA_INK)
                    text("Body text — rounded, medium, comfortably spaced.", size = 15, color = NEBULA_INK)
                    text("CAPTION · 12 SP · MUTED", size = 12, color = NEBULA_MUTED)
                }

                text("Swatch book", size = 18, bold = true, color = NEBULA_PINK)
                grid(columns = 2, gap = 10) {
                    paletteRow("Orchid", NEBULA_ORCHID, NEBULA_DEEP_INK, "primary")
                    paletteRow("Iris", NEBULA_IRIS, "#0B0716", "secondary")
                    paletteRow("Sky", NEBULA_SKY, "#06202B", "tertiary")
                    paletteRow("Rose", NEBULA_PINK, "#2A0710", "accent")
                    paletteRow("Mint", NEBULA_MINT, "#062018", "support")
                    paletteRow("Mono", NEBULA_INK, "#111118", "ink")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Pairs that matter", size = 18, bold = true, color = NEBULA_LAVENDER)
                    pairRow("Ink on page", NEBULA_INK, NEBULA_BLACK)
                    pairRow("Ink on card", NEBULA_INK, NEBULA_CARD)
                    pairRow("Deep ink on orchid", NEBULA_DEEP_INK, NEBULA_ORCHID)
                    pairRow("Rose ink on pink", NEBULA_ERROR_INK, NEBULA_PINK)
                    text(
                        "Every enforced text/fill pair in the theme clears WCAG AA; the two body " +
                            "pairs clear AAA. The host computes the final Material roles.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("One family, no file", size = 16, bold = true, color = NEBULA_PINK)
                    text(
                        "The format lets a theme name a bundled face, not ship one. This package " +
                            "names the rounded family and leaves the device's accessibility scale alone.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }

        screen("performance", "Performance Modes", icon = OverlayIcons.SPEED) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.SPEED, size = 28)
                    column(gap = 2) {
                        text("Performance Modes", size = 24, bold = true, color = NEBULA_INK)
                        text("A design language for tuning — not a tuning control.", size = 13, color = NEBULA_MUTED)
                    }
                }

                text("Mode cards", size = 18, bold = true, color = NEBULA_LAVENDER)
                grid(columns = 2, gap = 10) {
                    modeCard("Auto", "Balances the host's own profile with the current game.", NEBULA_LAVENDER)
                    modeCard("Lite", "A calmer surface for long sessions and warm devices.", NEBULA_SKY)
                    modeCard("Balanced", "The suite's reference: full glass, live vitals, no drama.", NEBULA_MINT)
                    modeCard("Boost", "The most vivid look, for short sessions on cool hardware.", NEBULA_PINK)
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.SHIELD, size = 18)
                        text("Host-owned", size = 16, bold = true, color = NEBULA_PINK)
                    }
                    text(
                        "These cards are a visual study. GameCore's performance engine, your " +
                            "profiles and the device's thermal state decide what actually runs.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("What this plugin does not do", size = 18, bold = true, color = NEBULA_LAVENDER)
                    noRow("It does not change refresh rate or resolution.")
                    noRow("It does not alter CPU, GPU or thermal policy.")
                    noRow("It does not run automations or execute code.")
                    noRow("It does not hide the host's own performance controls.")
                }
            }
        }

        screen("notes", "Design Notes", icon = OverlayIcons.INFO) {
            scroll(gap = 16, padding = 16) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.INFO, size = 28)
                    column(gap = 2) {
                        text("Design Notes", size = 24, bold = true, color = NEBULA_INK)
                        text("What is live, what is a preview, and what the format cannot do.", size = 13, color = NEBULA_MUTED)
                    }
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("A data-only suite", size = 18, bold = true, color = NEBULA_LAVENDER)
                    text(
                        "This package carries JSON and a launcher-icon request. It has no classes, " +
                            "scripts, network calls, WebView or arbitrary host behaviour.",
                        size = 13,
                        color = NEBULA_INK,
                    )
                    text(
                        "GameCore interprets the theme, screens, settings and widgets with its own " +
                            "native renderer. A malformed package can fail to load; it cannot run.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("What is live", size = 18, bold = true, color = NEBULA_MINT)
                    noteRow("Theme colours, AMOLED base, semantic roles, typography")
                    noteRow("Glass material and the glass_intensity Control Center slider")
                    noteRow("Live vitals on screens and dashboard widgets")
                    noteRow("Five read-only dashboard cards")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("What is a preview", size = 18, bold = true, color = NEBULA_PINK)
                    noteRow("Theme cards and palette swatches")
                    noteRow("Dock-size studies")
                    noteRow("Performance-mode cards")
                    noteRow("The static glass bar on the Control Center screen")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Format notes", size = 18, bold = true, color = NEBULA_LAVENDER)
                    noteRow("One theme per package; sibling themes would be separate packages")
                    noteRow("One global corner radius and UI scale")
                    noteRow("No shipped font, artwork or icon bitmap")
                    noteRow("Screens and widgets dispatch no actions")
                    noteRow("The host's animation and density settings always win")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Permissions", size = 18, bold = true, color = NEBULA_LAVENDER)
                    permissionRow(OverlayIcons.EXTENSION, "Change UI", "Repaint the app and draw the screens.")
                    permissionRow(OverlayIcons.GAME, "Dashboard widgets", "Place the five read-only cards.")
                    permissionRow(OverlayIcons.STORAGE, "Store data", "Keep glass_intensity in the plugin's own store.")
                    permissionRow(OverlayIcons.SENSORS, "Approved readings", "device_stats and thermals for live figures.")
                    permissionRow(OverlayIcons.INFO, "Launcher icon", "Ask for a bundled GameCore icon slot.")
                }

                card(background = NEBULA_CARD, padding = 16, gap = 8) {
                    text("Credit & scope", size = 18, bold = true, color = NEBULA_PINK)
                    text(
                        "The design language is inspired by modern storefront interface patterns: " +
                            "true-black surfaces, large rounded cards, lavender/pink accents, glass " +
                            "materials and calm motion. No third-party artwork, screenshots, names or " +
                            "trademarks are included, and this package is not affiliated with any " +
                            "storefront app.",
                        size = 12,
                        color = NEBULA_MUTED,
                    )
                }
            }
        }
    }

    widgets {
        widget("command", "Nebula Command", WidgetSizes.FULL) {
            column(gap = 10, padding = 12) {
                row(gap = 8, align = "center") {
                    image(OverlayIcons.INSIGHTS, size = 18)
                    text("Nebula Command", size = 15, color = NEBULA_LAVENDER)
                }
                grid(columns = 2, gap = 8) {
                    widgetStat(OverlayIcons.SPEED, "FPS", OverlayStats.FPS)
                    widgetStat(OverlayIcons.BOLT, "CPU", OverlayStats.CPU)
                    widgetStat(OverlayIcons.INSIGHTS, "GPU", OverlayStats.GPU)
                    widgetStat(OverlayIcons.MEMORY, "RAM", OverlayStats.RAM)
                    widgetStat(OverlayIcons.BATTERY, "Battery", OverlayStats.BATTERY)
                    widgetStat(OverlayIcons.THERMOSTAT, "Temp", OverlayStats.TEMP)
                }
                chart(
                    values = listOf(58, 61, 59, 64, 62, 66, 63, 65),
                    kind = ChartKinds.LINE,
                    min = 0,
                    max = 90,
                    label = "Frame rate shape",
                    note = "sample shape · live figures above",
                    color = NEBULA_LAVENDER,
                )
            }
        }

        widget("vitals", "Nebula Vitals", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12) {
                text("Nebula Vitals", size = 14, color = NEBULA_PINK)
                grid(columns = 2, gap = 8) {
                    widgetStat(OverlayIcons.SPEED, "FPS", OverlayStats.FPS)
                    widgetStat(OverlayIcons.BOLT, "CPU", OverlayStats.CPU)
                    widgetStat(OverlayIcons.THERMOSTAT, "Temp", OverlayStats.TEMP)
                    widgetStat(OverlayIcons.BATTERY, "Battery", OverlayStats.BATTERY)
                }
            }
        }

        widget("palette", "Nebula Palette", WidgetSizes.SMALL) {
            column(gap = 8, padding = 12) {
                text("Nebula", size = 14, color = NEBULA_LAVENDER)
                row(gap = 4) {
                    swatchChip(NEBULA_ORCHID, NEBULA_DEEP_INK)
                    swatchChip(NEBULA_IRIS, "#0B0716")
                    swatchChip(NEBULA_PINK, "#2A0710")
                    swatchChip(NEBULA_SKY, "#06202B")
                }
                text("orchid · iris · rose · sky", size = 11, color = NEBULA_MUTED)
            }
        }

        widget("motion", "Nebula Motion", WidgetSizes.MEDIUM) {
            column(gap = 8, padding = 12) {
                text("Motion contract", size = 14, color = NEBULA_PINK)
                row(gap = 6, align = "center") {
                    image(OverlayIcons.TIMELINE, size = 16)
                    text("Sheen 7.2s", size = 12, color = NEBULA_INK)
                }
                row(gap = 6, align = "center") {
                    image(OverlayIcons.BOLT, size = 16)
                    text("Gentle settle", size = 12, color = NEBULA_INK)
                }
                row(gap = 6, align = "center") {
                    image(OverlayIcons.EXTENSION, size = 16)
                    text("Glass 72%", size = 12, color = NEBULA_INK)
                }
                text("system motion wins", size = 11, color = NEBULA_MUTED)
            }
        }

        widget("dock", "Nebula Dock", WidgetSizes.FULL) {
            column(gap = 8, padding = 12) {
                text("Glass dock", size = 14, color = NEBULA_LAVENDER)
                card(background = "#CC17151F", padding = 10) {
                    row(gap = 12, align = "center") {
                        dockIcon(OverlayIcons.GAME, "Play")
                        dockIcon(OverlayIcons.SPEED, "Boost")
                        dockIcon(OverlayIcons.INSIGHTS, "Stats")
                        dockIcon(OverlayIcons.SETTINGS, "Tune")
                        dockIcon(OverlayIcons.SHIELD, "Safe")
                    }
                }
                text("A read-only study of the suite's dock language.", size = 11, color = NEBULA_MUTED)
            }
        }
    }

    icon(slot = IconSlots.NEON)
}

// ------------------------------------------------------------------------------------------------
// Palette constants
// ------------------------------------------------------------------------------------------------

private const val NEBULA_BLACK = "#000000"
private const val NEBULA_SURFACE = "#111118"
private const val NEBULA_CARD = "#1C1B24"
private const val NEBULA_INK = "#F7F3FF"
private const val NEBULA_MUTED = "#BDB6CF"
private const val NEBULA_LAVENDER = "#C9B8FF"
private const val NEBULA_ORCHID = "#C9B8FF"
private const val NEBULA_IRIS = "#8B7CFF"
private const val NEBULA_PINK = "#FF9DCE"
private const val NEBULA_SKY = "#7DD3FC"
private const val NEBULA_MINT = "#8FF0C4"
private const val NEBULA_SAND = "#F5D9A8"
private const val NEBULA_LINE = "#3B3548"
private const val NEBULA_ERROR = "#FF6B8A"
private const val NEBULA_ERROR_INK = "#2A0710"
private const val NEBULA_DEEP_INK = "#1A1033"
private const val GLASS_INTENSITY_SETTING = "glass_intensity"

// ------------------------------------------------------------------------------------------------
// Screen helpers
// ------------------------------------------------------------------------------------------------

private fun ScreenNodeScope.statTile(icon: String, label: String, stat: String) {
    card(background = NEBULA_CARD, padding = 12, gap = 6) {
        row(gap = 8, align = "center") {
            image(icon, size = 18)
            text(label, color = NEBULA_MUTED, size = 12)
        }
        stat(stat, size = 22, color = NEBULA_INK)
    }
}

private fun ScreenNodeScope.profileRow(icon: String, title: String, detail: String) {
    row(gap = 10, align = "center") {
        image(icon, size = 18)
        column(gap = 2) {
            text(title, size = 14, bold = true, color = NEBULA_INK)
            text(detail, size = 12, color = NEBULA_MUTED)
        }
    }
}

private fun ScreenNodeScope.themeCard(
    name: String,
    background: String,
    ink: String,
    accent: String,
    active: Boolean,
) {
    card(background = background, padding = 14, gap = 8) {
        row(gap = 8, align = "center") {
            text(name, color = ink, size = 16, bold = true)
            if (active) text("ACTIVE", color = accent, size = 10, bold = true)
        }
        row(gap = 6) {
            card(background = accent, padding = 8) {
                text("Aa", color = NEBULA_DEEP_INK, size = 14, bold = true)
            }
            card(background = ink, padding = 8) {
                text("Aa", color = background, size = 14, bold = true)
            }
        }
        text(if (active) "Applied now" else "Preview only", color = ink, size = 11)
    }
}

private fun ScreenNodeScope.swatch(name: String, background: String, ink: String) {
    card(background = background, padding = 12, gap = 4) {
        text(name, color = ink, size = 13, bold = true)
        text(background.uppercase(), color = ink, size = 10)
    }
}

private fun ScreenNodeScope.paletteRow(
    name: String,
    background: String,
    ink: String,
    role: String,
) {
    card(background = background, padding = 12, gap = 4) {
        text(name, color = ink, size = 14, bold = true)
        text(role, color = ink, size = 11)
        text(background.uppercase(), color = ink, size = 10)
    }
}

private fun ScreenNodeScope.pairRow(label: String, foreground: String, background: String) {
    card(background = background, padding = 10, gap = 4) {
        text(label, color = foreground, size = 13, bold = true)
        text("Aa — legible pair", color = foreground, size = 12)
    }
}

private fun ScreenNodeScope.principleCard(icon: String, title: String, detail: String) {
    card(background = NEBULA_CARD, padding = 14, gap = 6) {
        row(gap = 8, align = "center") {
            image(icon, size = 18)
            text(title, size = 15, bold = true, color = NEBULA_INK)
        }
        text(detail, size = 12, color = NEBULA_MUTED)
    }
}

private fun ScreenNodeScope.dockStudy(name: String, iconSize: Int, detail: String) {
    card(background = NEBULA_CARD, padding = 14, gap = 8) {
        row(gap = 8, align = "center") {
            text(name, size = 15, bold = true, color = NEBULA_LAVENDER)
            text(detail, size = 11, color = NEBULA_MUTED)
        }
        card(background = "#CC17151F", padding = 10) {
            row(gap = 14, align = "center") {
                image(OverlayIcons.GAME, size = iconSize)
                image(OverlayIcons.SPEED, size = iconSize)
                image(OverlayIcons.INSIGHTS, size = iconSize)
                image(OverlayIcons.SETTINGS, size = iconSize)
                image(OverlayIcons.SHIELD, size = iconSize)
            }
        }
    }
}

private fun ScreenNodeScope.modeCard(name: String, detail: String, accent: String) {
    card(background = NEBULA_CARD, padding = 14, gap = 6) {
        text(name, size = 16, bold = true, color = accent)
        text(detail, size = 12, color = NEBULA_MUTED)
        text("design reference", size = 10, color = NEBULA_MUTED)
    }
}

private fun ScreenNodeScope.noRow(detail: String) {
    row(gap = 8, align = "center") {
        image(OverlayIcons.BLOCK, size = 16)
        text(detail, size = 12, color = NEBULA_MUTED)
    }
}

private fun ScreenNodeScope.noteRow(detail: String) {
    row(gap = 8, align = "center") {
        image(OverlayIcons.CHECK, size = 16)
        text(detail, size = 12, color = NEBULA_INK)
    }
}

private fun ScreenNodeScope.permissionRow(icon: String, title: String, detail: String) {
    row(gap = 10, align = "center") {
        image(icon, size = 18)
        column(gap = 2) {
            text(title, size = 13, bold = true, color = NEBULA_INK)
            text(detail, size = 11, color = NEBULA_MUTED)
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Widget helpers
// ------------------------------------------------------------------------------------------------

private fun OverlayNodeScope.widgetStat(icon: String, label: String, stat: String) {
    card(background = NEBULA_CARD, padding = 10) {
        row(gap = 6, align = "center") {
            image(icon, size = 16)
            text(label, color = NEBULA_MUTED, size = 11)
        }
        stat(stat, size = 18, color = NEBULA_INK)
    }
}

private fun OverlayNodeScope.swatchChip(background: String, ink: String) {
    card(background = background, padding = 8) {
        text("Aa", color = ink, size = 12)
    }
}

private fun OverlayNodeScope.dockIcon(icon: String, label: String) {
    column(gap = 4, align = "center") {
        image(icon, size = 20)
        text(label, color = NEBULA_MUTED, size = 10)
    }
}
