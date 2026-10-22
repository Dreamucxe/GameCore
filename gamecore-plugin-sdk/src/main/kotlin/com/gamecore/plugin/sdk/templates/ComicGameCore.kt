package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.ThemeFonts
import com.gamecore.plugin.sdk.plugin

/**
 * "Comic GameCore" — a pure UI-theme plugin that repaints GameCore like a bright comic-book
 * page: warm paper background, white panels, bold near-black ink, a punch-red primary and a
 * comic-blue accent.
 *
 * Like [cyberpunkGameCore], this is a worked authoring example that lives *beside* [Templates]
 * in its own file, so it can be added without editing the shared template library.
 *
 * It carries **data only**: [Permissions.CHANGE_UI] and [Permissions.CHANGE_ICON] grants, one
 * `theme.json` and one `icon.json`. No rules, no overlay, no scoped data reads — a theme is the
 * lowest-risk thing a plugin can be, and the icon grant only lets it pick a launcher icon.
 *
 * ### Palette → the six theme roles
 * The host repaints itself from exactly six colours (see the host's `PluginTheme`). The brief's
 * light comic palette maps straight onto them; the extra brights (yellow #FFD60A, cyan #00B4D8,
 * pink #FF4D8D) have no theme role — only one [accent] exists and it merely SEEDS Material's
 * derived roles — so they are reported as gaps, not faked. Every enforced text/fill pair clears
 * WCAG AA (4.5:1); the two ink pairs clear AAA:
 *
 *  - ink (`onSurface` #111111) on `surface` #FFFFFF  → 18.88:1 (AAA)
 *  - ink on `background` paper #FFF8E7                → 17.83:1 (AAA)
 *  - white (`onPrimary`) on `primary` red #D00000     → 5.70:1  (AA, +1.20 over the 4.5 floor)
 *
 * `primary` is kept at the brief's exact #D00000: white-on-#D00000 is 5.70:1, which already
 * clears the brief's AA 4.5 bar, so the brief's "darken anything that fails" rule does not
 * trigger. (Deepening to ~#AD0000 would reach AAA 7:1 but is not required and costs palette
 * fidelity; it is noted in the task report as the AAA option.)
 *
 * ### Format gaps (closest supported option used; see the task report)
 *  - Bold 2–3dp black outlines, hard offset ink shadows, halftone texture, speech-bubble shapes,
 *    per-screen styling, pop motion and custom icons have NO tokens → omitted, not faked.
 *  - Bangers / Comic Neue cannot ship (the loader admits no font file) → nearest friendly
 *    bundled face, `rounded` (which the host maps to sans-serif). No font file means no OFL
 *    obligation.
 *  - Slightly-rounded comic panels → `cornerRadiusDp = 8` (global; scales the house shape ladder).
 *  - `uiScale` stays 1.0: it is a global density multiplier on top of the user's own
 *    accessibility setting, not a way to enlarge only stat numbers, so the user's density is
 *    left untouched rather than risk clipping.
 */
fun comicGameCore(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.comic",
    name = "Comic GameCore",
) {
    version = 2
    versionName = "1.1.0"
    // api keeps the SDK default (Sdk.API, 1). The icon section below raises minHostVersion to
    // Sdk.HOST_VERSION_CODE_ICON (37), the first host build that understands it.
    developer = "Dream"
    description = "Bright comic-book theme — bold ink on warm paper, punch red and comic blue."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)   // the launcher icon the plugin asks for

    theme {
        name = "Comic GameCore"
        dark = false
        primary = "#D00000"       // punch comic red — hero fills, selected states
        onPrimary = "#FFFFFF"     // white label on red (5.70:1)
        background = "#FFF8E7"    // warm comic paper
        surface = "#FFFFFF"       // white panels
        onSurface = "#111111"     // bold ink (18.88:1 on surface, 17.83:1 on paper)
        accent = "#1D4ED8"        // comic blue — seeds Material's derived secondary/tertiary
        cornerRadiusDp = 8        // slightly-rounded comic panels (global shape-ladder scale)
        font = ThemeFonts.ROUNDED // closest friendly bundled face (host maps it to sans-serif)
        uiScale = 1.0
    }

    // Redline is the plugin's own launcher entry — the slot is what changes the icon the launcher shows.
    icon(slot = IconSlots.REDLINE)
}
