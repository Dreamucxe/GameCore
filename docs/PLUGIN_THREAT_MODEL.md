# GameCore Plugin Platform — Security Threat Model

Scope: the on-device plugin platform under
`app/src/main/java/com/gamecore/core/plugin/` and the elevated-shell seam under
`app/src/main/java/com/gamecore/core/shizuku/`. This note records what a plugin is,
what it is kept away from, the threats the design anticipates, and the mitigations
that are actually in the source. Where this note and the source disagree, the source
wins; known discrepancies are called out inline.

The platform described here is the one that ships in GameCore **versionCode 28
(3.8)**, the first build to carry it. `PluginApi.HOST_VERSION_CODE` is 28, and
`PluginHostVersionTest` pins it to `BuildConfig.VERSION_CODE` so the two cannot drift:
a plugin declaring `minHostVersion` 28 runs here, 29 is refused as `HostTooOld`, and
the refusal says so rather than failing at load time.

A single sentence frames everything below: **a plugin is data, not code.** It ships a
manifest, declarative themes/screens, bounded Game Pill modules, allowlisted automation
rules, and Aim Lab content. It never ships an executable that GameCore runs, and it is
never handed a binder, a shell, an argument vector, or a command string. Its only
influence on a device-affecting change is a single untrusted *value* that must survive a
form check and a clamp before GameCore — not the plugin — builds the command.

---

## 1. Architecture and trust boundary

Every host-side action taken on a plugin's behalf is funnelled through one API surface,
then a permission gate, then (for device changes) the action broker, then GameCore core,
and only then the elevated shell. No call site may reorder or skip these stages.

```
  UNTRUSTED                           |            TRUSTED (GameCore process)
                                      |
  +-----------------+                 |
  |  Plugin         |  manifest +     |
  |  (DATA only:    |  declarative    |
  |   manifest,     |  content +      |
  |   themes,       |  ONE string     |
  |   modules,      |  value per      |
  |   automations,  |  action req     |
  |   aimlab packs) |                 |
  +--------+--------+                 |
           |                          |
           |   ActionRequest(id,value)|   <-- never a command, shell, binder, or argv
           v                          |
  = = = = =TRUST BOUNDARY = = = = = = = = = = = = = = = = = = = = = = = = = = = = = = =
                                      |
                            +---------v----------+
                            |  Plugin API Facade |  rate limit + audit, per (plugin,op)
                            |  (PluginApiFacade) |
                            +---------+----------+
                                      |
                            +---------v----------+
                            | Permission Gate    |  grant? advanced opt-in? scope?
                            | (PluginPermission- |  kill switch / disabled / suspended?
                            |  Gate)             |
                            +---------+----------+
                                      |
                            +---------v----------+
                            | Action Broker      |  closed registry (BrokerAction),
                            | (ShizukuAction-    |  ValueForm.accepts, clamp, tier,
                            |  Broker)           |  per-use HIGH confirm, auto-revert
                            +---------+----------+
                                      |
                            +---------v----------+
                            | GameCore Core      |  builds ShellCommand.putSetting(...)
                            | (typed command     |  from the registry's WritableSetting
                            |  construction)     |  + validated value (argv element)
                            +---------+----------+
                                      |
                            +---------v----------+
                            | Shizuku /          |  execute(command: ShellCommand)
                            | ElevatedShell      |  — a typed command, never a string
                            +--------------------+
```

Stated plainly: **a plugin never receives a binder, a shell handle, an argument vector,
or a command string, and never names a settings key, namespace, or shell verb.** It names
a `BrokerAction` by `id` and hands over one string value (`ActionRequest`,
`BrokerAction.kt`). `ElevatedShell.execute` takes a `ShellCommand` — one of a closed set
of typed cases — "so there is no expressible way to run a command that is not one of the
enumerated cases" (`ElevatedShell.kt` doc comment). "GameCore never runs arbitrary shell
commands" is a property of the type system, not a promise in a comment.

