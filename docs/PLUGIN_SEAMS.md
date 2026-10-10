# Plugin Platform — Integration Seam Contracts (verified against source)

Produced by the "Understand" workflow (6 parallel Explore agents, run wf_f0bb24b7-204),
each grounded in real source under /root/GameCore. This is the ground truth Parts F–M
bind to. **Where the spec said "no core modification" and the source disagrees, the
source wins and the deviation is recorded here.**

## 1. Overlay / Game Pill  — THE ONE PLACE THAT NEEDS A NEW SEAM

- `core/overlay/OverlayWindows.kt`: `OverlaySlot` is a **closed enum** (BUTTON, PANEL,
  QUICK_SHEET, PILL, CROSSHAIR, HUD, MAGNIFIER, REPLAY, SCOUT, HUNT, WHEEL, DOCK,
  DOCK_PANEL, TRIGGER_PLACEMENT, TRIGGER_MARKER). One live `View` per slot. *Still closed
  after the change in §1a — it gained three plugin-module slots, not an open registry.*
- Dispatch chain: callers write `OverlayController.desired: StateFlow<OverlayRequest>`
  (`domain/overlay/OverlayController.kt`, @Singleton) → `GamingOverlayService.reconcile()`
  (`service/GamingOverlayService.kt`) runs one hardcoded `if (request.pill) …` branch
  per fixed `OverlayRequest` boolean → `OverlayWindows.show(slot, spec, host, content)`.
- `OverlayRequest` (`core/model/OverlayModels.kt`) is a **closed set of booleans**, 1:1
  with slots. **There is no generic "module N" flag and no registration interface.**
- **DECISION (Parts F/G):** The only honest way to let data-only plugins add a bounded
  Pill/overlay module without per-plugin core edits is to add ONE new generic seam: a
  plugin-module registry (list of {OverlayWindowSpec + bounded Composable built from the
  UI engine}) that a single new generic branch in `reconcile()` consumes. That one
  branch is the minimal, auditable core change. Document it in the threat-model note as
  the single overlay extension point; everything else stays closed.

### 1a. The seam as built — IMPLEMENTED

The decision above landed as described, and this is the whole of it. Three slots, one
branch, one registry read; nothing per-plugin anywhere in core.

- **Slots.** `OverlaySlot` gains `PLUGIN_MODULE_1/2/3` and a companion
  `PLUGIN_MODULES` list. These are the only slots whose *content* originates outside
  GameCore. The count is pinned to `PluginOverlayModule.MAX_MODULES_ON_SCREEN` by
  `PluginModuleSlotsTest`, which also derives the list from the enum, so a fourth slot
  added in one place and forgotten in the other fails the gate instead of silently never
  being drawn into.
- **The branch.** One `showPluginModules(manager)` call in `reconcile()`, following the
  `TRIGGER_MARKER` precedent: it rides along with a session that is already up. A module
  cannot start the service or keep it alive — `reconcile()`'s head still closes everything
  and stops the service when `!request.anythingVisible`. **`OverlayRequest`,
  `OverlayController` and the settings model were not touched**, which is why there is no
  new boolean 1:1 with these slots.
- **The registry read.** `PluginRegistry.activeOverlayModules: StateFlow<List<ActiveOverlayModule>>`,
  collected in `observe()` so a grant revoked mid-session removes the window on the next
  emission. The registry has already applied the kill switch, the per-plugin switch, the
  `CREATE_OVERLAY` grant and every `READ_APPROVED_DATA` scope the module's figures need,
  so the service's job is placement, not policy.
- **Where the bounds are enforced.** `touchable = false` and an explicit width/height from
  `ModuleSize`'s closed set — never `WRAP_CONTENT`, so a long string cannot become a wide
  window — plus a fraction-based anchor with `snapToEdge = false` (per the fractions gotcha
  below; a module is not draggable). The codec already refuses touch-capable node types, so
  a plugin is stopped twice over rather than once.
- **Readings.** `publishReadings()` samples the union of the user's HUD widgets and every
  module's `requiredStats`, re-read each tick. A module asking for a stat the user's HUD
  does not display still gets it — otherwise the feature would work only for users who
  happened to have built a matching HUD — and one appearing mid-session is picked up on the
  next interval without restarting the loop. `OverlaySamplingGate` takes a fourth term,
  `pluginStatsVisible`, so a module with no figures in it costs no sampling at all.
- **Dogfooded by** `assets/plugins/performance/pill.json` (frame rate / CPU / temperature),
  whose scope appetite is pinned to its own manifest by `BundledPluginsTest`.

### Hard bounds a plugin overlay module MUST respect (enforced today)
- Touch: every window is `FLAG_NOT_FOCUSABLE` always. `FLAG_NOT_TOUCHABLE` is added
  unless `OverlayWindowSpec.touchable`/`dismissOnOutsideTouch`. **Plugin modules must
  leave `touchable=false`** → no touch interception (spec rule).
- No full-screen: `fullScreen=true` → MATCH_PARENT + FLAG_LAYOUT_NO_LIMITS, reserved for
  look-through decorations + TRIGGER_PLACEMENT. **Plugin modules must be bounded** (spec rule).
- Position/size bounds live in each model's `normalised()` and in `OverlayFrame`
  (`core/overlay/OverlayFrame.kt`): clamp by own size, snap by window CENTRE, pin
  oversize to origin, insets reserve cutout/status/nav/back-gesture strip.
