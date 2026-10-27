# The GameCore Kotlin Plugin SDK

> **Status: built and shipping.** `:gamecore-plugin-sdk` is a real Gradle module in
> this repository, and this document describes it as it exists in **3.8 /
> versionCode 28**. Every type, function, constant and template named below is in
> `gamecore-plugin-sdk/src/main/kotlin/com/gamecore/plugin/sdk/`; anything a reader
> might expect that is *not* there is called out explicitly in §6.
>
> Read [`PLUGIN_FORMAT.md`](PLUGIN_FORMAT.md) first — it defines the `.gcplugin`
> package the SDK emits. This document must never contradict it; the SDK is a
> *producer* of exactly that format.

## 1. Overview

The Plugin SDK is a **build-time Kotlin DSL**. A developer describes a plugin in
Kotlin, gets a `PluginDefinition` back, and writes it to a `.gcplugin` zip. It is
the ergonomic front end to a format that is otherwise hand-authored JSON (see
`PLUGIN_FORMAT.md` §3) — and it is the only part of the plugin platform that runs
on a developer's machine rather than on a user's phone.

It is explicitly **not** a runtime extension point:

- The SDK **ships no code into the app.** `:app` does not depend on
  `:gamecore-plugin-sdk` — deliberately, and the dependency arrow has never
  pointed that way. Nothing here is compiled into, bundled with, or loaded by
  GameCore.
- A `.gcplugin` carries **no classes, no scripts, no binders** — only
  `manifest.json` and JSON section content. The DSL compiles your definition
  *down to that data*. Your Kotlin runs once, on your machine, at package time;
  it never runs on the device.
- There is no `onClick`, no lambda, no callback that survives into the package.
  An automation's "action" is the *id* of a broker action — a reference GameCore
  dispatches through its own gated path, not a function the plugin supplies.

### The "data, not code" guarantee

Because the DSL only ever *emits* the closed format, it cannot express anything
the format cannot carry, and the type system is the first line of enforcement:

- A permission is requested with a typed constant from `Permissions`, not a free
  string.
- A broker action id comes from `Actions`, which mirrors the host's
  `BrokerAction` registry. You cannot name an action that does not exist.
- An overlay module's node types are **functions on a scope**, and the four
  interactive ones are simply absent — there is no `button`, `slider`, `switch`
  or `scroll` to call inside a `pill { }` block, so an interactive node in an
  overlay is not something an author writes and has refused later. It does not
  compile. (`PillDsl.kt`'s own note explains why: the overlay window is
  `FLAG_NOT_TOUCHABLE`, so a node that wanted a touch could never be given one.)
- An `image` node names one of GameCore's twenty built-in vectors from
  `OverlayIcons`. There is no path by which the host loads a byte of plugin
  artwork, so there is no builder that pretends otherwise.

## 2. Module setup

```kotlin
// settings.gradle.kts — already present in this repository
include(":gamecore-plugin-sdk")
```

The module is pure Kotlin/JVM: no Android plugin, no KSP, no Hilt, no Compose.

```kotlin
// gamecore-plugin-sdk/build.gradle.kts
plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    implementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
```

Two consequences worth knowing before you build against it:

1. **`org.json` is the only runtime dependency.** The SDK emits JSON with the
   same library the host parses it with, which is why a string that round-trips
   here round-trips there.
2. **Its tests use `kotlin.test`, not `:app`'s JUnit-4-only classpath.** The two
   test source sets are independent; `tools/jvmtest.sh` covers `:app` only, so
   SDK changes are verified with `./gradlew :gamecore-plugin-sdk:test`.

Build and test it:

```
./gradlew :gamecore-plugin-sdk:test
```

## 3. The DSL at a glance

```kotlin
import com.gamecore.plugin.sdk.*
import java.io.File

val definition: PluginDefinition = plugin("com.example.neon", "Neon") {
    version = 2
    versionName = "1.1"
    developer = "You"
    description = "A high-contrast theme with a frame-rate readout."
    changelog = "Adds the overlay module."

    request(Permissions.CHANGE_UI)
    request(Permissions.CREATE_OVERLAY)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.DEVICE_STATS),
    )

    theme { /* §4 */ }
    rules { /* §5 */ }
    pill { /* §6 */ }
}

PluginPackager.writeTo(File("build/neon.gcplugin"), definition)
```