The presentation layer sits outside that chain rather than at the top of it. Every
screen reaches plugins through the `PluginRegistry` interface and nothing else, and the
interface is the whole surface: four flows (`installed`, `activeTheme`, `killSwitchOn`,
`isLoaded`) and ten suspending methods. A ViewModel holding it can enumerate plugins,
flip a switch, pick a theme and remove a sideloaded plugin; it cannot obtain an
`ElevatedShell`, a grant store it could write directly, or the raw bytes of a package.
That is the point of it being an interface rather than the implementation — the list a
reviewer has to check is those fourteen members, not the surface of a concrete class.
Two of them are worth naming here: `install` stores a package with an empty grant and
the plugin disabled, so installing is deliberately not consenting; and `grant` records
the user's decision without acting on it, because `PluginPermissionGate` re-checks the
grant against the manifest on every call, which leaves an over-reaching grant inert
instead of trusted.

---

## 2. Assets protected

- **The elevated (Shizuku/ADB-level) authority** the user granted — the single most
  valuable capability on the device. The whole platform exists to let plugins benefit
  from it without ever touching it.
- **Device system/secure settings** reachable only through the closed `WritableSetting`
  allowlist (`ShellCommand.kt`), e.g. refresh-rate bounds, brightness, battery saver,
  animation scales — never an arbitrary `settings put` key.
- **GameCore's own data**: sessions, games, device stats, thermals — readable only in the
  scoped slices the user approved (`READ_APPROVED_DATA`, `DataScope`), never raw and never
  another app's.
- **Per-plugin stored state**, confined to a derived namespace (`PluginNamespace`) so one
  plugin cannot read or overwrite another's values.
- **Host process stability and responsiveness** — protected from crash loops, floods, and
  unbounded content/log growth.
- **The user's trust decisions** — the grant, the advanced opt-in, the per-use HIGH
  confirmation, and the global kill switch are the authority, not the manifest's requests.

---

## 3. Threats and mitigations

