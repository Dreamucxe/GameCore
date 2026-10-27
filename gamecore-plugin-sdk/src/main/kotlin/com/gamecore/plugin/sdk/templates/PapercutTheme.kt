package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Papercut" — a genuinely different light theme: warm paper, dark ink, ink-red and teal, sharp
 * corners and a serif face.
 *
 * Most light themes in the app are white panels with a cool grey scaffold. Papercut starts from
 * the other direction: an off-white paper ground, a cream surface, near-black ink and a small
 * amount of red and teal for emphasis. The shape ladder is deliberately tight (`cornerRadiusDp`
 * 4), so panels read as cut paper rather than rounded cards.
 *
 * **No textures.** The package format admits no artwork, so the "paper" is colour and type, not
 * a bitmap. The limits screen says that plainly. The package is data only: one schema-2 theme,
 * three read-only screens and an icon request.
 */
fun papercutTheme(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.papercut",
    name = "Papercut",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A warm paper light theme with ink-red and teal, sharp corners and serif type. No " +
            "textures or images — the paper is colour and type."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Papercut"
        dark = false
        primary = "#A63A2B"        // ink red — primary actions
        onPrimary = "#FFFFFF"      // white ink on red
        background = "#F3EDE2"     // warm paper ground
        surface = "#FFFDF7"        // cream panel
        onSurface = "#1C1917"      // near-black ink
        accent = "#2F5D62"         // deep teal
        cornerRadiusDp = 4
        font = ThemeFonts.SERIF
        uiScale = 1.0

        roles {
            secondary = "#2F5D62"
            tertiary = "#C47A2C"
            surfaceVariant = "#EAE1D3"
            onSurfaceVariant = "#4A423A"
            outline = "#8C7B68"
            error = "#B42318"
            onError = "#FFFFFF"
        }
        typography {
            weight = ThemeWeights.NORMAL
        }
    }

    screens {
        screen("overview", "Papercut", icon = OverlayIcons.INSIGHTS) {
            column(gap = 12, padding = 16) {
                text("Papercut", size = 22, bold = true)
                text(
                    "A light theme that starts from warm paper rather than cool white. Serif " +
                        "type, tight corners and two accent inks carry the look.",
                    size = 15,
                )
                card(background = "#FFFDF7", padding = 14, gap = 10) {
                    text("The palette", size = 16, bold = true)
                    row(gap = 10, align = "center") {
                        card(background = "#A63A2B", padding = 10) {
                            text("Ink red", color = "#FFFFFF", size = 12, bold = true)
                        }
                        card(background = "#2F5D62", padding = 10) {
                            text("Teal", color = "#FFFFFF", size = 12, bold = true)
                        }
                        card(background = "#F3EDE2", padding = 10) {
                            text("Paper", color = "#1C1917", size = 12, bold = true)
                        }
                    }
                    text(
                        "Red is the primary action; teal is the second voice; the paper ground " +
                            "keeps long text comfortable in daylight.",
                        size = 14,
                    )
                }
                card(background = "#FFFDF7", padding = 14, gap = 8) {
                    text("Typography", size = 16, bold = true)
                    text("Display", size = 26, bold = true)
                    text("Section heading", size = 18, bold = true)
                    text(
                        "Body copy is set in the same serif face, with a normal weight. The " +
                            "hierarchy comes from size and spacing, not from a second font.",
                        size = 14,
                    )
                }
            }
        }

        screen("printshop", "Print Shop", icon = OverlayIcons.DATA) {
            column(gap = 12, padding = 16) {
                text("A small print-shop grammar", size = 20, bold = true)
                card(background = "#FFFDF7", padding = 14, gap = 8) {
                    text("One loud voice", size = 16, bold = true)
                    text(
                        "A screen gets one red action. If everything is ink-red, nothing is.",
                        size = 14,
                    )
                }
                card(background = "#EAE1D3", padding = 14, gap = 8) {
                    text("Paper, not glass", size = 16, bold = true)
                    text(
                        "Surfaces are cream and the corners are tight. There is no translucency " +
                            "and no blur; the theme is meant to feel printed, not layered.",
                        size = 14,
                    )
                }
                grid(columns = 2, gap = 8) {
                    card(background = "#FFFDF7", padding = 12, gap = 6) {
                        image(OverlayIcons.CHECK, size = 24)
                        text("Clear labels", size = 13, bold = true)
                    }
                    card(background = "#FFFDF7", padding = 12, gap = 6) {
                        image(OverlayIcons.TIMELINE, size = 24)
                        text("Generous spacing", size = 13, bold = true)
                    }
                    card(background = "#FFFDF7", padding = 12, gap = 6) {
                        image(OverlayIcons.INSIGHTS, size = 24)
                        text("One accent at a time", size = 13, bold = true)
                    }
                    card(background = "#FFFDF7", padding = 12, gap = 6) {
                        image(OverlayIcons.SHIELD, size = 24)
                        text("No hidden colour code", size = 13, bold = true)
                    }
                }
            }
        }

        screen("limits", "Paper, Not A Texture", icon = OverlayIcons.INFO) {
            column(gap = 12, padding = 16) {
                text("What the paper is", size = 20, bold = true)
                card(background = "#FFFDF7", padding = 14, gap = 8) {
                    text("Colour and type", size = 16, bold = true)
                    text(
                        "The warm ground, cream surfaces, serif face and tight corners are the " +
                            "whole effect. A `.gcplugin` cannot ship a paper texture, a font " +
                            "file or an arbitrary image.",
                        size = 14,
                    )
                }
                card(background = "#FFFDF7", padding = 14, gap = 8) {
                    text("Why a light theme", size = 16, bold = true)
                    text(
                        "Some rooms and some eyes are better served by a light surface. This " +
                            "theme is not a dimmed dark theme; it is designed from the paper up.",
                        size = 14,
                    )
                }
                text(
                    "If you need a darker interface at night, use Night Shift or a dark theme. " +
                        "Papercut is deliberately a daylight palette.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.DEFAULT)
}
