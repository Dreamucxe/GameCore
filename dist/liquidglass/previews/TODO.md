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
palette (see `LiquidGlassThemePluginTest.body_text_pairs_clear_wcag_aa`). Measured ratios:

| Pair | Ratio | Grade |
| ---- | ----- | ----- |
| white ink `#FFFFFF` on surface `#1C1C1E` | 17.01:1 | AAA |
| white ink `#FFFFFF` on background `#000000` | 21.00:1 | AAA |
| white label `#FFFFFF` on primary blue `#0A6FE0` | 4.81:1 | AA |

The two ink pairs clear AAA (7:1); white-on-blue clears AA with +0.31 of headroom over the 4.5
floor. `primary` is **not** the brief's literal iOS blue: white-on-`#0A84FF` (iOS dark blue) is
only 3.65:1 and white-on-`#007AFF` (iOS light blue) 4.02:1 — both FAIL AA. Per the brief's own
"verify and adjust for contrast / darken anything that fails" rule the hue is deepened the
minimum needed to clear AA, to `#0A6FE0` (4.81:1). The AAA option (~`#0062CC`, 5.80:1) is
recorded in the task report but not used — it costs vividness for margin the brief does not
require.

## What a screenshot could NOT show (format gaps, not render gaps)

Even on a device, the liquid-glass look is bounded by the 11-key theme format, so these never
appear: the translucent "liquid glass" material, backdrop blur, the specular-rim gradient
border, soft drop shadow and the inner top-edge highlight; continuous/squircle corners and
per-component radii (capsule buttons, 28dp sheets); the SF-Pro/Inter type scale (Large Title 34
… Caption 12), weights and tabular numerals; the semantic accent SET (green/red/orange/yellow)
and the raised-row tone `#2C2C2E`; segmented controls, grouped inset lists and grabber sheets;
spring motion; and the "reduce transparency" / "remove animations" respects. The surface is a
single opaque near-black `#1C1C1E` — the closest flat stand-in for frosted glass — and a light
companion palette cannot ship in the same plugin (one `dark` boolean = one palette). See the
task report's gap list and candidate core changes.

## How to produce the screenshots on a device

1. Build and install the debug APK.
2. Settings → Plugins → Developer mode → **Load local plugin** → pick `liquidglass.gcplugin`.
3. Enable it, then capture Home, Games and Settings; drop the PNGs in this folder as
   `home.png`, `games.png`, `settings.png` (each ≤ 2 MiB if later bundled under `previews/`).