| Threat | Mitigation (and where it lives) |
| --- | --- |
| **Command injection** — plugin smuggles shell metacharacters to run arbitrary commands | The plugin never supplies a command. `ElevatedShell.execute(command: ShellCommand)` takes a typed command, not a string; arbitrary shell is unrepresentable. The value is passed as an argv element built by `ShellCommand.putSetting(setting, value)` from the registry's own `WritableSetting`, never concatenated into a string. Before that, `BrokerAction.setting.accepts(value)` (→ `ValueForm.accepts`) enforces an explicit grammar + range per setting (e.g. `REFRESH_RATE` admits only `0` or `20..480`; `BOOLEAN_INT` is exactly `"0"`/`"1"`, not `"true"`). The action set is the closed `BrokerAction` registry; `fromId` returns null for anything not in it → `UNKNOWN_ACTION`. There is no "custom action" and no settings key a plugin can name. |
| **Privilege escalation** — plugin reaches device changes it was never approved for | `SYSTEM_ACTIONS` is the only permission that leaves GameCore, is `RiskTier.HIGH`, and needs the separate "Advanced plugin access" opt-in on top of its grant. `PluginPermissionGate.check` enforces, in fixed order: kill switch, disabled, suspended, manifest-requested, granted, advanced opt-in (`ADVANCED_NOT_ENABLED`), scope. A grant can never exceed the manifest request (`NOT_REQUESTED`). On top of the gate, HIGH-tier broker actions require a per-use confirmation (`confirmHighRisk`), which defaults to refusing so a host that forgets to wire it fails closed (`ShizukuActionBroker.run`). The gate has no "already validated" fast path — every call re-checks. |
| **Zip-slip / path traversal** — malicious archive entry escapes the plugin tree | `PluginPackageLoader.safePathOrNull` rejects (not sanitises) absolute paths, backslashes/UNC shapes, any `.`/`..`/blank segment, leading/double slashes, ISO control chars, and over-long names; forward slash is the only separator. The zip magic is checked up front; declared entry sizes are never trusted (`readEntryCapped` reads and stops at the cap); `structuralCheck` rejects undeclared/disallowed files. Storage keys get the same treatment via `PluginNamespace.qualify` (grammar + no dotted-traversal). |
| **Resource exhaustion / DoS** — oversized package, zip bomb, call flood, unbounded log | Loader caps: `MAX_PACKAGE_BYTES` 5 MiB, `MAX_ENTRY_BYTES` 2 MiB, `MAX_TOTAL_UNCOMPRESSED_BYTES` 10 MiB (defeats compression bombs), `MAX_ENTRIES` 200, `MAX_PATH_LENGTH` 180; streams are read capped so an endless stream is dropped at the ceiling. `PluginRateLimiter` fixed-window per (plugin, operation); the broker is additionally limited to `actionsPerWindow` (20) per `windowMs` (10 s). The Aim Lab validator caps `MAX_TARGETS` 64. `PluginAuditLog` is a bounded ring (`capacityPerPlugin` 200) because an unbounded log is its own DoS. The declarative languages are bounded in their own files, not merely by the rate limiter: `StatExpression` caps a formula at `MAX_SOURCE_LENGTH` 256 chars, `MAX_TOKENS` 128 and `MAX_DEPTH` 16, over a grammar of `+ - * /`, parentheses and unary minus — there is no call, no loop and no recursion to bound, and an unknown variable is an error rather than a silent zero. `RulesCodec` admits `MAX_RULES` 32 per document; `AutomationEngine` bounds each rule to `maxActionsPerRule` 8 and `maxConditions` 10 over a closed 7-trigger / 6-operator set, so a rule set is a flat conjunction evaluated once per trigger and cannot iterate. `ScreenValidator` caps a screen at `maxNodes` 500, `maxDepth` 24, `maxChildren` 100, `maxGridColumns` 8 and `maxStringLength` 2 000, and stops collecting reasons past 50 so even the rejection path is bounded. §3.1 lists every ceiling in one place. |
| **Malicious content** — plugin ships a person-shaped target or weapon model | `AimLabContentValidator` allow-lists primitive shapes only (`sphere/disc/box/pill/diamond/ring`); any other `shapeId` is rejected. Target names and asset refs are screened for humanoid terms (`human`, `silhouette`, `figure`, `soldier`, `enemy`, ...) and weapon terms (`gun`, `rifle`, `knife`, `scope`, ...), with a specific reason. Assets must be textures (`.png`/`.webp`), never meshes (`.glb`/`.obj`/`.fbx`). |
| **Bad-state persistence** — plugin strands the device in a harmful setting | Numeric values are clamped to the action's tighter bound before the form check and the clamp is audited (`clampIfNeeded` / `PluginApiFacade.noteClamp`); out-of-range is corrected, not passed through. `autoRevert` actions capture the prior reading before the write so a restore survives even if GameCore is killed (`ShizukuActionBroker.run`, `AutoRevert`). `PluginSupervisor` auto-disables a plugin after `crashLoopThreshold` (2) consecutive startup crashes and auto-suspends after `failureThreshold` (10) failures within `failureWindowMs` (60 s). The global `systemKillSwitch` revokes all plugin system access at once (`SYSTEM_KILL_SWITCH`). |
| **Cross-plugin access** — one plugin reads/overwrites another's data | `PluginNamespace.of(pluginId)` derives a `plugin.<id>.` prefix; the namespace is derived by the host, never supplied by the plugin, so a plugin cannot choose another's namespace. `qualify` confines every key; `belongsTo` guards reads. Rate-limit and audit keys are also per-plugin (`"$pluginId::$operation"`), so one plugin's flood cannot throttle another. |

### 3.1 Bounded resources — every ceiling in one place

Every number a plugin could otherwise choose for itself. They are gathered here because
"is anything unbounded?" is a question a reviewer should be able to answer by reading one
table rather than nine files, and because a ceiling nobody can find is a ceiling nobody
notices the absence of. Each is enforced in the file named, not in the UI.

