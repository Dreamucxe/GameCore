package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Colorblind Safe" — a high-contrast dark theme built around blue and amber rather than
 * red-versus-green.
 *
 * The palette is not a medical guarantee and does not simulate a specific type of colour vision
 * deficiency. It is a design choice with two practical properties: the primary/accent pair sits
 * on opposite sides of the blue-yellow axis, where the common red-green deficiencies retain more
 * separation, and no state is signalled by hue alone. The screens name each colour's job in words
 * as well as showing the swatch, which is the part a theme can actually enforce.
 *
 * The package is data only: a schema-2 theme, three read-only screens and a launcher icon.
 */
fun colorblindSafeTheme(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.colorblindsafe",
    name = "Colorblind Safe",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A high-contrast blue-and-amber theme that avoids red-green-only signalling, with " +
            "read-only screens explaining each colour's job."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    theme {
        name = "Colorblind Safe"
        dark = true
        primary = "#2563EB"        // blue — primary actions
        onPrimary = "#FFFFFF"      // white ink on blue
        background = "#071018"     // deep blue-black
        surface = "#101D28"        // raised navy surface
        onSurface = "#F2F7FA"      // near-white ink
        accent = "#F59E0B"         // amber — attention and highlights
        cornerRadiusDp = 14
        font = ThemeFonts.SYSTEM
        uiScale = 1.0

        roles {
            secondary = "#F59E0B"
            tertiary = "#A78BFA"
            surfaceVariant = "#172A38"
            onSurfaceVariant = "#C7D6E2"
            outline = "#4B6478"
            error = "#DC2626"
            onError = "#FFFFFF"
        }
        typography {
            weight = ThemeWeights.MEDIUM
        }
    }

    screens {
        screen("overview", "Colorblind Safe", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("Colorblind Safe", size = 22, bold = true)
                text(
                    "A dark theme that uses blue for primary actions and amber for attention, " +
                        "instead of asking red and green to carry the whole message.",
                    size = 15,
                )
                card(padding = 14, gap = 10) {
                    text("The signal colours", size = 16, bold = true)
                    grid(columns = 3, gap = 8) {
                        card(background = "#2563EB", padding = 10, gap = 4) {
                            text("Blue", color = "#FFFFFF", size = 13, bold = true)
                            text("Primary", color = "#FFFFFF", size = 11)
                        }
                        card(background = "#F59E0B", padding = 10, gap = 4) {
                            text("Amber", color = "#1A1000", size = 13, bold = true)
                            text("Attention", color = "#1A1000", size = 11)
                        }
                        card(background = "#DC2626", padding = 10, gap = 4) {
                            text("Red", color = "#FFFFFF", size = 13, bold = true)
                            text("Destructive", color = "#FFFFFF", size = 11)
                        }
                    }
                    text(
                        "Each swatch is labelled in words. That is deliberate: a theme cannot " +
                            "guarantee how any one person sees a hue, but it can avoid making " +
                            "colour the only signal.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Why blue and amber", size = 16, bold = true)
                    text(
                        "The common red-green deficiencies separate blue from yellow more " +
                            "reliably than red from green. Using blue as the primary and amber " +
                            "as the accent keeps the two most important states on different " +
                            "sides of that axis.",
                        size = 14,
                    )
                }
            }
        }

        screen("signals", "Signals & Labels", icon = OverlayIcons.INFO) {
            column(gap = 12, padding = 16) {
                text("Signals and labels", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("Blue = do the main thing", size = 15, bold = true)
                    text(
                        "Primary buttons, selected tabs and active switches. The label still " +
                            "says what the control does.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Amber = look here", size = 15, bold = true)
                    text(
                        "Warnings, highlights and figures that need attention. Amber is paired " +
                            "with an icon or a word, never used as the only clue.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Red = destructive, and named", size = 15, bold = true)
                    text(
                        "Delete, remove and irreversible actions. The text says what will " +
                            "happen; the red is reinforcement, not the message.",
                        size = 14,
                    )
                }
                text(
                    "If a screen ever relies on colour alone, that is a bug in the screen, not " +
                        "a reason to distrust the palette.",
                    size = 14,
                )
            }
        }

        screen("limits", "What A Theme Can't Fix", icon = OverlayIcons.SHIELD) {
            column(gap = 12, padding = 16) {
                text("The honest limits", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("It cannot recolour another app", size = 15, bold = true)
                    text(
                        "GameCore can style its own interface. A game or another app draws its " +
                            "own colours, and no plugin can reach inside it.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("It is not a colour-vision simulator", size = 15, bold = true)
                    text(
                        "The palette is designed to be robust, not certified for every type or " +
                            "severity of colour vision deficiency. Contrast and labels do most " +
                            "of the work.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("System colour correction still matters", size = 15, bold = true)
                    text(
                        "Android's accessibility settings can apply a system-wide correction " +
                            "or a grayscale mode. Those are the right tools for other apps; " +
                            "this theme is the right tool for GameCore.",
                        size = 14,
                    )
                }
            }
        }
    }

    icon(slot = IconSlots.DEFAULT)
}
