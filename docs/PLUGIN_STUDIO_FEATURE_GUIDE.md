# Plugin Studio 1.1 feature map and tutorial

Plugin Studio is GameCore’s visual editor for building data-only `.gcplugin` packages on an Android phone. It edits only capabilities that GameCore’s host can validate and run. It does not generate executable plugin code.

The same editor has two hosts:

- **Inside GameCore 4.3:** **Settings → Plugins → Create a plugin → Open Plugin Studio**.
- **Standalone Plugin Maker 1.1.0:** package `com.gamecore.pluginmaker`, Android 8.0+, no Android permissions in the release build.

Both hosts use the same `plugin-studio/` implementation and deterministic package writer. Saving uses Android’s system file picker, so the release Plugin Maker needs no storage permission.

## 1. The shortest complete tutorial

This path creates a valid theme plugin, saves it, installs it, and turns it on.

1. Open Plugin Studio from GameCore or the standalone Plugin Maker.
2. Open **Theme** and choose a starting template.
3. Expand **Identity** and set:
   - a unique package-style id, such as `com.yourname.midnight`;
   - plugin name;
   - version number and version name;
   - developer and description.
4. Edit the palette, type, shape, glass, or glitch values.
5. Open **Test** and fix every field in **Validation report**.
6. Open **Export** and verify:
   - the entry list;
   - declared sections;
   - total size; and
   - SHA-256.
7. Tap **Save .gcplugin** and choose a folder in the system picker.
8. In GameCore, open **Settings → Plugins → Choose files** and select the package.
9. Read the install preview and confirm. Installation grants nothing and leaves the plugin off.
10. Open the plugin’s detail page, approve only wanted permissions/scopes, save the grant, choose its theme if desired, and enable it.

A saved `.gcplugin` is not automatically installed, and an installed plugin is not automatically enabled.

## 2. Workspace map

```text
Plugin Studio
├── Top bar
│   ├── Current project name
│   ├── Undo / Redo
│   ├── Projects and drafts
│   └── Close (when hosted in a dialog)
├── Build
│   ├── Project category
│   ├── Derived section / permission / scope plan
│   ├── Theme inclusion
│   ├── Read-only screens
│   ├── One bounded overlay
│   ├── Typed settings
│   └── Dashboard widgets
├── Theme
│   ├── Eight templates
│   ├── Thirteen colour roles
│   ├── Shape / scale / dark / AMOLED
│   ├── Fourteen typefaces / weight / type ramp
│   ├── Glass material
│   ├── Glitch motion
│   ├── Package identity
│   └── Requested permissions and data scopes
├── Logic
│   ├── Rules
│   ├── Trigger
│   ├── Flat AND conditions
│   ├── Ordered allowlisted actions
│   └── Fixed host action values
├── Preview
│   ├── Compact / Phone / Large / Tablet
│   ├── Portrait / Landscape
│   ├── Font scale
│   ├── Brightness
│   └── Simulated host controls
├── Test
│   ├── Validation report
│   ├── Rule simulation
│   ├── Static conflict observations
│   ├── Package inspection and SHA-256
│   ├── Local capability observations
│   ├── WCAG / design score
│   └── Colour-vision previews
└── Export
    ├── Final validation
    ├── Exact archive entry list
    ├── Size / declared sections / schema / SHA-256
    ├── Save .gcplugin
    ├── Import an existing package
    └── Open GameCore (standalone host only)
```

## 3. Drafts, projects, undo, and autosave

### Draft versus package

A **draft** is Studio’s editable JSON state. It may temporarily contain a half-typed id or another invalid field. It is not installable.

A **package** is the validated deterministic zip written by Export. The Save button remains disabled while validation problems exist.

### Project manager

The folder button in the top bar opens saved projects. You can:

- open an existing draft;
- create a new draft;
- switch between projects; and
- return to the project last opened after restarting.

Studio autosaves the active draft about 800 ms after editing stops. This autosave is separate from exporting a `.gcplugin`.

### Undo and redo

Undo and redo cover draft edits in the current editor session, retaining up to 60 prior draft states. Opening/importing a different project becomes another reversible draft transition; writing an exported file is not undone by the editor.

