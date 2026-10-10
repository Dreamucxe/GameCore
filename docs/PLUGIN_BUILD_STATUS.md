# GameCore Plugin Platform — build status

Branch: `plugins-base` · shipping version **3.8 / versionCode 28**

## The one distinction this document exists to make

Everything in parts A–N is **written, parsed and validated**. Not everything is **executed**.

The plugin format, the loader, the permission model, the grant store, the theme, the install flow and
the overlay-module seam are wired end to end and run when the user taps. Three subsystems are complete
libraries with passing tests that no caller reaches yet — the Shizuku Action Broker, the automation
rules engine, and Aim Lab plugin content. The in-app changelog (`WhatsNewRegistry`, vc 28) advertises
only what the app can perform, because a release note claiming a feature the build cannot do is a lie
about the build.

| Part | What it is | State |
| --- | --- | --- |
| A | `.gcplugin` zip format, manifest, versioned API | **Shipped** — install from file works |
| B | Loader / validator (zip-slip, byte + entry ceilings, SHA-256) | **Shipped** — runs on every install |
| C | Permission layer (gate, scopes, risk tiers, namespacing) | **Shipped** — grants are per-permission |
| D | Plugin API facade (rate limit, clamp, audit) | **Shipped** — the only surface a plugin has |
| E | Shizuku Action Broker (typed actions, auto-revert) | Written + tested — **no caller wired** |
| F | Declarative UI engine (`ComponentNode`, `ScreenValidator`) | **Shipped** — rendered in overlay modules |
| F′ | Theme bridge (`PluginTheme` → `MaterialTheme`) | **Shipped** — wired into `GameCoreTheme` |
| G | Content modules (Aim Lab validator, `StatExpression`, `AutomationEngine`) | Written + tested — **not executed** |
| G′ | Overlay-module seam (`activeOverlayModules` → `reconcile()`) | **Shipped** — the one core change |
| H | Management + supervisor (crash-loop, auto-suspend) | **Shipped** — Plugins screen |
| I | Preview / install flow | **Shipped** — `PluginDetailScreen` |
| J | Marketplace client (`MarketEntry`, integrity) | Written — **no network, no catalogue type** |
| K | Kotlin Plugin SDK (`:gamecore-plugin-sdk`) | **Shipped** — pure-JVM module, builds standalone |
| L | Developer mode (test-mode auto-revert) | Written + tested — **not reachable from UI** |
| M | Bundled plugins (Minimal / Pixel / Performance) | **Shipped** — in `assets/plugins/` |
| N | Tests | Per-part, pure JVM |

## What the user can actually do in 3.8

1. Settings → Access and tools → **Plugins**.
2. Install a plugin from a file; see its declared permissions tiered by risk and its compatibility verdict.
3. Grant each permission separately; device-settings access needs a second opt-in.
4. Turn a plugin on or off; remove a sideloaded one (bundled ones cannot be removed).
5. Pick a plugin theme; unreadable colour pairs are substituted automatically; reset to GameCore's own.
6. Grant **Show an overlay** to a plugin that declares `pill.json`, and see its module over a running
   game — up to three at once, alongside the Pill and HUD, never taking a touch from the game.
7. Flip the global kill switch, which stops every plugin whatever its own switch says.

The two plugin screens are rendered in **GameCore's own theme even when a plugin theme is active** — a
plugin does not get to paint the screen that turns it off. See `GameCoreRoot`'s registration.

## The one core change (the "no core modification" deviation)

`docs/PLUGIN_SEAMS.md` §1 records why it was unavoidable: `OverlaySlot` is a closed enum and
`OverlayRequest` a closed set of booleans, so a data-only plugin could not add a Pill/overlay module
without one generic seam. §1a records exactly what landed — three `PLUGIN_MODULE` slots, one
`showPluginModules()` branch in `reconcile()`, one registry `StateFlow`, and a fourth term on the
sampling gate. **`OverlayRequest`, `OverlayController` and the settings model were not touched**: a
module rides along with a session that is already up and cannot start or keep alive the service.
This is the only core change the platform needs, and it is the single overlay extension point.

## Bundled plugins (part M)

`app/src/main/assets/plugins/<dir>/` — loose JSON files rather than zips, so the plugins GameCore
itself ships are the ones a developer can open in a text editor. They go through the **same** codecs a
sideloaded `.gcplugin` does; "we shipped it" is not a security property, and a bundled example that
skipped validation is how the example becomes the hole. `BundledPluginsTest` parses every one of these
files with the production codecs, so a typo fails the gate instead of shipping as a broken default.

| Directory | Declares | Demonstrates |
| --- | --- | --- |
| `minimal/` | `theme` | The floor: five required keys, one permission, the short (bare-string) permission form. The file to copy. |
| `pixel/` | `theme` | A theme that changes more than colour — `mono` font, zero corner radius — and the long (object) permission form. |
| `performance/` | `rules`, `pill` | Four automation rules over real broker action ids, and an overlay module reading frame rate, CPU and temperature. |

Only `performance` asks for anything above LOW risk, and it asks for all of it explicitly:
`system_actions` (HIGH, behind the second opt-in), `run_automations`, `create_overlay` and
`read_approved_data` scoped to `device_stats` + `thermals`. Nothing is granted by being bundled — each
one starts off and needs the same per-permission tap as a downloaded plugin.

## Deliberately out of scope this run

No `DexClassLoader`, no in-process third-party code, no `WebView`, no scripting. A plugin is data. No
screen-capture, screen-reading or accessibility-reading API is exposed to plugins; `NETWORK` and
`EXTERNAL_APP` are reserved and rejected by the validator.

## Build state

- `:app` — single Gradle module, `assembleDebug` + `testDebugUnitTest` gate.
- `:gamecore-plugin-sdk` — pure Kotlin/JVM, independent of `:app`.
- Debug APK and signed release APK both copied to `/sdcard/Download`.
