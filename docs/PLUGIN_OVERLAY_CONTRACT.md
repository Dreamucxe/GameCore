# Plugin overlay modules — the one core change (3.8)

This is the contract for the single accepted modification to GameCore core: a **generic plugin-module
registry** consumed by **one new generic branch** in `GamingOverlayService.reconcile()`, so that
data-only plugins can add a bounded on-screen module without any per-plugin core edit.

Everything here is derived from the real source, not from a handover doc. Where this document and the
source disagree, **the source wins** — say so rather than coding around it.

---

## 0. The rule that does not bend

A plugin is **data**. There is no `DexClassLoader`, no in-process third-party Kotlin/Java, no WebView,
no embedded scripting. An overlay module is a *tree of validated nodes plus a position*, rendered by
GameCore's own Compose code. A plugin never receives a binder, a shell, a command string, or a
`Composable`.

Consequences you must not design around:

- A module can never be `touchable`. See §3.
- A module can never be full-screen. See §3.
- No screen capture, no screen reading, no accessibility reading. A module draws; it does not observe.
- No game-memory or game-state reading. Stats come only from GameCore's own approved readings.

---

## 1. Why this seam and not another

Three facts from the source shape the whole design:

1. **`OverlayWindows.live` is `mutableMapOf<OverlaySlot, View>()`** — one live `View` per slot, and
   `show()` calls `hide(slot)` first, so it *replaces* rather than refuses. N plugin modules therefore
   cannot share one slot. We allocate a small fixed number of slots instead of making the map key
   richer, because that keeps `hideAll()`, `visible: Set<OverlaySlot>` and every existing call site
   correct and unchanged.
2. **`OverlayRequest.anythingVisible` gates `stopSelf()`** in `reconcile()`. A request carrying only
   plugin modules would tear the service down, so the new flag must participate in `anythingVisible`.
3. **`core/plugin` must not depend on the overlay layer.** The plugin-side model carries fractions,
   floats and strings; the *service* translates it into `OverlayWindowSpec` at the boundary. This
   mirrors `HudWidget.x/yFraction` (fractions) and deliberately not `OverlayConfig.pillX/pillY`
   (pixels), because fractions survive rotation and transfer between devices.

`PluginSection.PILL("pill", "pill.json")` **already exists** in the manifest grammar and the loader
already enforces declared-vs-present. Nothing in the package format changes. We are implementing a
section the format already reserved.

---

## 2. JSON: `pill.json`

```json
{
  "format": "gamecore.plugin.pill",
  "version": 1,
  "id": "fps-badge",
  "title": "Frame rate",
  "anchor": { "x": 0.5, "y": 0.08 },
  "size": "small",
  "opacity": 85,
  "content": {
    "type": "row",
    "props": { "gap": 6, "align": "center" },
    "children": [
      { "type": "text", "props": { "text": "FPS", "color": "#99FFFFFF", "size": 11 } },
      { "type": "stat", "props": { "stat": "fps", "size": 14 } }
    ]
  }
}
```

### Node encoding

A node is `{ "type": <component id>, "props": { ... }, "children": [ ... ] }`. `props` and `children`
are both optional and default to empty. `type` must resolve via `ComponentType.fromId`; an unknown
type is a **rejection**, never a skip.

`PropValue` is inferred from the JSON type, with two string sigils:

| JSON | becomes |
| ---- | ------- |
| `true` / `false` | `PropValue.Bool` |
| number | `PropValue.Num` |
| `"#RRGGBB"` or `"#AARRGGBB"` (valid hex) | `PropValue.Color` |
| `"@someId"` | `PropValue.ActionRef("someId")` |
| any other string | `PropValue.Str` |

A `#`-prefixed string that is *not* valid hex is a plain `Str` — not an error, because a label may
legitimately start with `#`. A `@`-prefixed string is always an `ActionRef`; authors wanting a literal
`@` write `@@`, which decodes to a `Str` of the remainder.

---

## 3. Bounds the codec enforces

These are the teeth. Every one of them is a rejection, not a clamp, unless stated otherwise.

**Interactive node types are rejected outright in an overlay module.** Allowed: `COLUMN`, `ROW`,
`GRID`, `CARD`, `TEXT`, `STAT`, `SPACER`, `IMAGE`. Rejected: `BUTTON`, `SLIDER`, `SWITCH`, `SCROLL`.
This is the structural reason a module is safe to render in a window that is `FLAG_NOT_TOUCHABLE`: it
contains nothing that could want a touch. Enforcing it in the codec rather than the renderer means a
reviewer checks one list, and an interactive node can never reach a surface that cannot deliver events.