## 4. Build workspace

Build controls the plugin sections other than automation rules and the theme’s design values.

### Project category

Categories organize drafts inside Studio:

- Theme
- Interface
- Automation
- Overlay
- Settings
- Monitoring
- Dashboard
- Utility
- Combined

A category is Studio metadata only. It is not written as a manifest capability and grants nothing.

### Derived section plan

The **Section plan** card shows three derived lists:

1. manifest `declares` values;
2. permission requests required by authored content; and
3. approved-data scopes required by live readings.

You do not manually claim a section. Adding/removing content changes the lists. Required permissions and scopes are locked on in the Theme permission editor so an export cannot declare live content without declaring what it needs.

### Include or exclude the theme

**Include theme.json** decides whether the package declares and writes a theme. Turning it off preserves the draft’s theme values in the editor but excludes them from the exported package.

### Read-only screens

A plugin can declare up to **32 screens**. For each screen you set:

- lowercase screen id;
- title;
- optional built-in icon; and
- host-rendered component content.

Screens are opened from the plugin’s detail page in GameCore. They are read-only: no component can dispatch an action, navigate elsewhere, or write a setting.

The visual editor authors a `column` or `card` root with ordered direct children. Up/down controls change the real exported order. Imported nested trees are preserved, but the flat editor does not pretend to edit every nested shape it cannot faithfully represent.

### Monitoring overlay

A plugin can declare **one overlay**. GameCore allows at most three plugin overlay modules active globally. Configure:

- id and title;
- horizontal and vertical anchor from 0–100%;
- fixed host size: Small, Medium, or Large;
- opacity from 20–100%; and
- component content.

The runtime window is fixed-size and non-touchable. It cannot scroll, accept taps, freely resize, choose arbitrary z-order, or open multiple windows for one plugin. Overlay charts are literal-only.

### Typed settings

A plugin can declare up to **64 settings**:

| Type | Editable values |
| --- | --- |
| Boolean | Default on/off |
| Integer | Default, optional minimum and maximum |
| Float | Default, optional minimum and maximum |
| Enum | Up to 32 distinct options and one default |
| String | Default and maximum length from 1–256 |

Setting ids are lowercase storage-key slugs. The current host format has no independent title/description fields, so GameCore renders a sanitized form of the id.

Settings are stored under that plugin’s namespace and need the `store_data` permission grant. They do not automatically bind to a screen, device setting, or rule.

The one implemented setting-to-behavior bridge is **Drive glass intensity**. A numeric Int or Float setting may be named by `theme.glass.intensitySetting`, allowing the plugin’s setting to scale its own glass material.

### Dashboard widgets

A plugin can declare up to **32 read-only Home widgets**. Each has:

- id and title;
- Small, Medium, Large, or Full host height footprint; and
- component content.

The size is a host layout footprint, not a free canvas. Widgets cannot receive taps or dispatch actions. GameCore draws them only while the plugin is active and the `add_dashboard_widgets` grant is held.

## 5. Component vocabulary

Studio uses a closed host-rendered vocabulary:

| Component | Purpose |
| --- | --- |
| `column` | Vertical container |
| `row` | Horizontal container; imported nested rows are preserved |
| `grid` | Bounded multi-column container |
| `scroll` | Scroll container on screens/widgets; forbidden in overlays |
| `card` | Surface/container |
| `text` | Literal text |
| `stat` | Approved live reading |
| `spacer` | Fixed spacing |
| `image` | One of GameCore’s built-in icons |
| `chart` | Literal values, or supported retained live series on screens/widgets |

There are intentionally no authorable `button`, `slider`, or `switch` screen nodes. The host can parse old illustrative forms of those nodes but has no safe action callback for them; presenting them as interactive controls would create inert UI.

### Surface limits

| Surface | Nodes / depth | Child cap | Scroll | Live chart |
| --- | --- | --- | --- | --- |
| Screen | 500 nodes / depth 24 | 100 | Yes | FPS, CPU, RAM, temperature |
| Overlay | 40 nodes / depth 6 | 12 | No | No; literal charts only |
| Widget | 40 nodes / depth 6 | 12 | Yes | FPS, CPU, RAM, temperature |