`plugin(id, name) { … }` returns a `PluginDefinition` — `manifestJson: ByteArray`
plus `sectionFiles: Map<String, ByteArray>`. Nothing is written to disk until you
hand it to `PluginPackager`.

**The top-level surface, in full:**

| Member | Meaning |
| --- | --- |
| `version: Int`, `versionName: String` | The plugin's own version, as the host's upgrade check reads it |
| `api: Int` | Plugin API level; defaults to `Sdk.API` |
| `minHostVersion: Int` | Lowest GameCore `versionCode` this plugin runs on; defaults to `Sdk.HOST_VERSION_CODE` |
| `developer`, `description`, `changelog` | Shown on the plugin's detail screen |
| `request(permission, scopes = emptySet())` | One requested permission. Flat — there is no `permissions { }` block |
| `theme { }` | Emits `theme.json`, declares `theme` |
| `rules { }` | Emits `rules/rules.json`, declares `rules` |
| `pill { }` | Emits `pill.json`, declares `pill` |
| `section(path, declares, bytes)` | Escape hatch: a raw section file for a section with no builder yet |

Declaring a section and writing its file are **one act**, by design: the host
rejects a package whose `declares` and contents disagree in *either* direction,
so the SDK never lets them be written separately.

### 3.1 Refuse, don't clamp

The SDK's one editorial decision, and the thing to understand before reading §4
onward. The host is a load-time system facing a user who would rather have a
slightly-wrong plugin than no plugin, so it **clamps** an out-of-range anchor,
opacity, corner radius or UI scale, and **truncates** an over-long name or title.
Those are the right calls on a device.

They are the wrong calls at authoring time. A value the host quietly adjusted is
a value the author never saw on their own screen. So wherever the host clamps or
truncates, **this SDK refuses** — and where the host rejects outright, the SDK
refuses earlier, at the line that caused it.

`require` failures are `IllegalArgumentException`s with messages that name the
field, the value, the host's range, and what the host would have done with it.

## 4. `theme { }` — restyling the app

```kotlin
theme {
    name = "Neon"            // ≤ 48 chars
    dark = true
    primary = "#FF2E88"      // #RRGGBB or #AARRGGBB
    onPrimary = "#FFFFFF"
    background = "#05010A"
    surface = "#120820"
    onSurface = "#F2E9FF"
    accent = "#00E5FF"
    cornerRadiusDp = 4       // 0..48
    font = ThemeFonts.MONO   // system | rounded | mono | serif | cursive | minecraft
    uiScale = 1.0            // 0.8..1.4
}
```

All eleven keys are emitted every time, including those left at their defaults:
the host requires the six colours and defaults the rest, and a theme file that
spells out what it means is one a developer can read back and diff.

The two failure modes are different, which is why both are checked:

| Mistake | What the host does | What the SDK does |
| --- | --- | --- |
| Colour not `#RRGGBB`/`#AARRGGBB` (no 3-digit form, no named colours) | `BAD_COLOR` — **rejects the whole theme** | refuses |
| `font` outside `ThemeFonts.ALL` | `BAD_FONT` — **rejects the whole theme** | refuses |
| `cornerRadiusDp` outside `0..48` | clamps | refuses |
| `uiScale` outside `0.8..1.4` | clamps | refuses |
| `name` over 48 chars | truncates | refuses |
| Control or bidi character in `name` | strips | refuses |

A hard theme rejection is the quietest failure in the platform: the plugin
installs, its switch turns on, and the app looks exactly as it did. Nothing on
screen distinguishes that from a theme the user never selected.

A theme **cannot ship a font file.** `font` names a face GameCore already
bundles. Safety surfaces — the permission prompts, the plugin manager, the kill
switch — render in GameCore's own theme regardless of what is active; a plugin
does not get to paint the screen that turns it off.

## 5. `rules { }` — automations through the broker

```kotlin
rules {
    rule("boost", Triggers.GAME_LAUNCHED, listOf(Actions.SET_PEAK_REFRESH_RATE))
    rule(
        "cool_down", Triggers.THERMAL_HIGH,
        actions = listOf(Actions.SET_MIN_REFRESH_RATE),
        conditions = listOf(
            RulesBuilder.Condition(RuleVariables.TEMP, RuleOperators.GTE, 45.0),
        ),
    )
}
```

A rule is "when TRIGGER, if CONDITIONS, do ACTIONS". **A rule names an action; it
never carries one.** There is no command string, no settings key and no value
anywhere in this grammar — what an action does, what it clamps to and whether it
needs a second opt-in is the broker's business. That is the whole reason
automations can exist without a plugin ever holding a shell.