| Resource | Ceiling | Enforced in | Why it has a number |
| --- | --- | --- | --- |
| Package bytes | 5 MiB (`MAX_PACKAGE_BYTES`) | `PluginPackageLoader` | The read is capped before the archive is opened, so an oversized file costs the cap, not the file. |
| One entry, uncompressed | 2 MiB (`MAX_ENTRY_BYTES`) | `PluginPackageLoader` | Declared sizes are never trusted; `readEntryCapped` stops at the cap mid-stream. |
| All entries, uncompressed | 10 MiB (`MAX_TOTAL_UNCOMPRESSED_BYTES`) | `PluginPackageLoader` | The anti-compression-bomb bound: a 5 MiB archive cannot inflate past this. |
| Entries per package | 200 (`MAX_ENTRIES`) | `PluginPackageLoader` | Thousands of tiny entries are a DoS that no byte cap catches. |
| Entry path length | 180 (`MAX_PATH_LENGTH`) | `PluginPackageLoader` | Long names are the other half of path abuse, and the filesystem has its own limit. |
| Permissions per manifest | 32 (`MAX_PERMISSIONS`) | `PluginManifestCodec` | A grant screen the user can actually read; there are far fewer real permissions than this. |
| Scopes per permission | 32 (`MAX_SCOPES_PER_PERMISSION`) | `PluginManifestCodec` | Same reason, one level down. |
| Declared sections | 7 (`MAX_DECLARED_SECTIONS`) | `PluginManifestCodec` | Exactly the number of `PluginSection` entries — a manifest cannot declare a section twice to get more. |
| Manifest text | id 128, name 64, developer 64, versionName 32, description 512, changelog 2 048 | `PluginManifestCodec` | Every field is sanitised *and* truncated, so no plugin string can grow a list row or a dialog without bound. |
| Stat formula | 256 chars, 128 tokens, depth 16 | `StatExpression` | Three independent caps because length alone does not bound nesting, and nesting is what costs stack. |
| Rules per document | 32 (`MAX_RULES`) | `RulesCodec` | Parse-time bound: an endless rules file would be a DoS against the install flow itself. |
| Actions per rule / conditions per rule | 8 / 10 | `AutomationEngine` | Evaluation is one pass over a flat conjunction; these bound the work per trigger. |
| Screen content | 500 nodes, depth 24, 100 children, 8 grid columns, 2 000 chars per string | `ScreenValidator` | A declarative screen is composed on the host's main thread, so its size is the host's problem. |
| Rejection reasons collected | 50 | `ScreenValidator` | The error path is bounded too; a pathological screen cannot produce a pathological report. |
| Aim Lab targets | 64 (`MAX_TARGETS`) | `AimLabContentValidator` | A scene the renderer can draw at frame rate, which is the whole point of Aim Lab. |
| Theme corner radius | 0..48 dp | `PluginTheme` + `PluginThemeCodec` | Clamped, not rejected — see §3.2. |
| Theme UI scale | 0.8..1.4 (clamped), resolved density 80..160 % | `PluginTheme`, `PluginThemeBridge` | The plugin's scale multiplies the user's, so the *product* is what has to be bounded. |
| Theme name / font | 48 chars / one of 4 bundled fonts | `PluginThemeCodec` | A theme picks a font; it cannot ship or name a font file. |
| API calls | fixed window per (plugin, operation) | `PluginRateLimiter`, `PluginApiFacade` | The limit and window are the call site's (the broker's own are below), so each operation is throttled on its own terms rather than sharing one global budget. |
| Device actions | 20 per 10 s (`actionsPerWindow` / `windowMs`) | `ShizukuActionBroker` | The only path that leaves GameCore gets the tightest, hard-coded window. |
| Audit entries per plugin | 200 (`capacityPerPlugin`, ring) | `PluginAuditLog` | Oldest entries drop; the log cannot become the exhaustion it exists to record. |
| Storage key length | 128 (`PluginNamespace.MAX_KEY_LENGTH`) | `PluginPermissionGate` | Keys are grammar-checked and bounded before they are qualified into the plugin's namespace. |
| Consecutive startup crashes | 2 (`crashLoopThreshold`) → auto-disable | `PluginSupervisor` | Two is a loop, not bad luck. |
| Failures in a window | 10 per 60 s (`failureThreshold` / `failureWindowMs`) → auto-suspend | `PluginSupervisor` | Suspension is this-run only and says why, so the user is not left with a dead row. |
| Developer test session | 60 s (`TEST_WINDOW_MS`) then auto-revert | `DeveloperTestMode` | A forgotten or crashed test session cannot leave a plugin half-applied. |