The flat editor offers Text, Stat, built-in Image, literal Chart, Card, and Spacer as direct children. Layout padding and gap are bounded, and text/color/icon/series values are validated before export.

### Live stats and scopes

| Stat | Screen/widget | Overlay | Required scope |
| --- | --- | --- | --- |
| FPS | Yes | Yes | `device_stats` |
| CPU | Yes | Yes | `device_stats` |
| GPU | Yes (instant only) | Yes | `device_stats` |
| RAM | Yes | Yes | `device_stats` |
| Battery | Yes (instant only) | Yes | `thermals` |
| Temperature | Yes | Yes | `thermals` |
| Rotation rate | No | Yes | `motion` |
| Movement | No | Yes | `motion` |

Motion readings exist only in the overlay because the overlay service owns the motion sampler. Live retained charts are limited to FPS, CPU, RAM, and temperature. Unavailable real readings remain unavailable; plugins do not receive fabricated zeroes.

### Built-in icons

Screens and image nodes may use one of twenty host icons: speed, memory, thermostat, battery, bolt, timer, timeline, data, insights, sensors, storage, shield, extension, info, warning, block, check, dot, game, or settings.

Custom image files, downloaded fonts, arbitrary vectors, and per-plugin assets are not authored by the current Studio.

## 6. Theme workspace

### Eight starting templates

| Template | Starting style |
| --- | --- |
| **Minimal** | Light warm paper, deep blue/teal, Inter |
| **AMOLED** | True black, lime and ice blue, JetBrains Mono |
| **Gaming** | Dark scoreboard, signal red and mint, Orbitron |
| **Glass** | Cool translucent schema-4 material, rounded type |
| **Nothing-inspired** | Light monochrome, red accent, sharp corners, mono |
| **Neon** | Magenta/cyan/lime on near-black with glitch motion |
| **Material-inspired** | Light lavender Material-style roles, Rubik |
| **Compact** | Dense dark layout with smaller scale and 4 dp corners |

Applying a template replaces the theme design while keeping package identity, permissions, and non-theme sections.

### Thirteen colour roles

The palette editor exposes:

- Primary / On primary
- Background
- Surface / On surface
- Accent
- Secondary
- Tertiary
- Surface variant / On surface variant
- Outline
- Error / On error

Tap a role to open the advanced picker. It provides:

- hue ring and saturation/brightness field;
- hue, saturation, brightness, and opacity sliders;
- HEX, RGB, and HSL entry;
- recent colors and favorites;
- saved thirteen-role palettes;
- complementary, analogous, and monochromatic suggestions;
- readable black/white ink suggestion;
- live WCAG contrast against the role’s actual backdrop; and
- protanopia, deuteranopia, and tritanopia approximations.

Recent colors, favorites, and reusable palettes stay in Studio’s local storage. They are not written into the plugin package as extra assets.

### Shape and type

Configure:

- corner radius: 0–48 dp;
- UI scale: 0.8–1.4×;
- typography scale: 0.85–1.30×;
- light/dark mode and AMOLED black;
- global Light, Normal, Medium, or Bold weight; and
- one of fourteen host-bundled faces.

The font choices are System, Rounded, Mono, Serif, Cursive, Minecraft, Cinzel, Comic Neue, Inter, JetBrains Mono, Orbitron, Press Start 2P, Rubik, and VT323. Plugins name a bundled font id; they do not carry a font file.

### Glass material

Schema-4 glass can set:

- intensity;
- transparency;
- gloss;
- blur from 0–32 dp;
- optional moving sheen and 1.5–20 second period;
- None, Gentle, Snappy, or Bouncy spring;
- hardware-reactive thinning; and
- optional numeric setting link for intensity.

Real backdrop blur needs Android 12. On older versions, GameCore can still draw translucency and the rim but does not claim true backdrop blur.

### Glitch motion

Schema-3 glitch can set:

- intensity;
- 2–6 slices;
- slice and ghost offsets;
- burst timing;
- fracture lines;
- block noise;
- micro-jitter; and
- hardware reaction.

This is a request to GameCore’s renderer, not executable animation code. The user’s animations-off/reduced-motion choice overrides it.

### Identity

A valid package needs:

