package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Night Shift" — a warm amber-on-black interface for late sessions.
 *
 * The palette removes the cool blue-white cast most dark themes carry: the background is a
 * near-black brown, surfaces are warm charcoal, ink is a soft cream, and the primary/accent
 * pair is amber and burnt orange. Type is set in GameCore's bundled serif face, which gives the
 * interface a quieter, paper-like rhythm without shipping a font file.
 *
 * **What this is, and is not.** This is a colour and type theme. It is *not* a hardware
 * blue-light filter: Android exposes no plugin API that changes the display's colour
 * temperature, and a `.gcplugin` carries no code that could. The two read-only screens say so
 * plainly, so a user who wants a system-wide night filter knows where the boundary is.
 *
 * The package is data only: one `theme.json`, one `screens/screens.json`, one `icon.json`, and
 * the two grants those sections need. No rules, overlay, settings, data scope or code.
 */
fun nightShiftTheme(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.nightshift",
    name = "Night Shift",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A warm amber-on-black interface with serif type for late sessions. A palette, not a " +
            "hardware blue-light filter."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Night Shift"
        dark = true
        primary = "#FFB000"        // amber — selected states and primary fills
        onPrimary = "#1A1000"      // near-black ink on amber
        background = "#050403"     // warm near-black, not a blue-black
        surface = "#141008"        // raised warm charcoal
        onSurface = "#F5E6C8"      // soft cream ink
        accent = "#FF8A00"         // burnt orange for secondary emphasis
        cornerRadiusDp = 10
        font = ThemeFonts.SERIF
        uiScale = 1.0

        roles {
            secondary = "#D08A3C"
            tertiary = "#9A6A2E"
            surfaceVariant = "#1E160C"
            onSurfaceVariant = "#D8C49A"
            outline = "#5A4528"
            error = "#E06C5A"
            onError = "#1A0B08"
        }
        typography {
            weight = ThemeWeights.NORMAL
        }
    }

    screens {
        screen("overview", "Night Shift", icon = OverlayIcons.INSIGHTS) {
            column(gap = 12, padding = 16) {
                text("Night Shift", size = 22, bold = true)
                text(
                    "A warm amber-on-black palette with serif type. It is designed to feel " +
                        "quieter at night than a cool blue-white dark theme.",
                    size = 15,
                )
                card(padding = 14, gap = 10) {
                    text("The palette", size = 16, bold = true)
                    row(gap = 10, align = "center") {
                        card(background = "#FFB000", padding = 10) {
                            text("Primary", color = "#1A1000", size = 12, bold = true)
                        }
                        card(background = "#FF8A00", padding = 10) {
                            text("Accent", color = "#1A1000", size = 12, bold = true)
                        }
                    }
                    text(
                        "Amber and burnt orange carry emphasis. Warm charcoal surfaces keep the " +
                            "screen dark without the blue cast of a pure neutral black.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("What it changes", size = 16, bold = true)
                    text("The app's colours, corner rounding and type.", size = 14)
                    text("The launcher icon, if you accept the plugin's icon request.", size = 14)
                }
            }
        }

        screen("bedtime", "Bedtime Notes", icon = OverlayIcons.INFO) {
            column(gap = 12, padding = 16) {
                text("A palette, not a filter", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text(
                        "Night Shift changes the colours GameCore draws. It cannot change the " +
                            "display's colour temperature or remove blue light from other apps.",
                        size = 14,
                    )
                    text(
                        "For a system-wide night filter, use Android's Night Light or your " +
                            "device's display settings. GameCore has no plugin API for that.",
                        size = 14,
                    )
                }
                text("A small bedtime checklist", size = 16, bold = true)
                grid(columns = 2, gap = 8) {
                    card(padding = 10, gap = 6) {
                        image(OverlayIcons.TIMER, size = 24)
                        text("Dim the room", size = 13, bold = true)
                    }
                    card(padding = 10, gap = 6) {
                        image(OverlayIcons.BATTERY, size = 24)
                        text("Charge nearby", size = 13, bold = true)
                    }
                    card(padding = 10, gap = 6) {
                        image(OverlayIcons.INFO, size = 24)
                        text("Silence alerts", size = 13, bold = true)
                    }
                    card(padding = 10, gap = 6) {
                        image(OverlayIcons.CHECK, size = 24)
                        text("Stop at a set time", size = 13, bold = true)
                    }
                }
                text(
                    "The theme is one part of the environment. The rest is the room, the " +
                        "notifications and the clock.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.MONO)
}