Conditions are a flat conjunction of `variable op value`. No boolean operators,
no nesting, no expressions: a rules file is a decision table, not a program.

Four names, four different host behaviours — and only one of them is quiet:

| Name | Source | What the host does with a bad one |
| --- | --- | --- |
| `trigger` | `Triggers` | Rejects the **entire rules file** |
| action id | `Actions` | Rejects the **entire rules file** |
| `op` | `RuleOperators` | Rejects the **entire rules file** |
| `variable` | `RuleVariables` | **Nothing.** Copies it through unresolved |

That last row is the one to internalise. The host's rules codec does not resolve
`variable` at all, and the engine reads a name the stat feed does not publish as
a condition that is simply **not met** — in `AutomationEngine`'s own words, *"a
rule referencing a variable the feed does not provide simply never fires."* A
thermal rule written against `temp_c` where the feed says `temp` installs,
validates, appears in the plugin's rule list, and protects nothing, for as long
as the plugin is installed, with no log line and no device state that differs
from "not hot yet". `RuleVariables` exists so that is a build failure.

Caps, from `RuleBounds`: 32 rules per file, 8 actions per rule, 10 conditions per
rule. A rule with **no** actions is a host validation failure rather than a
no-op, and the SDK refuses an empty `actions` list for that reason. Duplicate
rule ids are refused too — the host names a rule by its id when it reports a
problem with one.

All four names are resolved case-insensitively by the host, and the SDK emits the
canonical spelling the host will register, so the JSON reads back as what ran.

## 6. `pill { }` — a module drawn over the game

```kotlin
pill {
    id = "fps-badge"              // ^[a-z0-9][a-z0-9._-]{0,63}$
    title = "Frame rate"          // ≤ 48 chars
    anchor(x = 0.5, y = 0.08)     // screen fractions, not pixels
    size = OverlaySizes.SMALL     // small | medium | large
    opacity = 85                  // 20..100
    row(gap = 10, align = "center") {
        column(gap = 2, align = "center") {
            text("FPS", color = "#99FFFFFF", size = 10)
            stat(OverlayStats.FPS, size = 16)
        }
        spacer(size = 6)
        image(OverlayIcons.THERMOSTAT, size = 14)
    }
}
```

Exactly one root node: the file carries one `id` and the host registers one
module per section. Up to three modules are drawn at once, alongside the Pill and
HUD, and a module rides along with an overlay session that is already up — it
cannot start the service or keep it alive. See
[`PLUGIN_OVERLAY_CONTRACT.md`](PLUGIN_OVERLAY_CONTRACT.md).

**The eight component functions are the whole set:** `column`, `row`, `grid`,
`card`, `text`, `stat`, `spacer`, `image`.

Bounds come from `OverlayBounds`: 40 nodes, depth 6 below the root, 12 children
per container, 4 grid columns, 120 characters per string prop, 48 for the title,
64 for the id.

Three things in this block are easy to get wrong in a way nothing reports:

- **Prop names.** The host's renderer reads a fixed vocabulary per node type and
  silently defaults anything else. A card's fill is `color`, not `background`; a
  spacer takes `size` and is square, not `width`/`height`; an image names an
  `icon`, never an asset path. The SDK emits the renderer's own names, and
  `PluginPillDslTest` walks every emitted key against that vocabulary so a
  change on the host side fails here.
- **Alignment is per-axis.** `align` is the *cross* axis: horizontal words on a
  `column`, vertical on a `row`. The host resolves an unrecognised word to the
  axis default, so `column(align = "top")` would be a layout quietly pinned left.
  The SDK checks each word against its axis. Note that `end` is legal on both and
  means a different edge on each, as in Compose.
- **A stat needs its scope.** `stat(OverlayStats.TEMP)` draws nothing unless the
  manifest also requests `READ_APPROVED_DATA` scoped to
  `OverlayStats.scopeFor(OverlayStats.TEMP)` — `thermals` for temperature and
  battery, `device_stats` for frame rate, CPU, GPU and RAM. A missing grant is
  not malformed JSON, so **no codec will catch this for you**; the module loads
  and the field stays empty.

