package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ScreenNodeScope
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.ThemeWeights
import com.gamecore.plugin.sdk.plugin

/**
 * "Anime Interface Pack" — a vivid, anime-inspired reskin of GameCore (hot-pink primary, cyan
 * accent, a violet third pop on deep-plum surfaces) plus two read-only *reference screens* that
 * show the look off.
 *
 * Like [cyberpunkGameCore], [comicGameCore] and [liquidGlassGameCore], this is a worked authoring
 * example that lives *beside* [com.gamecore.plugin.sdk.Templates] in its own file, so it can be
 * added without editing the shared template library. It carries **data only**: one
 * [Permissions.CHANGE_UI] grant, a `theme.json`, and a `screens/screens.json`. There is no rule,
 * no overlay, no scoped data read, and — because the package format admits no code or asset entry —
 * no shipped font, no artwork and no script. The whole deliverable is two JSON files.
 *
 * ### What it does, and what it honestly cannot
 * "Anime" here is a *palette and layout language*, not content. The pack ships **no anime artwork,
 * no characters and no franchise anything** — none could be carried even if it wanted to, since an
 * `image` names one of GameCore's own built-in vectors ([OverlayIcons]) and the loader reads no
 * plugin image bytes from anywhere. The display look leans on `rounded`, a bundled face the host
 * maps to its clean sans; **no anime display font ships** (the format admits no font file, and a
 * brand face could not be redistributed anyway). The two screens are **read-only**: the data-only
 * host renders them under the active theme, so any interactive node would draw inert — this pack
 * deliberately uses none, only `card`/`column`/`row`/`grid`/`scroll`/`text`/`stat`/`image`/`spacer`.
 * The `stat` tiles on the profile screen draw an em-dash **absent** for now (the live screen-stat
 * feed lands in a later host phase) and this pack asks only for [Permissions.CHANGE_UI], not
 * `read_approved_data`, so the stat grid is a faithful layout reference rather than a live readout —
 * the screen itself says so.
 *
 * ### Palette -> the six theme roles
 * The host repaints itself from exactly six colours (see the host's `PluginTheme`). The anime
 * palette maps onto them; every enforced text/fill pair clears WCAG AA (4.5:1), and the two body
 * pairs clear AAA:
 *
 *  - body ink (`onSurface` #FFE6F3) on `surface` #241026     -> ~15.2:1 (AAA)
 *  - body ink on `background` #0E0A12                         -> ~16.7:1 (AAA)
 *  - dark ink (`onPrimary` #1A0A14) on `primary` pink #FF4FA3 -> ~6.29:1 (AA)
 *
 * `accent` cyan #38F0E0 seeds Material's derived secondary/tertiary; the v2 `roles` block below
 * pins those explicitly rather than leaving them to derivation.
 *
 * ### Schema-2 extras (so `api` is already 2 before the screens raise it to 3)
 *  - **`roles`** names the anime triad the six-role model cannot otherwise express: a cyan
 *    `secondary`, a violet `tertiary` (#B16CFF — the third vivid pop), and a muted pink-violet
 *    `outline` (#5A2E52) for borders. The four roles left unnamed inherit the house scheme.
 *  - **`typography`** sets one global `weight` of `medium`, for the punchier, poster-like weight an
 *    anime UI wants, while leaving the ramp's own `scale` untouched (a theme leans the type, it does
 *    not rebuild it).
 *
 * ### Format gaps (closest supported option used; see the task report)
 *  - A **cel-shaded / speed-line / halftone** look, glow, gradients and per-screen palettes have no
 *    theme tokens -> omitted, not faked. The surfaces are solid plum, the closest flat stand-in.
 *  - **Corners** are one global `cornerRadiusDp`; it is set high (24, within `ThemeBounds` 0..48)
 *    for the soft, rounded card language, since per-component radii are not expressible.
 *  - **`uiScale` stays 1.0**: it is a global density multiplier on top of the user's own
 *    accessibility setting, not a layout tool, so the user's density is left untouched.
 */