### 3.2 A theme is the quietest denial of service

The lowest-risk thing a plugin ships is also the one with the least obvious failure
mode. A theme carries no capability at all, yet two of its fields can make the app
unusable without doing anything the codec would call invalid: an `onSurface` a hair from
`surface` leaves real text on screen that nobody can read, and a UI scale at its bound,
multiplied by a user who has already scaled up, leaves rows that clip instead of grow.
Neither is a malformed value. Both are a device the user cannot drive — including, if it
is handled naively, the screens they would use to turn the theme off.

The handling is deliberately asymmetric to the rest of this document: **the offending
role degrades and the theme still applies.** `PluginThemeBridge.legibleInk` measures
every plugin-supplied ink against the backdrop it will actually be drawn on with
`ContrastMath` (§11's own arithmetic) and, below `MIN_CONTRAST_RATIO`, substitutes
GameCore's house ink for that one role — and if the house ink does not clear the bar
against the plugin's backdrop either, black or white chosen off that backdrop's
luminance, so the function always returns something readable rather than a best effort.
Every other colour the plugin asked for stands. Scale is treated the same way: the
plugin's factor multiplies the user's setting and the product is clamped into
80..160 %, and corner radius and `uiScale` are `coerceIn`-clamped by the codec rather
than rejected.

Rejecting the whole theme over one unreadable pair would be the worse answer, and not
only on taste: it would make an unreadable colour pair the plugin's way of refusing to
load at all, and it would throw away the colours that were fine. Degrading one role
keeps the plugin's work and keeps the app legible, and §24 holds for the result — the
manager names the plugin responsible for how the app looks, which is the first thing a
user wants when they want it to stop.

The backstop is structural rather than cosmetic. `PluginRegistry.resetToDefaultTheme` is
a named operation, not `setActiveTheme(null)` spelled differently, precisely because it
is the escape hatch the whole feature is only safe with: it is reachable from a screen a
plugin can never restyle, it writes nothing it could fail to write, and it does not need
the offending plugin to still parse. A theme that is somehow intolerable despite the
degradation is therefore always one tap from gone, and the tap is never a dead one
(§32).

---

## 4. Explicitly excluded capabilities (no permission grants these)

These are not blocklisted strings a clever manifest might slip past; they are
**unrepresentable** — there is no type, enum case, or command shape that could carry them,
so no grant, opt-in, or confirmation can reach them.

- **Raw / arbitrary shell.** `ElevatedShell.execute` takes a `ShellCommand`, a closed set
  of typed cases — not a string and not an argv. There is no case meaning "run this text",
  so arbitrary shell cannot be constructed, let alone granted.
- **Package install / uninstall.** No `BrokerAction` and no `ShellCommand` case installs or
  removes a package. The registry comment lists this among actions "never representable
  here, by omission and by the absence of any `WritableSetting` or `ShellCommand` case".
- **Granting / revoking permissions for other apps.** The only permission-affecting command
  is GameCore's own self-grant flow (`GrantSelfPermission` is bound to `ownPackageName`,
  `SelfGrantablePermission`), which is not a plugin action at all. There is no case that
  takes an arbitrary target package.
- **Reading other apps' data or `/data`.** `READ_APPROVED_DATA` is confined to enumerated
  `DataScope`s over GameCore's own data; no action or command reads another app's storage.
