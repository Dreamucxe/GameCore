# The `.gcplugin` Package Format

This document describes the on-disk format of a GameCore plugin package, as
implemented in `app/src/main/java/com/gamecore/core/plugin/`. Every field,
limit, and rule below is taken from that source; where a value is not
verifiable in source it is called out explicitly.

## 1. Overview: plugins are data, not code

A `.gcplugin` carries **no classes, no scripts, no binders** — only JSON and
assets that the installed build of GameCore interprets. There is no
`DexClassLoader`, no in-process code execution, no `WebView`, and no scripting
engine anywhere in the plugin path. A plugin can only arrange and style the
native component set GameCore already ships; it can never introduce a surface
GameCore has not vetted. This is why a malformed or hostile package can refuse
to load but can never run arbitrary behaviour inside the app.

Two consequences of "data, not code" run through the whole format:

- The **API version** (a single integer) is the only forward-compatibility
  mechanism, because a plugin cannot run code to probe for a capability.
- Everything a package could carry — screens, actions, button handlers — is a
  reference to something GameCore declares, dispatched through a gated path, not
  a callback the plugin supplies.

## 2. Package (zip) layout

A `.gcplugin` is a zip archive. The one required entry is `manifest.json`
(`PluginPackageLoader.MANIFEST_ENTRY`). The remaining entries are organised into
**sections**, plus two non-section directories.

### Sections (declared via `declares`)

The seven sections are defined by `PluginSection`. Each maps to a fixed entry
prefix in the archive. A section the manifest does **not** declare must **not**
be present in the zip, and a declared section **must** be present — the loader
rejects either mismatch (`UNDECLARED_SECTION` / `MISSING_DECLARED_SECTION`).

| Section id | Archive prefix | Shape |
| ---------- | -------------- | ----- |
| `theme`    | `theme.json`   | single file |
| `pill`     | `pill.json`    | single file |
| `screens`  | `screens/`     | directory |
| `profiles` | `profiles/`    | directory |
| `rules`    | `rules/`       | directory |
| `aimlab`   | `aimlab/`      | directory |
| `stats`    | `stats/`       | directory |

A path belongs to a section if it equals the prefix (for the two single-file
sections) or starts with the prefix (for the directory sections).

### Non-section directories (not declared)

| Prefix      | Purpose |
| ----------- | ------- |
| `previews/` | Screenshots shown on the install screen. Not a section; not declared. |
| `assets/`   | Images referenced by `theme`/`screens`. Capped as bytes; not a section; not declared. |

Any file that is neither `manifest.json`, a preview, a loose asset, nor under a
known section prefix is rejected as `DISALLOWED_FILE`.

Note: the loader validates the manifest, enforces the section/file agreement,
and pins a SHA-256. The *content* models for each section (`theme.json`,
`pill.json`, individual screens, profiles, rules, Aim Lab packs, stats) each
have their own validation pass beyond the scope of this loader document.

## 3. Annotated `manifest.json`

The example below uses only real top-level keys (`PluginManifestCodec.KNOWN_KEYS`)
and real permission ids. Comments are annotations only — JSON does not allow
comments, so a real manifest must not contain them.

```jsonc
{
  // Envelope tag. Must be exactly this string, or the package is rejected
  // (WRONG_FORMAT).
  "format": "gamecore.plugin",

  // Required. Package-style name, validated against the package grammar
  // (not merely sanitised). Max 128 chars.
  "id": "com.example.neon",

  // Required. Display name. Sanitised, max 64 chars, must be non-empty
  // after sanitising.
  "name": "Neon Theme Pack",

  // Required. Positive integer. The plugin's own build number.
  "version": 3,

  // Optional. Display version string. Defaults to version.toString().
  // Max 32 chars.
  "versionName": "1.2.0",

  // Required. The plugin API this package is built against. Positive
  // integer. This build implements API 1.
  "api": 1,

  // Required. Lowest GameCore versionCode the plugin supports. Positive
  // integer. This build is versionCode 27.
  "minHostVersion": 27,

  // Optional. Sanitised, max 64 chars. Defaults to "Unknown".
  "developer": "Example Studio",

  // Optional. Multiline, sanitised, max 512 chars.
  "description": "A neon re-theme with a few Game Pill readouts.",

  // Optional. Each entry is either a bare string id or an object
  // {"id": ..., "scopes": [...]}. Only read_approved_data and
  // modify_settings may carry scopes. Max 32 permissions.
  "permissions": [
    "change_ui",
    { "id": "add_pill_modules" },
    { "id": "read_approved_data", "scopes": ["sessions", "device_stats"] }
  ],

  // Optional. The sections this package carries. Must match the archive
  // exactly. Max 7 (the number of sections).
  "declares": ["theme", "pill"],

  // Optional. Multiline, sanitised, max 2048 chars.
  "changelog": "1.2.0 — added the Pill FPS readout."
}
```