fun animeInterfacePack(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.anime",
    name = "Anime Interface Pack",
) {
    version = 1
    versionName = "1.0.0"
    // minHostVersion keeps the SDK default (Sdk.HOST_VERSION_CODE, 28). `api` is NOT set by hand:
    // the schema-2 theme raises it to 2 and the screens section raises it to 3, so it lands at 3.
    developer = "Dream"
    description = "Vivid anime-inspired theme (pink, cyan, violet on plum) with two reference screens."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)

    theme {
        name = "Anime Interface Pack"
        dark = true
        primary = "#FF4FA3"       // hot anime pink — hero fills, selected states
        onPrimary = "#1A0A14"     // near-black plum ink on pink (~6.29:1)
        background = "#0E0A12"    // deep plum-black page
        surface = "#241026"       // raised plum panels
        onSurface = "#FFE6F3"     // pink-tinted near-white body ink (~15.2:1 on surface)
        accent = "#38F0E0"        // electric cyan — seeds Material's derived secondary/tertiary
        cornerRadiusDp = 24       // high, soft rounded-card language (host clamps to 0..48)
        font = ThemeFonts.ROUNDED // bundled rounded sans — nearest clean face the format allows
        uiScale = 1.0

        // Schema-2: pin the anime pink/cyan/violet triad the six-role model cannot carry on its own.
        roles {
            secondary = "#38F0E0" // cyan, matched to the accent family
            tertiary = "#B16CFF"  // violet — the third vivid pop
            outline = "#5A2E52"   // muted pink-violet hairline for borders
        }
        // Schema-2: one global weight for the punchier, poster-like anime feel; ramp scale untouched.
        typography {
            weight = ThemeWeights.MEDIUM
        }
    }

    screens {
        // Screen 1 — a truthful theme showcase: palette swatches and type specimens that render
        // fully and correctly today, with zero inert placeholders.
        screen("showcase", "Interface Showcase", icon = OverlayIcons.INSIGHTS) {
            scroll(gap = 16, padding = 20) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.EXTENSION, size = 28)
                    text("Anime Interface Pack", size = 24, bold = true, color = "#FF4FA3")
                }
                text(
                    "A vivid, anime-inspired reskin: hot pink, electric cyan and violet on deep plum.",
                    color = "#FFE6F3",
                )

                text("Palette", size = 18, bold = true, color = "#38F0E0")
                grid(columns = 2, gap = 12) {
                    card(background = "#FF4FA3", padding = 14) {
                        text("Primary", color = "#1A0A14", bold = true)
                    }
                    card(background = "#38F0E0", padding = 14) {
                        text("Accent", color = "#0E0A12", bold = true)
                    }
                    card(background = "#B16CFF", padding = 14) {
                        text("Tertiary", color = "#140A1F", bold = true)
                    }
                    card(background = "#241026", padding = 14) {
                        text("Surface", color = "#FFE6F3", bold = true)
                    }
                }

                text("Type", size = 18, bold = true, color = "#38F0E0")
                card(background = "#241026", padding = 16, gap = 6) {
                    text("Display", size = 24, bold = true, color = "#FFE6F3")
                    text("Title", size = 20, bold = true, color = "#FFE6F3")
                    text("Body text — rounded face, medium weight.", size = 15, color = "#FFE6F3")
                }

                row(gap = 10, align = "center") {
                    image(OverlayIcons.GAME, size = 20)
                    image(OverlayIcons.BOLT, size = 20)
                    image(OverlayIcons.SHIELD, size = 20)
                    image(OverlayIcons.INSIGHTS, size = 20)
                    text("Built-in icons only", color = "#FFE6F3")
                }
            }
        }

        // Screen 2 — a character-sheet-style status HUD: the brief's stat grid, laid out in the
        // pack's look. The stats are GameCore's own real readings; they draw absent (a dash) until
        // the host feeds screen stats, and the note at the foot says so plainly.
        screen("profile", "Status Panel", icon = OverlayIcons.GAME) {
            scroll(gap = 16, padding = 20) {
                row(gap = 12, align = "center") {
                    image(OverlayIcons.GAME, size = 28)
                    text("Status Panel", size = 24, bold = true, color = "#FF4FA3")
                }
                text(
                    "A character-sheet-style HUD, laid out in the Anime Interface Pack look.",
                    color = "#FFE6F3",
                )

                card(background = "#241026", padding = 16, gap = 12) {
                    text("Vitals", size = 18, bold = true, color = "#38F0E0")
                    grid(columns = 2, gap = 12) {
                        statTile(OverlayIcons.SPEED, "Frame Rate", OverlayStats.FPS)
                        statTile(OverlayIcons.DATA, "CPU Load", OverlayStats.CPU)
                        statTile(OverlayIcons.BOLT, "GPU Load", OverlayStats.GPU)
                        statTile(OverlayIcons.MEMORY, "Memory", OverlayStats.RAM)
                        statTile(OverlayIcons.BATTERY, "Battery", OverlayStats.BATTERY)
                        statTile(OverlayIcons.THERMOSTAT, "Temp", OverlayStats.TEMP)
                    }
                }

                card(background = "#241026", padding = 14, gap = 4) {
                    row(gap = 8, align = "center") {
                        image(OverlayIcons.INFO, size = 18)
                        text("Live values", bold = true, color = "#38F0E0")
                    }
                    text(
                        "Readings show as a dash for now. GameCore feeds screen stats in a later " +
                            "update, and this pack requests appearance only (no data access), so the " +
                            "grid is a layout reference rather than a live readout.",
                        color = "#FFE6F3",
                        size = 13,
                    )
                }
            }
        }
    }
}

/**
 * One stat tile on the profile screen: an icon-and-label row above the figure itself.
 *
 * Factored out because the six tiles differ only in their icon, label and stat id. It is an
 * extension on [ScreenNodeScope] so it reads as just another node inside a `grid { }` block, and it
 * adds exactly one `card` to whatever scope calls it. The [stat] draws absent (an em-dash) in this
 * host phase; see the type note on [ScreenNodeScope.stat].
 */
private fun ScreenNodeScope.statTile(icon: String, label: String, stat: String) {
    card(background = "#2E1530", padding = 12, gap = 6) {
        row(gap = 8, align = "center") {
            image(icon, size = 18)
            text(label, color = "#D9A6C6", size = 13)
        }
        stat(stat, size = 22, color = "#FFE6F3")
    }
}