- **Arbitrary `settings put`.** Writes go only through the closed `WritableSetting`
  allowlist reached via a `BrokerAction`; a key with no action pointing at it is
  unreachable. A plugin cannot name a namespace or key.
- **Arbitrary broadcasts / intents.** No `ShellCommand` case and no permission sends a
  broadcast or launches an intent. `launch_app` and `intent` are reserved names (below).
- **Screen capture / screen reading.** No action, command, or permission captures or reads
  the screen. Overlays are bounded windows that "cannot cover the screen or take touches
  from the game" (`CREATE_OVERLAY`).
- **A validation bypass, including in developer mode.** The one place a bypass would be
  convenient is "test this plugin" in Settings → Developer, where an author loads an
  unpackaged folder. `DeveloperTestMode` carries no such flag: it holds a session and a
  deadline and nothing else, and a locally loaded folder goes through the same codecs and
  the same gate a downloaded package does. Developer mode changes where the bytes come
  from, not whether they are trusted — "the absence of a bypass is the point, so there is
  deliberately no field for one" (`DeveloperTestMode` doc comment). The session also
  expires on its own after `TEST_WINDOW_MS` (60 s), so the loose end developer mode could
  leave behind closes without anyone remembering to close it.
- **Network / external-app launch.** `network`, `internet`, `external_app`, `launch_app`,
  and `intent` are in `PluginPermission.RESERVED`. The manifest codec rejects them as
  `RESERVED_PERMISSION` with a reason explaining why, rather than silently dropping them —
  and there is no enum case they could resolve to even if they slipped the reserved check.
  "A plugin is data and may not do either" (network or launching apps) — `PluginPermission`
  doc comment.

The reserved-name check gives developers an honest "reserved" message, but it is defence in
depth, not the real barrier: even an unreserved typo resolves to `null` (unknown permission)
and still has no capability behind it.

---

## 5. Residual risks and assumptions

- **Shizuku is user-granted ADB authority.** The elevated shell is optional and only as
  trustworthy as the ADB-level access the user themselves started. GameCore cannot and does
  not escalate beyond what the user already granted to Shizuku; if that authority is
  misused outside GameCore, that is outside this boundary. Every caller has a
  standard-Android fallback, so the platform degrades rather than fails when Shizuku is
  absent (`isAvailable()` → `SHIZUKU_UNAVAILABLE`, nothing attempted).
- **Curated index as the trust root.** Distribution relies on a single curated JSON index
  in a GitHub repo, fetched over HTTPS **with certificate pinning**, with each entry
  carrying a **SHA-256** of the exact approved bytes (`MarketEntry.kt`). The index is the
  authority, the download server is not trusted, and a server returning different bytes past
  the pinning still fails the hash. Residual assumption: the integrity of the curated index
  and the review that populates it. A plugin that behaves in review but changes behaviour at
  runtime is caught only to the extent the audit trail and supervisor expose it.
- **Device-specific setting bounds.** Some `ValueForm`s (e.g. `COLOR_TEMPERATURE`,
  `COLOR_MODE`) deliberately admit a wider range than any one device enforces, because the
  per-device config is not readable; the honest reject-only-what-is-not-a-value approach
  leans on the device's own clamping plus GameCore's read-back/auto-revert, not on GameCore
  knowing the exact bound.
- **Host wiring.** Several guarantees depend on the host calling the broker/facade on every
  plugin-driven action and on wiring `confirmHighRisk`. The defaults fail closed (confirm
  defaults to refuse; the gate has no fast path), but a host that bypasses the facade
  entirely would bypass rate-limiting and audit — the architecture mitigates this by making
  the facade/broker the only path that can construct a write-effect command.
- **In-memory supervisor/limiter state.** Rate-limit windows and crash/failure counters live
  in memory; a process restart resets them. This is acceptable for flood control but means
  cross-restart abuse patterns rely on the durable grant/suspension state in
  `PluginGrantStore` rather than the counters.