Rules the codec enforces:

- `format` must equal `gamecore.plugin` exactly.
- Required fields: `id`, `name`, `version`, `api`, `minHostVersion`. A missing
  one is `MISSING_FIELD`.
- Any top-level key outside the known set is `UNKNOWN_KEY` — **unknown keys are
  rejected, not ignored**, so a newer-API field can never load with its meaning
  silently dropped.
- `version`, `api`, and `minHostVersion` must each be a positive integer
  (`< 1` is rejected).
- Within a permission object, the only allowed keys are `id` and `scopes`;
  anything else is `UNKNOWN_KEY`.
- A `scopes` list on a permission that takes no scopes is rejected (`BAD_SCOPE`),
  not silently dropped. For `read_approved_data` each scope must be a known
  `DataScope` id.
- Duplicate permissions are deduplicated (last declaration wins).

## 4. Permissions

The complete, closed set is `PluginPermission` — there is no "custom"
permission and no string a package can invent. Two names that look like
permissions, **network access and launching other apps, are deliberately
absent**: a plugin is data and may do neither.

| id | Risk tier | What it touches |
| -- | --------- | --------------- |
| `read_approved_data` | Low | A scoped read of GameCore's *own* data only (never another app's, never raw). Scopes narrow it. |
| `change_ui` | Low | Appearance only — colours, fonts, component styling, declarative screens. Safety screens stay as GameCore drew them. |
| `add_pill_modules` | Low | The Game Pill only — bounded readout/control modules, no full-screen takeover. |
| `create_overlay` | Medium | A bounded overlay window over a foregrounded game. Cannot cover the screen or steal touches from the game. |
| `modify_settings` | Medium | GameCore's own settings, limited to declared scopes. Not device settings. |
| `run_automations` | Medium | Allowlisted automation rules (trigger + conditions + broker actions) only — no arbitrary code. |
| `add_aimlab_content` | Low | Aim Lab target shapes and scenarios only — no humanoid or weapon content. |
| `system_actions` | Advanced (High) | Named, device-affecting actions through the Shizuku Action Broker (e.g. refresh rate, performance mode). Only GameCore's fixed, reviewed actions — never a raw command or shell. The one permission that can change anything outside GameCore; gated behind a separate "Advanced plugin access" opt-in on top of the grant. |

### Scoped permissions

Only two permissions carry scopes:

- `read_approved_data` — scopes are `DataScope` ids:
  - `sessions` — recorded play and training sessions.
  - `games` — the games you've added and their profiles.
  - `device_stats` — live performance figures (frame rate, CPU load).
  - `thermals` — temperature and battery readings.
- `modify_settings` — scopes are settings-area strings, validated against the
  settings catalog downstream (not by the manifest codec).

### Reserved names

These resolve to no permission and are refused with a `RESERVED_PERMISSION`
reason (a more precise message than "unknown"): `network`, `internet`,
`external_app`, `launch_app`, `intent`.

### Risk tiers

`RiskTier` is ordered `LOW` < `MEDIUM` < `HIGH` ("Low risk" / "Medium risk" /
"Advanced"). The tier drives how prominently the install screen warns, whether a
grant needs more than one tap, and the extra opt-in gate for `system_actions`.

## 5. API versioning and compatibility

Defined in `PluginApi`:

