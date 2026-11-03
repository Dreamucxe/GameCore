# The GameCore Plugin Maker

The Plugin Maker is a standalone Android app for building a data-only GameCore plugin and
saving it as a `.gcplugin` on the phone. It needs no computer, no SDK, and no
GameCore install to run; it produces a file that GameCore installs like any
other package.

GameCore 4.3 also hosts the same editor behind **Plugins › Create a plugin**.
The two entry points share one implementation (`plugin-studio/`), so a change to
the editor or the package writer reaches both.

For a task-oriented walkthrough of every workspace, component, rule, limit and
export/install step, see the
[Plugin Studio feature map and tutorial](PLUGIN_STUDIO_FEATURE_GUIDE.md).

## 1. Installing the maker

The maker is sideloaded from its APK (`GameCore-PluginMaker-1.1.0.apk`). It is
signed with the same certificate as GameCore, so Android treats the two as
coming from the same publisher; neither app needs the other to be installed.

| | |
| --- | --- |
| Package | `com.gamecore.pluginmaker` |
| Version | 1.1.0 (versionCode 2) |
| Minimum Android | 8.0 (API 26) |
| Permissions | none |

The maker asks for no Android permissions. Saving and importing use the system
file picker (the storage access framework), which grants access to the one file
the user chooses and needs no storage permission on any Android version.

## 2. The workspace

The phone-sized editor is six focused workspaces, not an endless settings page:

| Tab | What it holds |
| --- | --- |
| **Build** | project category, read-only screens, one bounded overlay, typed settings and dashboard widgets |
| **Theme** | template gallery, the thirteen colour roles, shape, type, glass and glitch, identity, permissions and data scopes |
| **Logic** | the host's seven edge triggers, six variables, flat conditions and seven allowlisted broker actions |
| **Preview** | a device simulator with compact/phone/large/tablet widths, portrait and landscape, font scaling and brightness |
| **Test** | draft validation, a visibly simulated rule runner, conflict checks, capability observations, WCAG and colour-vision checks |
| **Export** | the package inspector, SHA-256, save, import, and the honest boundary of the format |

Undo and redo sit in the top bar. A project switcher stores multiple drafts on
the device, autosaves the open one after typing stops, and restores the last
project after a restart. Drafts are the studio's own JSON; they are not
packages and cannot be installed.

## 3. What the editor sets

| Section | Fields |
| --- | --- |
| Identity | id, name, version number, version name, developer, description, changelog |
| Permissions | any of the eleven permissions GameCore defines, each switched off until granted in GameCore |
| Core colours | primary, on-primary, background, surface, on-surface, accent |
| Roles | secondary, tertiary, surface variant, on-surface variant, outline, error, on-error |
| Shape | corner radius 0–48 dp, UI scale 0.8–1.4× |
| Type | fourteen bundled faces, a global weight, and a type-ramp scale 0.85–1.30× |
| Material | schema-4 glass: intensity, transparency, gloss, blur 0–32 dp, sheen and period, spring, hardware reaction |
| Motion | schema-3 glitch: intensity, slices, slice and ghost offsets, burst gaps, fracture lines, noise, micro-jitter, hardware reaction |
| Screens | up to 32 host-rendered, read-only component trees with text, cards, icons, stats and charts |
| Overlay | one fixed-size, non-touchable readout with literal charts or live FPS/CPU/GPU/RAM/battery/temperature/motion stats |
| Settings | up to 64 typed bool/int/float/enum/string definitions; a numeric setting can control glass intensity |
| Dashboard | up to 32 bounded, read-only widgets using the same safe node vocabulary |
| Automations | up to 32 edge-triggered rules, each with 10 flat conditions and 8 allowlisted broker actions |

The id must be a package-style id (`com.example.mytheme`) — letters, digits,
underscores and at least one dot — because two packages cannot share one.

Asking for a permission in a package grants nothing. The package arrives
switched off, and each permission is approved on the plugin's own screen inside
GameCore after install. Glass and glitch are requests to the host's renderer,
not code; the user's reduced-motion setting overrides glitch entirely.

## 4. Colour editing

Tapping a colour opens a wheel and a precise editor:

- hue ring, saturation/brightness disc, hue, saturation, brightness and opacity sliders;
- HEX (`#RRGGBB` or `#AARRGGBB`), RGB and HSL entry;
- recent colours, favourites and saved palettes (stored on the device, never in the package);
- complementary, analogous and monochromatic suggestions, plus a readable ink for the current backdrop;
- a live WCAG contrast ratio measured against the surface the role actually sits on;
- protanopia, deuteranopia and tritanopia simulations, labelled as approximations rather than a medical test.

Saved palettes store the theme's thirteen roles in order, so applying one to
another project is a single action.

## 5. What the maker writes