`PropValue.ActionRef` is likewise **rejected** inside an overlay module, for the same reason: there is
no path by which it could be dispatched, so accepting it would be a promise the system cannot keep.

**Tree size.** Reuse the existing `ScreenValidator` with far tighter caps than a full screen — it is
already parameterised, so do not write a second validator:

```kotlin
ScreenValidator(
    maxNodes = 40,
    maxDepth = 6,
    maxGridColumns = 4,
    maxStringLength = 120,
    maxChildren = 12,
)
```

**Position and opacity are clamped, not rejected** — a module slightly off-screen is a mistake to
correct, not an attack to refuse. `anchor.x`/`anchor.y` clamp into `0f..1f`; `opacity` clamps into
`20..100` (never fully transparent, because an invisible window the user cannot find is worse than a
faint one).

The anchor's *values* are clamped; its *shape* is not. An `anchor` that is not an object — an array,
a string, a bare number — is rejected as `BAD_ANCHOR`, and so is an object carrying any key other
than `x` or `y`. There is nothing to clamp in `[0.5, 0.08]`: the module would land at the default
position, which is not where the file says it goes, and the author would have no way to know. The
reason is its own rather than `MALFORMED` so the install screen can say what is wrong with the file
instead of claiming it is not JSON.

**Size is a closed enum**, not free numbers, so a plugin cannot grow a "module" into a full-screen
surface by arithmetic: `small 120×48`, `medium 180×72`, `large 240×112` (dp). An unknown id rejects.

**Text is sanitised** with the existing `TextSanitizer` on `title` and on every `Str` prop, so a module
cannot smuggle control characters or bidi overrides onto the screen.

**At most `MAX_MODULES_ON_SCREEN = 3`** modules render at once, across all plugins. Beyond that they
are dropped in registry order, and the manager says so rather than silently losing one.

---

## 4. Files

### Agent-owned (new files — no shared-file edits)

| File | What |
| ---- | ---- |
| `core/plugin/ui/PluginNodeCodec.kt` | `ComponentNode` ↔ JSON. Part F's missing codec. |
| `core/plugin/overlay/PluginOverlayModule.kt` | The model: id, title, anchor, size, opacity, content. |
| `core/plugin/overlay/PluginOverlayCodec.kt` | `pill.json` → model, house codec pattern. |
| `ui/plugins/render/PluginNodeRenderer.kt` | `ComponentNode` → Compose, read-only. |
| tests for each of the above | |

### Main-thread-owned (shared/central — do NOT edit these)

`GamingOverlayService.kt`, `OverlayModels.kt`, `OverlayWindows.kt`, `OverlayController.kt`,
`PluginRegistry.kt`, `PluginRegistryImpl.kt`, `di/PluginModule.kt`, `WhatsNewRegistry.kt`.

---

## 5. House codec pattern (follow `PluginThemeCodec` exactly)

- Pure `org.json` + models. **No `android.*`** anywhere, so it is unit-testable on the JVM.
- `const val FORMAT` and `const val VERSION`.
- A typed sealed `Result` with a `Rejected(reason)` carrying a human reason.
- **Never throws.** Wrap the parse; a `JSONException` becomes a `Rejected`.
- An ordered guard chain — cheapest and most structural checks first.
- Enum-by-name with an explicit fallback or rejection, never `valueOf` on raw input.
- A `SCAN_LIMIT`-style ceiling on anything iterated.
- `encode` as well as `decode` where the SDK needs to emit the file, and a round-trip test.

## 6. Test classpath — read this before writing a test

`testImplementation` is **only** `junit` (JUnit 4), `kotlinx-coroutines-test`, and `org.json`.

There is **no** Robolectric, Mockito, MockK, Truth, Turbine, kotlin-reflect, androidx.test,
compose-ui-test, or JUnit 5. Do not import them. Write hand-written fakes, not mocks.

`isReturnDefaultValues = true`, so `android.*` calls return 0/null/false instead of throwing — but a
test must never *depend* on that. Compose's `Color`, `Dp`, `TextStyle`, `Shapes`, `ColorScheme`,
`Typography` and `FontFamily` are JVM-testable and fine to assert on. A `@Composable` is not
unit-testable here: test the codec and the model, and leave the renderer to the compile gate.