- package-style id such as `com.example.mytheme`;
- name;
- integer version starting at 1;
- human version name;
- optional developer, description, and changelog; and
- supported plugin API and minimum host version.

The id is the installed identity. Updating a plugin uses the same id and a higher version number; choosing an existing unrelated id would replace/collide with that package.

### Permissions and approved-data scopes

Studio lists the eleven permission ids GameCore recognizes. Requesting a permission grants nothing, and requesting one without content that uses it does not create behavior.

Runtime-backed Studio sections derive these requests automatically:

| Authored content | Required request |
| --- | --- |
| Theme or screens | `change_ui` |
| Live screen/widget/overlay stat | `read_approved_data` plus exact scopes |
| Plugin overlay | `create_overlay` |
| Typed settings | `store_data` |
| Dashboard widgets | `add_dashboard_widgets` |
| Rules | `run_automations`; actions also require `system_actions` |

Other recognized host permissions remain visible for manifest compatibility, but Studio does not pretend to author unsupported Aim Lab content, icon packs, arbitrary settings mutation, or old Pill-control surfaces merely because a permission id exists.

Known approved-data scopes are `sessions`, `games`, `device_stats`, `thermals`, `motion`, and `automation_history`. Required live-data scopes cannot be switched off while content still needs them. After installation, GameCore still asks the user to approve each aperture separately.

## 7. Logic workspace

Logic authors bounded host automations, not scripts.

### Rule shape

A plugin may have up to **32 rules**. Each rule contains:

1. one exact edge trigger;
2. up to ten conditions combined by flat **AND**; and
3. one to eight ordered actions.

There is no OR group, branch, loop, delay, retry, schedule, parallel block, action parameter, arbitrary settings key, command string, or rule-to-rule call.

### Seven triggers

- Game launched
- Game closed
- Session started
- Session ended
- Thermal high
- Battery low
- Charger connected

Rules run only while GameCore’s detection/automation runtime is active.

### Six variables and comparisons

Conditions compare FPS, CPU, GPU, RAM, battery, or temperature with a finite number using `==`, `!=`, `<`, `<=`, `>`, or `>=`.

If a real input is unavailable, it is omitted rather than substituted with zero; a condition that needs it does not match.

### Seven broker actions

| Action id | Fixed host behavior |
| --- | --- |
| `set_peak_refresh_rate` | Set peak refresh bound to 120 Hz |
| `set_min_refresh_rate` | Set minimum refresh bound to 120 Hz |
| `set_window_animation_scale` | Set window animation scale to 0 |
| `set_transition_animation_scale` | Set transition animation scale to 0 |
| `set_animator_duration_scale` | Set animator duration scale to 0 |
| `set_low_power` | Turn Android battery saver on |
| `set_screen_brightness` | Set raw brightness to 200/255; GameCore records and restores it |

The creator chooses action ids and their order, never values or command text. At runtime, rules still require the plugin to be enabled, its automation/system-action grants, Advanced access where required, Shizuku, and a device that supports the action.

## 8. Preview workspace

Preview draws the current theme inside a simulated device frame:

- Compact: 320×640
- Phone: 360×740
- Large: 412×820
- Tablet: 600×900
- portrait or landscape;
- preview font scale from 80–140%; and
- preview brightness from 35–100%.

The frame contains sample buttons, switches, sliders, cards, dialog, and text so roles and shapes can be judged. Those interactions affect only the preview. They do not run a plugin or change a device.

Preview reports title overflow at the selected width/scale. It is a layout illustration, not a promise that every real GameCore screen on every OEM density will be identical.

## 9. Test workspace

### Validation report

Every problem is grouped under its exact draft field. Common failures include:

- invalid package or section id;
- missing/long names;
- duplicate screen/widget/rule/setting ids;
- values outside documented ranges;
- unsupported icon/stat/node on a surface;
- excessive tree size/depth;
- invalid setting defaults/min/max;
- a glass intensity link that does not name a numeric setting; and
- an empty action list.

### Rule simulation

The sandbox is labeled **SIMULATION — no device actions run**. Choose a trigger, set six numeric inputs, mark any input unavailable, and evaluate locally. The result lists matched rule ids and planned action ids.

