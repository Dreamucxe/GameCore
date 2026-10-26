# Nebula Interface Suite

A data-only GameCore 3.5 (versionCode 43) plugin: one schema-4 glass theme, seven read-only
reference screens, five dashboard widgets and one real control (`glass_intensity`).

## Install

1. Copy `nebula.gcplugin` to the phone (it is already at `/sdcard/Download/nebula.gcplugin`).
2. Open it with GameCore, or use **Plugins → Install from file**.
3. Enable **Nebula Interface Suite**, grant the requested permissions, and open its screens.
4. The live control is **Settings → Plugins → Nebula Interface Suite → glass_intensity**, also
   surfaced by GameCore's **Glass Control Center**. The static slider on the Control Center screen
   is a readout, not a control.

## Contents

- Theme: true-black AMOLED base, near-black glass surface, orchid `#C9B8FF` primary, rose
  `#FF9DCE` accent, iris `#8B7CFF` secondary, sky `#7DD3FC` tertiary, 28dp corners, rounded face,
  medium weight, schema-4 glass with 18dp authored blur, 7.2s sheen and a gentle spring.
- Screens: Control Center, Look & Feel, Motion Lab, Dock & Density, Type & Palette, Performance
  Modes, Design Notes. 441 nodes total.
- Widgets: Nebula Command, Nebula Vitals, Nebula Palette, Nebula Motion, Nebula Dock. 103 nodes
  total.
- Permissions: `change_ui`, `add_dashboard_widgets`, `store_data`, `read_approved_data`
  (`device_stats` + `thermals`), `change_icon`.

## What is real

Theme repaint, semantic roles, typography, live screen/widget figures, the five dashboard cards,
and the `glass_intensity` slider. The package carries JSON only: no classes, scripts, network
access, WebView or arbitrary host behaviour.

Theme cards, palette swatches, dock-size studies, performance-mode cards and the static glass bar
are **previews**. They are labelled as such in the screens. The format carries one theme per
package, one global corner radius and UI scale, and screens/widgets dispatch no actions. GameCore's
own performance engine, animation accessibility setting and density setting always win.

The bundled source copy under `app/src/main/assets/plugins/nebula/` carries one representative
widget (`command`) because the bundled loader reads one `widgets.json` per plugin. The
`.gcplugin` carries all five.

SHA-256: see `nebula.gcplugin.sha256`.