The output is one `.gcplugin` file: a zip containing `manifest.json` plus only the enabled
`theme.json`, `pill.json`, `screens/`, `rules/`, `settings.json` and `widgets/` entries. Its
`declares` list is generated from those entries and required permission/scoped-data requests are
derived from their actual content. There is no code, dex, native library or script, for the reasons in
[PLUGIN_FORMAT.md](PLUGIN_FORMAT.md).

The writer is deterministic: the same draft always produces the same bytes.
Entries carry a fixed timestamp (the zip epoch), permissions are written in
sorted order, and colours are written as `#RRGGBB`, or `#AARRGGBB` when the
author chose an alpha. The SHA-256 shown in the Export tab therefore describes
the package's contents rather than the moment it was saved.

The editor writes the documented format directly; it does not depend on
GameCore's source. The host's own manifest and per-section codecs remain the authority. In this
repository, `PluginStudioPackageTest` and `PluginStudioMultiSectionPackageTest` compose packages
with Studio and decode every emitted section with those real host codecs, so the two copies of the
rules cannot drift apart without a test failing.

## 6. Importing an existing package

**Import an existing package** opens the system picker, checks the zip's names,
sizes and structure, and loads every field the studio understands. It never
executes anything from the package.

- Theme, pill, screens, rules, settings and widget sections are loaded and editable.
- Profiles, Aim Lab, stats, icon, asset, preview and unknown files are refused: Studio cannot
  preserve them, and re-exporting would silently delete data.
- Unknown manifest or theme keys are listed after import. The supported part is
  editable, and the report says plainly which keys will not be written back.
- A malformed, oversized, duplicated or path-traversing archive is refused with
  a reason rather than partially loaded.

Importing never installs and never enables anything. It only fills the editor.

## 7. Installing the result

1. Save the package from the maker to a folder you can find (Downloads is the
   usual choice).
2. Open GameCore › **Plugins**.
3. Tap **Choose files** and pick the `.gcplugin`.
4. Read the preview: it names the plugin, its developer, its version and every
   permission it asks for.
5. Confirm. The plugin arrives switched off; turn it on and grant permissions
   on its own screen.

## 8. What the runtime does not support

The editor does not show these because the current trusted host has no complete execution path for
them and a control that writes nothing would be dishonest:

- touchable plugin overlays, free overlay resizing/z-order and multiple overlay windows per package;
- screen buttons, sliders or switches that claim to run actions or write settings (the current
  shared host renderer is deliberately read-only);
- scheduled, branching, parallel, looping or retrying workflows;
- plugin-to-plugin messaging or shared storage;
- arbitrary animations; the glitch block is the only motion the host renders,
  and glass is the only material;
- custom assets, sounds, images, downloaded fonts or arbitrary vectors in component trees;
- on-device signing with GameCore's release key: putting that private key in either APK would
  destroy its trust value;
- AI generation or collaboration, which are not implemented here and are not
  required for editing or exporting.

The complete audited boundary is recorded in
[PLUGIN_STUDIO_CAPABILITIES.md](PLUGIN_STUDIO_CAPABILITIES.md).

## 9. What is verified, and what is not

Verified by the repository's tests:

- a composed package contains exactly the entries implied by its enabled sections;
- the manifest carries matching declarations plus the permission and scope requests derived from
  live content;
- the theme carries the six required colours, the seven roles, the type block
  and the optional effects;
- two compositions of the same draft are byte-identical, and changing a colour
  changes the bytes;
- the host's real manifest, theme, screen, overlay, settings, widget and rule codecs decode what
  Studio writes;
- the importer round-trips every supported section, refuses unsupported files, and
  reports unknown keys instead of hiding them;
- colour conversions, WCAG contrast, palette generation and all eight templates
  behave as documented.

Not verified here:

- how a particular OEM's file picker presents the save location;
- the host's behaviour on an unsupported device (for example, real backdrop
  blur below Android 12);
- any package produced by a modified build of the maker.

The maker is a tool for producing data. It is not a sandbox boundary, and a
package is only ever as trustworthy as the person who made it — which is why
GameCore still shows every permission before anything is granted.

## 10. Agent access (debug builds)

The debug build can expose its live draft to a local MCP server on loopback. The
bridge is compiled only into the debug variant and runs in a debug-only
foreground service with a persistent notification, so it stays reachable while
you work in a terminal. It reads and replaces the open draft, runs the same
validation, and returns package bytes composed by the same writer. It cannot
install a plugin, grant a permission, run a rule or execute code, and the
release APK contains no bridge, service or network permission at all.

The server, the setup script, the copy-paste agent prompt and the full
architecture tree are in
[PLUGIN_STUDIO_MCP.md](PLUGIN_STUDIO_MCP.md) and
`tools/gamecore-studio-mcp/`.