- Models are PURE (JVM-testable): OverlayConfig, PillDisplayMode (COMPACT/DETAILED),
  HudWidget/HudLayout (MAX_WIDGETS=12, textSizeSp 8..28, opacity floor 15), OverlayFrame,
  PositionFraction, OverlayWindowSpec, OverlaySlot.
- **GOTCHA — position models differ:** `OverlayConfig.pillX/pillY` are PIXELS;
  `HudWidget.x/yFraction` are 0..1 FRACTIONS. A plugin module placed by data should use
  FRACTIONS (survives rotation/device transfer), like HUD, not pixels.
- **GOTCHA — core cannot depend on overlay layer:** core.model stores strings/floats
  (quickPins = QuickToggle names, macrosJson opaque, Dock/Button store flat floats) to
  avoid a back-edge dependency. Plugin models in `core/plugin` must follow suit.

## 2. Theme / appearance — CLEAN SEAM

- `ui/theme/Theme.kt`: `GameCoreTheme(settings: AppSettings)` is the **single** place
  assembling `MaterialTheme(colorScheme, typography, shapes)`. Pure function of inputs.
- Schemes: `darkSchemeFor/lightSchemeFor/amoledSchemeFor` in `ui/theme/Color.kt`;
  `GameCoreTypography` in `ui/theme/Type.kt`. Existing `ColorPreset` model to map onto.
- **SEAM (Part F theme.json):** give `GameCoreTheme` an optional plugin-theme override
  alongside AppSettings; `RootViewModel`/`GameCoreRoot` (~line 212) resolves the active
  plugin theme when CHANGE_UI granted + THEME declared. Core untouched because every
  screen already reads from `MaterialTheme.colorScheme/typography`.
- **Safety screens opt out by NOT being wrapped in the overridden theme** → permission
  prompts, plugin manager, kill switch render in the default theme always.

## 3. Navigation + Hilt DI — CLEAN SEAM

- `ui/GameCoreRoot.kt`: `GameCoreNav` NavHost at ~line 362; `Destination.kt` declares
  routes; `Destination.top` = [home, games, aimlab, sessions, settings] pinned by
  `ui/DestinationTest.kt` (`allDestinations` ~line 141).
- **To add plugin-manager / preview-install / kill-switch / developer screens:**
  (1) `data object` in Destination.kt implementing `Destination` — NOT `Top` (keeps them
  off the bottom bar); id-carrying preview screen gets a `routeFor(pluginId)` + ARG
  constant (model: ProfileEditor ~line 553). (2) one `composable(route){ … }` in
  GameCoreNav, `navArgument` form for the id screen. (3) add each new object to
  `allDestinations` in DestinationTest + keep routes unique. (4) new
  `@Module @InstallIn(SingletonComponent::class)` file for plugin singletons.
- Reached via `open`/`push` lambdas → land on back stack, not the bar.

## 4. Data layer (read feeds) — CLEAN SEAM

- `DataScope` (SESSIONS, GAMES, DEVICE_STATS, THERMALS) in `core/plugin/PluginManifest.kt`
  maps 1:1 onto existing read surfaces:
  - SESSIONS → `SessionRepository` (sessions Flow, samples, statistics)
  - GAMES → `GameProfileRepository` (profiles Flow)
  - DEVICE_STATS → `PerformanceMonitor` (snapshots/history StateFlows)
  - THERMALS → `PerformanceMonitor` snapshots.thermal projection
- **SEAM (Part D):** new per-scope projection facade in the plugin layer that subscribes
  to those flows and maps rich internal models (GameSession, GameProfile,
  PerformanceSnapshot, ThermalReading) to **PII-stripped DTO flows**, gated by
  `RequestedPermission(READ_APPROVED_DATA, scopes)`. Plugins NEVER get repos/DAOs.

## 5. Backup & developer surfaces

- `data/repository/BackupManager.kt`: `readCapped(input, maxBytes)` streams and returns
  null the instant the cap is exceeded — the pattern Part B's zip loader reuses for
  per-file and per-package size caps. No zip handling exists anywhere yet (Part B is first).
- `core/backup/BackupCodec.kt`: FORMAT/VERSION/typed-RejectReason — the codec style
  already mirrored by `PluginManifestCodec`; reuse for plugin export (Part H, no secrets).
- Settings screen structure holds where a `Developer → Plugin Development` subsection goes
  (Part L). (Confirm exact Settings file when building L.)

## 6. Aim Lab content codec — REJECTION RULES

- `aimlab/AimLabContracts.kt`, `aimlab/engine/ConfigCodec.kt`, `aimlab/engine3d/Humanoid.kt`:
  the app's OWN aim lab HAS `HumanoidTarget` / `HUMANOID("Humanoid")`.
- **Plugin aim-lab content (Part G) MUST REJECT** humanoid targets and weapon models.
  CONFIRMED: `aimlab/engine3d/Humanoid.kt` → `enum class TargetType { SPHERES("Spheres"),
  HUMANOID("Humanoid"), MIXED("Mixed") }`. Plugin validator **allows only `SPHERES`** and
  **rejects both `HUMANOID` and `MIXED`** (MIXED spawns humanoids too). Also reject any
  weapon-model reference. Settings screen = `ui/settings/SettingsScreen.kt` (+State/ViewModel)
  for the Part L Developer subsection.