- `CURRENT = 4` — the API version this build implements.
- `MINIMUM_SUPPORTED = 1` — the oldest API a plugin may declare and still load.
- `HOST_VERSION_CODE = 50` — this build's `versionCode` (versionName 4.1).

A plugin declares the single `api` integer it was built against and the lowest
host `minHostVersion` it supports. `PluginApi.compatibility(api, minHostVersion)`
returns one of four verdicts, checked in this order:

| Condition | Verdict | Runnable | Meaning |
| --------- | ------- | -------- | ------- |
| `api > CURRENT` | `TooNew` | no | Needs a newer version of GameCore. |
| `api < MINIMUM_SUPPORTED` | `TooOld` | no | Built for an older plugin system; must be rebuilt. |
| `minHostVersion > HOST_VERSION_CODE` | `HostTooOld` | no | API is fine, but the plugin needs a newer app build. |
| otherwise | `Supported` | yes | Compatible with this build. |

The API gap is reported before the host-version gap because "update GameCore" is
the action in both cases and the API gap is more fundamental. There is
deliberately no per-feature capability negotiation and no "runs but degraded"
state: a plugin either renders in full or falls back per-screen to GameCore's
own UI, and that fallback is a loader decision, not a compatibility one.

## 6. Package limits and safety checks

The loader (`PluginPackageLoader`) never touches the filesystem; it consumes
bytes and returns an in-memory `LoadedPlugin`. Every limit below is a real
constant from the source.

### Size and count limits

| Limit | Constant | Value |
| ----- | -------- | ----- |
| Whole package, compressed | `MAX_PACKAGE_BYTES` | `5 * 1024 * 1024` = 5 MiB |
| Any single entry, uncompressed | `MAX_ENTRY_BYTES` | `2 * 1024 * 1024` = 2 MiB |
| Sum of all uncompressed entries | `MAX_TOTAL_UNCOMPRESSED_BYTES` | `10 * 1024 * 1024` = 10 MiB |
| Entry count | `MAX_ENTRIES` | 200 |
| Entry path length | `MAX_PATH_LENGTH` | 180 chars |

Reads are chunked at `READ_CHUNK_BYTES` = `64 * 1024` (64 KiB). A stream load is
capped at `MAX_PACKAGE_BYTES` as it reads, so an outsized or endless stream is
dropped the instant it crosses the ceiling.

### Manifest text caps (`PluginManifestCodec`)

| Field | Max length |
| ----- | ---------- |
| `id` | 128 |
| `name` | 64 |
| `developer` | 64 |
| `description` | 512 |
| `versionName` | 32 |
| `changelog` | 2048 |
| permissions (count) | 32 |
| scopes per permission (count) | 32 |
| declared sections (count) | 7 |

### Container safety checks

- **Zip magic.** The bytes must begin with a zip signature (`PK\x03\x04` local
  file header, `PK\x05\x06` empty EOCD, or `PK\x07\x08`); otherwise rejected as
  `MALFORMED` ("not a zip archive") rather than mis-reported as a missing
  manifest.
- **Zip-slip / path safety** (`safePathOrNull`). An entry name is rejected (not
  sanitised) if it is empty, longer than 180 chars, contains a backslash,
  starts with `/`, contains any ISO control character, or has any empty, blank,
  `.`, or `..` path segment. Forward slash is the only separator admitted.
  Rejection reason: `UNSAFE_PATH`.
- **Per-entry bomb guard.** Each entry is read under its own cap; the
  attacker-controlled declared size is never trusted. Overflow is
  `ENTRY_TOO_LARGE`.
- **Whole-archive bomb guard.** Running total of uncompressed bytes is checked
  against `MAX_TOTAL_UNCOMPRESSED_BYTES`.
- **Entry count.** More than `MAX_ENTRIES` files is `TOO_MANY_ENTRIES`.
- **Duplicate entries.** A repeated normalised path is `DUPLICATE_ENTRY`.
- **Directory entries** are skipped (the path grammar makes the tree implicit).
- **SHA-256.** A hex SHA-256 is computed over the exact received bytes and
  returned in `LoadedPlugin.sha256` — this is what the marketplace index and the
  install screen verify against.