The module id is the other trap worth naming: the host trims and lowercases it
*before* matching its grammar, then rejects whatever still does not fit. So
`"My Badge"` becomes `my badge` and then fails on the space, while `"Module"`
would silently register as `module`. The SDK refuses both — an id is the one
string in a module that must be identical in the source and in the registry.

## 7. Parity: how "builds == loads" is actually kept

The SDK does **not** share the host's validation code, and earlier drafts of this
document were wrong to say it did. `:gamecore-plugin-sdk` is a pure-JVM module
that does not depend on `:app`, so it cannot import `PluginPermission`,
`BrokerAction`, `ModuleStat` or any other host enum. There is no `packagePlugin`
Gradle task; `PluginPackager.writeTo(File, PluginDefinition)` is the whole of the
packaging surface.

Parity is a **mirror**, maintained three ways:

1. **Transcribed constants.** `Constants.kt` writes each host registry out as
   named constants with an `ALL` set. Using them rather than raw strings turns a
   typo into a compile error in the plugin's own build.
2. **Transcribed bounds, with the host's behaviour documented next to each.**
   Every set and every cap in `Constants.kt` records what the host does with a
   value outside it — reject, clamp, truncate, or nothing at all — because those
   differences are the whole reason the checks are worth their lines.
3. **Tests that pin the mirror.** `PluginSdkTest` and `PluginPillDslTest` assert
   the emitted JSON against the host's vocabulary, including a walk of every
   emitted prop key against the renderer's reads.

The honest limitation: the one way this can drift is a change on the host side
that nobody reflects here. That is a real risk, it is why the host's behaviour is
written down beside each constant, and it is why the parity tests assert against
the *vocabulary* rather than against the SDK's own output.

### What the SDK does not have yet

There are host-side sections with no builder in this module:

| Section | Status |
| --- | --- |
| `screens/` (full plugin screens) | Host-side engine built (`ComponentNode`, `ScreenValidator`); **no SDK builder** |
| `profiles/` (game-profile presets) | Host-side support built; **no SDK builder** |
| `aimlab/` (Aim Lab content) | Host-side validator built (primitive shapes only; humanoid and mixed targets rejected); **no SDK builder** |
| `stats/` (stat expressions) | Host-side `StatExpression` built; **no SDK builder** |

Use `section(path, declares, bytes)` for these, writing the JSON by hand against
`PLUGIN_FORMAT.md`. The escape hatch still goes through the packager's path
check, and the host still runs its own codecs — you lose the build-time checks,
not the load-time ones.

## 8. Templates

`com.gamecore.plugin.sdk.templates.Templates` holds four worked examples, each
built entirely through the DSL — nothing there hand-writes JSON, so they double
as the SDK's examples and as the fixtures its tests package:

| Template | Declares | Shows |
| --- | --- | --- |
| `minimal()` | `theme` | The floor: a clean dark theme, one low-risk permission. The one to copy. |
| `pixel()` | `theme` | A theme that changes more than colour — rounded font, 28dp corners, light scheme. |
| `cyberpunk()` | `theme`, `rules` | A theme plus two broker-driven rules, one of them conditional on temperature. |
| `overlay()` | `pill` | A data-only plugin whose whole content is one bounded module, with the permission pair it needs. |

## 9. Cross-references

- [`PLUGIN_FORMAT.md`](PLUGIN_FORMAT.md) — the on-disk `.gcplugin` format this
  SDK emits (package layout, manifest keys, permissions, limits, Aim Lab rules).
- [`PLUGIN_OVERLAY_CONTRACT.md`](PLUGIN_OVERLAY_CONTRACT.md) — what an overlay
  module may be and how it is drawn.
- [`PLUGIN_THREAT_MODEL.md`](PLUGIN_THREAT_MODEL.md) — what a plugin can never
  reach, and why.
- [`PLUGIN_BUILD_STATUS.md`](PLUGIN_BUILD_STATUS.md) — which parts of the
  platform execute today and which are built but unwired.
- Host sources the mirror in `Constants.kt` is taken from, under
  `app/src/main/java/com/gamecore/core/plugin/`: `PluginPermission.kt`,
  `BrokerAction.kt`, `theme/PluginTheme.kt`, `theme/PluginThemeCodec.kt`,
  `overlay/PluginOverlayModule.kt`, `overlay/PluginOverlayCodec.kt`,
  `content/RulesCodec.kt`, `content/AutomationEngine.kt`, plus
  `ui/plugins/render/PluginNodeRenderer.kt` for the prop vocabulary.