Simulation never calls Shizuku, the production broker, shell, intents, reflection, or network. It cannot prove that a real device action will work.

### Static conflict observations

Studio reports data-derived issues such as:

- duplicate trigger/condition sets;
- repeated actions;
- peak and minimum refresh actions together; and
- conditions using inputs marked unavailable in the sandbox.

These are observations, not a hidden workflow engine.

### Package inspection

Test composes the exact deterministic archive in memory and shows every path, entry size, total bytes, SHA-256, and container issues.

### Device capability explorer

The explorer reports local facts it can read without probing services or a network, such as Android/version capabilities. The shared maker cannot bind GameCore’s Shizuku broker and therefore never claims it is connected.

### Design check

The theme checker provides:

- score out of 100;
- measured WCAG ink/surface contrasts;
- warnings for alpha/extreme scale/design values;
- one-click black/white ink correction; and
- protanopia, deuteranopia, and tritanopia role previews.

The score covers values the theme owns. Touch-target size and host component spacing remain GameCore’s responsibility and are not falsely scored as plugin properties.

## 10. Export workspace

Export is the final gate. It shows validation problems or, for a valid draft:

- exact entry list and byte counts;
- total size;
- derived section declarations;
- theme schema when present; and
- deterministic SHA-256.

The same draft produces the same bytes on every save: archive paths and order are fixed, timestamps use the zip epoch, and permission/scope sets are sorted. Changing any content changes the digest.

### Package anatomy

A complete package may contain:

```text
my.plugin-1.0.0.gcplugin
├── manifest.json
├── theme.json                  (when enabled)
├── pill.json                   (when an overlay exists)
├── screens/screens.json        (when screens exist)
├── settings.json              (when settings exist)
├── rules/rules.json            (when rules exist)
└── widgets/<widget-id>.json    (one per widget)
```

`manifest.json` is always present. It carries identity, API/minimum host version, derived `declares`, permission requests, and approved-data scopes.

A Studio package contains JSON data only: no DEX, APK, native library, script, WebView, shell command, downloaded font, or signing key.

### Save

**Save .gcplugin** launches Android’s Storage Access Framework. You choose the name and destination, and only that selected document is writable. No broad storage permission is required.

### Import

**Import an existing package** reads through the same bounded container/import path and loads theme, overlay, screens, settings, widgets, and rules when they are representable.

Import behavior is loss-aware:

- malformed, oversized, duplicate, or path-traversing archives are rejected;
- unknown keys inside supported manifest/theme files are reported as warnings;
- nested supported node data the flat editor cannot manipulate is preserved where the importer supports it;
- unsupported package entries such as profiles, Aim Lab data, stats, icon/assets, preview files, or other unknown files cause refusal rather than being silently deleted on re-export; and
- importing edits a draft only. It does not install or enable anything.

Importer caps are 64 archive entries, 2 MiB for one entry, and 4 MiB total expanded data.

## 11. Worked examples

### Example A: theme-only package

1. Build → leave **Include theme.json** on; add no other sections.
2. Theme → apply a template and edit roles/type/effects.
3. Identity → choose a unique id and version.
4. Test → fix contrast and validation issues.
5. Export → expect `manifest.json` and `theme.json` only.

The package automatically requests `change_ui`.

### Example B: Home CPU/RAM widget

1. Build → **Dashboard widgets → Add widget**.
2. Set id/title/size.
3. Add CPU and RAM `stat` children.
4. Test the component tree.
5. Export.

Studio derives `add_dashboard_widgets`, `read_approved_data`, and the `device_stats` scope. The user must still grant them after installation.

### Example C: bounded performance overlay

1. Build → **Overlay → Include overlay**.
2. Set anchor, size, and opacity.
3. Add FPS, CPU, GPU, RAM, battery, temperature, rotation-rate, or movement stats.
4. Keep charts literal; overlays have no retained live chart series.
5. Export and grant overlay/data access in GameCore.

The resulting overlay is non-touchable and fixed-size.

### Example D: user-controlled glass intensity

1. Theme → enable **Glass material**.
2. Build → add an Integer or Float setting such as `glass.intensity`.
3. On that setting enable **Drive glass intensity**.
4. Test → confirm no `theme.glass.intensitySetting` error.
5. Export.

