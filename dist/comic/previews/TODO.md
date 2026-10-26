# Previews — TODO

The brief asks for three screenshots (Home, Games, Settings) showing the theme applied.
They are **not generated** in this build environment, for a concrete reason rather than an
omission:

- A theme only becomes pixels when GameCore's own Compose UI is recomposed with the plugin's
  `PluginTheme` mapped onto `MaterialTheme`. Rendering that requires a device or emulator (or a
  Compose screenshot-test harness such as Paparazzi/Roborazzi).
- This module's unit-test classpath is deliberately minimal — JUnit, kotlinx-coroutines-test and
  org.json only, with `isReturnDefaultValues = true`. There is no Robolectric, no
  `compose-ui-test`, and no screenshot library (see `docs/PLUGIN_OVERLAY_CONTRACT.md` §6). A
  `@Composable` cannot be rendered headlessly here.

## What is proven instead, numerically

The visual contract that screenshots would be *checked* for is verified by test rather than by
eye: every body-text / fill pair clears WCAG AA (4.5:1) via `ContrastMath` on the decoded
palette (see `ComicThemePluginTest.body_text_pairs_clear_wcag_aa`). Measured ratios:

| Pair | Ratio | Grade |
| ---- | ----- | ----- |
| ink `#111111` on surface `#FFFFFF` | 18.88:1 | AAA |
| ink `#111111` on background paper `#FFF8E7` | 17.83:1 | AAA |
| white `#FFFFFF` on primary red `#D00000` | 5.70:1 | AA |

The two ink pairs clear AAA (7:1); white-on-red clears AA with +1.20 of headroom over the 4.5
floor. `primary` is the brief's exact suggested red `#D00000` — it already passes AA, so the
brief's "darken anything that fails" rule does not trigger (deepening to `#AD0000` would reach
AAA 7.56:1 but is not required and costs palette fidelity).

## What a screenshot could NOT show (format gaps, not render gaps)

Even on a device, the comic look is bounded by the 11-key theme format, so these never appear:
bold 2–3dp black outlines, hard offset ink shadows, halftone dot texture, speech-bubble shapes,
per-screen styling, pop/overshoot motion, a Bangers/Comic Neue display face, and the extra
brights (yellow/cyan/pink) as placeable roles. See the task report's gap list.

## How to produce the screenshots on a device

1. Build and install the debug APK.
2. Settings → Plugins → Developer mode → **Load local plugin** → pick `comic.gcplugin`.
3. Enable it, then capture Home, Games and Settings; drop the PNGs in this folder as
   `home.png`, `games.png`, `settings.png` (each ≤ 2 MiB if later bundled under `previews/`).
