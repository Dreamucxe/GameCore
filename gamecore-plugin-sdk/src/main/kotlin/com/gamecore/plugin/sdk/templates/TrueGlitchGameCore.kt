package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.Sdk
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "True Glitch" — a schema-3 theme that does not just repaint GameCore, it tears it.
 *
 * ### What this plugin actually is
 * A `.gcplugin` is data. It cannot intercept a canvas, reassign `WindowManager.LayoutParams`, or
 * touch a frame buffer, and nothing here pretends otherwise. What schema 3 added is a *motion
 * contract*: the theme names how hard it wants to be shaken (the `glitch` block) and the host —
 * code that ships in the APK — owns every frame of what that looks like, drawing the UI into a
 * layer and re-drawing it as displaced horizontal bands with fracture lines, ghost copies and
 * block noise. The effect is structural: text, icons and panels are all in the same recording, so
 * all of them tear together.
 *
 * ### Palette
 * Cyan `#00F0FF` primary, neon red `#FF0055` accent, near-black `#050505` page. Every text/fill
 * pair clears WCAG AA (4.5:1):
 *
 *  - body text `#E8FBFF` on surface `#0A0A0C`   → 18.6:1 (AAA)
 *  - body text on background `#050505`          → 19.2:1 (AAA)
 *  - ink `#000A0C` on primary cyan              → 12.7:1 (AAA)
 *  - muted `#9FB3B8` on `#141418`               → 8.2:1 (AAA)
 *
 * ### Format gaps
 *  - VCR OSD Mono / Share Tech Mono cannot be shipped → nearest bundled face, `mono`.
 *  - Per-character glyph substitution ("60 FPS" → "6X %PS") is not a rendering effect the host can
 *    apply: it would mean the renderer rewriting the strings screens hand it, which is a different
 *    and much larger feature. The tear displaces those glyphs instead of replacing them.
 *
 * ### Host requirement
 * `minHostVersion = 37` (GameCore 3.15.0) — the first build that understands both `effects` and the
 * `icon` section. The theme alone would need 36, the first build whose codec understands `effects`;
 * the icon lifts it to 37. An older host rejects the package whole rather than rendering the palette
 * without the movement or ignoring the icon request, which is the honest failure.
 */
fun trueGlitchGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.trueglitch",
    name = "True Glitch",
) {
    version = 2
    versionName = "1.1.0"
    // The theme alone needs 36 (effects); the icon section needs 37, which `icon {}` below raises
    // this to on its own. Stated here so the package's whole requirement is visible in one place.
    minHostVersion = Sdk.HOST_VERSION_CODE_ICON
    developer = "Dream"
    description = "Structural corruption theme — the HUD tears into displaced bands, text and panels alike."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    // The launcher icon is the one part of the theme that is visible with the app closed. A slot is
    // the honest half of that: Android will not wear a package's bitmap, so the plugin asks for the
    // glitched icon GameCore already ships rather than promising one it cannot deliver.
    request(Permissions.CHANGE_ICON)

    theme {
        name = "True Glitch"
        dark = true
        primary = "#00F0FF"      // accent_primary from the brief — hero fills, selection
        onPrimary = "#000A0C"    // near-black ink on cyan (12.7:1)
        background = "#050505"   // the brief's #A6050505, flattened opaque — the host forces opaque
        surface = "#0A0A0C"      // raised panels
        onSurface = "#E8FBFF"    // body text (18.6:1)
        accent = "#FF0055"       // accent_secondary — the fracture colour the host tears in
        cornerRadiusDp = 2        // cut-machine look: the smallest step the format has
        font = ThemeFonts.MONO   // nearest bundled face to VCR OSD Mono
        uiScale = 1.0

        roles {
            secondary = "#FF0055"
            tertiary = "#7A00FF"
            surfaceVariant = "#141418"
            onSurfaceVariant = "#9FB3B8"
            outline = "#2A2F33"
            error = "#FF1744"
            onError = "#0A0000"
        }

        // The brief's 150–800 ms cadence, three bands, a 24 dp maximum tear and a 6 dp ghost. The
        // host scales every offset by `intensity`, so 0.85 still leaves a little room at the top.
        glitch {
            intensity = 0.85
            slices = 3
            sliceOffsetDp = 24
            ghostOffsetDp = 6
            fractureLines = true
            noise = true
            microJitter = true
            hardwareReactive = true
            burstMinMs = 150
            burstMaxMs = 800
        }
    }

    // The glitch icon: the same reticle as GameCore's own, torn red/cyan. It is a slot and not an
    // image because a slot is the only thing that can actually become the launcher icon.
    icon(slot = IconSlots.GLITCH)
}