This is the only generic setting bridge currently implemented.

### Example E: thermal automation

1. Logic → Add rule.
2. Choose **THERMAL_HIGH** or another exact trigger.
3. Add a temperature condition if desired.
4. Add a fixed broker action.
5. Test → mark inputs available/unavailable and simulate.
6. Export.
7. In GameCore grant Run automations, System actions, required Advanced access, and ensure Shizuku/device support.

Simulation matching does not guarantee a production action; the runtime gates remain authoritative.

## 12. Runtime lifecycle after export

```text
Studio draft
  → validate
  → deterministic .gcplugin file
  → user chooses file in GameCore
  → GameCore previews identity, compatibility, hash, and permissions
  → user confirms install
  → plugin stored OFF with an empty grant
  → user grants individual permissions/scopes
  → user saves the grant
  → user enables plugin
  → GameCore renders/evaluates only granted, supported sections
```

A plugin may later be disabled, globally stopped, suspended after repeated failures, quarantined, rolled back, or removed. None of those controls depends on the plugin’s own theme remaining readable.

## 13. What Studio deliberately does not offer

The current trusted runtime has no complete path for:

- executable plugin code, scripts, native libraries, shell, or network;
- arbitrary Android intents or external-app control;
- touchable/resizable plugin overlays, custom z-order, or multiple windows per package;
- screen buttons that dispatch actions, navigate, or write settings;
- scheduled, branching, parallel, looping, delaying, or retrying workflows;
- plugin-to-plugin messaging or shared storage;
- generic setting bindings beyond glass intensity;
- custom assets, sounds, downloaded fonts, or arbitrary vectors in component trees;
- on-phone signing with GameCore’s private release key; or
- a marketplace.

Studio omits these rather than displaying controls that export to nothing.

## 14. Limits at a glance

| Item | Limit |
| --- | --- |
| Undo history | 60 draft states |
| Screens | 32 |
| Plugin overlays | 1 per package; host allows up to 3 active globally |
| Settings | 64 |
| Dashboard widgets | 32 |
| Rules | 32 |
| Conditions per rule | 10, flat AND |
| Actions per rule | 8, ordered |
| Enum options | 32 |
| String-setting maximum | 256 characters |
| Screen tree | 500 nodes, depth 24, 100 children/container |
| Overlay/widget tree | 40 nodes, depth 6, 12 children/container |
| Package import | 64 entries, 2 MiB/entry, 4 MiB expanded total |

## 15. Troubleshooting

| Problem | Fix |
| --- | --- |
| Save button is disabled | Open Test or Export and fix every validation field. |
| GameCore says id already installed | Use that id only for an intentional update; otherwise choose a unique package-style id. |
| A permission cannot be turned off in Studio | Authored content requires it. Remove the content/live stat first. |
| A motion stat fails on a screen/widget | Rotation rate and movement are overlay-only. |
| A chart fails in an overlay | Overlay charts are literal-only; remove `stat`/`stat2` live bindings. |
| A widget/screen shows no live value | The real reading may be unavailable, or its approved-data scope was not granted in GameCore. |
| A rule matches in simulation but does nothing installed | Check enabled state, saved grants, Advanced access, Shizuku, runtime activity, and device support. |
| Glass blur looks different on an older phone | Real backdrop blur requires Android 12; older hosts use the supported translucent fallback. |
| Import refuses a package | Read the named entry/reason. Studio refuses data it cannot preserve instead of dropping it. |
| Saved package is not in GameCore | Saving and installing are separate; use GameCore → Plugins → Choose files. |

## 16. Related technical references

- [GameCore feature guide](GAMECORE_FEATURE_GUIDE.md)
- [Plugin Maker reference](PLUGIN_MAKER.md)
- [Plugin Studio capability boundary](PLUGIN_STUDIO_CAPABILITIES.md)
- [Plugin format](PLUGIN_FORMAT.md)
- [Plugin SDK](PLUGIN_SDK.md)
- [Plugin security model](PLUGIN_THREAT_MODEL.md)
- [Debug MCP architecture](PLUGIN_STUDIO_MCP.md)