- **No exceptions escape.** Any `ZipException` or other `Throwable` from the zip
  machinery is caught and mapped to `MALFORMED`; a bad download can refuse to
  load but can never take the process down.

### Image handling

The loader admits image entries by extension — `png`, `webp`, `jpg`, `jpeg`
(`IMAGE_EXTENSIONS`) — and caps their **bytes**; it does not decode them.
Decoding happens later via Android's `BitmapFactory` with its own dimension
caps. (This byte-level extension set is the loader's; it is distinct from the
stricter Aim Lab asset rule in section 7.)

### Rejection reasons

Container reasons (`PluginPackageLoader.Reason`): `EMPTY`, `MALFORMED`,
`PACKAGE_TOO_LARGE`, `ENTRY_TOO_LARGE`, `TOO_MANY_ENTRIES`, `UNSAFE_PATH`,
`DUPLICATE_ENTRY`, `MISSING_MANIFEST`, `MANIFEST_REJECTED`, `UNDECLARED_SECTION`,
`MISSING_DECLARED_SECTION`, `DISALLOWED_FILE`. A manifest-content rejection is
wrapped as `MANIFEST_REJECTED` so callers see a single rejection channel.

Manifest reasons (`PluginManifestCodec.Reason`): `EMPTY`, `MALFORMED`,
`WRONG_FORMAT`, `UNKNOWN_KEY`, `MISSING_FIELD`, `BAD_ID`, `BAD_VERSION`,
`BAD_API`, `UNKNOWN_PERMISSION`, `RESERVED_PERMISSION`, `BAD_SCOPE`,
`UNKNOWN_SECTION`, `TOO_MANY_ENTRIES`.

## 7. Aim Lab content rules

A plugin's Aim Lab pack (`aimlab/` section, gated by `add_aimlab_content`) is
validated by `AimLabContentValidator`. GameCore's own aim trainer has humanoid
targets and their models; a plugin's aim content deliberately may not. This is a
product-and-safety line enforced by allow-listing primitive shapes and rejecting
anything reaching for a humanoid or a weapon.

### Allowed primitive shapes

The only target shapes a plugin pack may use (`PrimitiveShape`):

| Shape id | Note |
| -------- | ---- |
| `sphere` | |
| `disc`   | |
| `box`    | |
| `pill`   | a rounded pill shape — a primitive, not a figure |
| `diamond`| |
| `ring`   | |

Any other shape id is rejected ("not a permitted primitive").

### No humanoid, no weapon

A target's name or asset reference is rejected if it contains a humanoid term
(e.g. human, humanoid, person, people, body, torso, head, silhouette, figure,
soldier, enemy, player, zombie, character, man, woman) or a weapon term (e.g.
gun, rifle, pistol, weapon, firearm, ak47/ak-47, m4, knife, sniper, shotgun,
grenade, bullet, ammo, scope, barrel). The rejection names the category so a
developer sees why. (The lists are allow-list-complemented keyword checks, not
an exhaustive synonym blocklist.)

### Assets: images only, no meshes

Primitive packs are textured, not modelled — an image, not a mesh. The only
allowed asset extensions are `.png` and `.webp` (`ALLOWED_ASSET_EXT`). Mesh
formats such as `.glb`, `.obj`, and `.fbx` are not permitted.

### Target count

A pack must have at least one target and at most `MAX_TARGETS` = **64** targets.

## 8. Plugin screens: the closed component set

Where a package carries declarative screens, each screen is a tree of
`ComponentNode`s whose types are limited to the closed `ComponentType` set.
There is no raw view, no HTML, no WebView, no custom drawing — a node of an
unknown type is rejected by the validator.

| Containers | Leaves |
| ---------- | ------ |
| `column`, `row`, `grid`, `scroll`, `card` | `text`, `button`, `slider`, `switch`, `stat`, `spacer`, `image` |

A container may hold children; a leaf may not. Component props are already-
validated primitives only (string, number, boolean, colour int, or an action
reference). A `button`'s "action" prop is the **id** of a declared automation or
broker action; pressing it dispatches through the normal gated path — it does
not run plugin code, because there is no plugin code.
