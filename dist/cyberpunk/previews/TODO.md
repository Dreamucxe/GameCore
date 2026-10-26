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
palette (see `CyberpunkThemePluginTest.body_text_pairs_clear_wcag_aa`). Measured ratios:

| Pair | Ratio | Grade |
| ---- | ----- | ----- |
| text `#E6F1FF` on surface `#12121F` | 16.25:1 | AAA |
| text `#E6F1FF` on background `#0A0A12` | 17.27:1 | AAA |
| ink `#0A0A12` on primary cyan `#00F0FF` | 14.00:1 | AAA |

## How to produce the screenshots on a device

1. Build and install the debug APK.
2. Settings → Plugins → Developer mode → **Load local plugin** → pick `cyberpunk.gcplugin`.
3. Enable it, then capture Home, Games and Settings; drop the PNGs in this folder as
   `home.png`, `games.png`, `settings.png` (each ≤ 2 MiB if later bundled under `previews/`).
