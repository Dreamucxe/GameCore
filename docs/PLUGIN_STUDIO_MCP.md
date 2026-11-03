# Plugin Studio MCP

The studio has a **debug-only** loopback bridge and a dependency-free MCP server. Together they let
a local agent read and replace the live draft in the Plugin Maker, run the studio's real validation,
compose a deterministic `.gcplugin`, and inspect a package with the studio's real importer.

The release APK does not contain the bridge. It is a development tool, not a remote control for a
user's installed GameCore.

## 1. What an agent can and cannot do

| Can | Cannot |
| --- | --- |
| Read the live draft in the open Plugin Maker | Install a `.gcplugin` |
| Replace the live draft; the editor updates and autosaves | Grant a permission or a data scope |
| Validate with `PluginDraft.problems()` | Run an automation, trigger a broker action or change a setting |
| Apply one of the eight theme templates | Execute plugin code, shell commands or intents |
| Compose and save a deterministic package | Open a screen, tap the UI or take a screenshot |
| Inspect an existing `.gcplugin` with the studio importer | Sign with GameCore's release key |
| Ask for the capability boundary | Add unsupported sections the studio cannot represent |

The last row of the negative column matters: an agent cannot invent a feature the host does not
run. If a draft asks for one, `StudioCapabilities.unsupported` says so and the editor never offered
it in the first place.

## 2. Architecture tree

```
MCP client (OpenCode, another stdio client)
  │  newline-delimited JSON-RPC 2.0 on stdin/stdout
  ▼
tools/gamecore-studio-mcp/
├── server.py                     process entry point; puts the package on sys.path
├── gamecore_mcp/
│   ├── __init__.py               version
│   ├── protocol.py               framing, initialize/ping, tools, prompts, errors
│   ├── tools.py                  tool schemas, handlers, verified export writer
│   ├── bridge.py                 HTTP client, fixed debug token, bridge errors
│   ├── capabilities.py           offline fallback for the capability boundary
│   └── prompts.py                the setup_plugin_studio MCP prompt
├── tests/
│   ├── fake_bridge.py            contract stand-in for the Kotlin bridge
│   ├── test_protocol.py          JSON-RPC lifecycle, batches, notifications, prompts
│   └── test_tools.py             every tool against the fake bridge
├── setup.sh                      checks, optional install/register/build/test
├── AGENT_PROMPT.md               copy-paste prompt for a coding agent
└── README.md                     short pointer to this document
  │  HTTP/1.1 on 127.0.0.1:47653, X-GameCore-Studio-Token
  ▼
Plugin Maker debug APK (com.gamecore.pluginmaker.debug)
├── plugin-maker/src/debug/
│   ├── AndroidManifest.xml       adds the debug Application, service and permissions
│   ├── kotlin/.../DebugBridgeApplication.kt
│   │                              starts the foreground bridge service
│   ├── kotlin/.../StudioBridgeService.kt
│   │                              owns the bridge; keeps the process alive
│   └── res/                      notification strings and a small status icon
├── plugin-maker/src/main/
│   ├── kotlin/.../MakerStudio.kt process-lifetime StudioSession
│   └── kotlin/.../MainActivity.kt visible draft follows the session
├── plugin-studio/src/main/
│   ├── core/StudioSession.kt     one draft, listeners, change-only notifications
│   ├── core/StudioCapabilities.kt
│   │                              capability boundary as JSON
│   ├── core/DraftCodec.kt        storage JSON ←→ PluginDraft
│   ├── core/PluginComposer.kt    deterministic .gcplugin writer
│   ├── core/PluginImporter.kt    loss-aware package reader
│   ├── core/PackageInspector.kt  non-throwing container inspection
│   └── ui/StudioEditor.kt        Build, Theme, Logic, Preview, Test, Export
└── plugin-studio/src/debug/
    └── kotlin/.../mcp/
        ├── HttpTransport.kt      capped HTTP/1.1 reader/writer
        └── StudioMcpBridge.kt    routes, token, dispatch, JSON responses
  │
  ▼
DraftStore (SharedPreferences)     ordinary autosave, same as a manual edit
```

The arrow that matters: **the MCP process never writes package JSON itself.** It sends a draft to
the app; the app decodes it with `DraftCodec`, validates with `PluginDraft.problems()`, and composes
with `PluginComposer`. The import path is the same: `PluginImporter` is the studio's own reader. A
test in `:app` already checks that what `PluginComposer` writes is what GameCore's host codecs read.

## 3. Every part, in order

### 3.1 `StudioSession` — the shared draft

`plugin-studio/src/main/kotlin/com/gamecore/studio/core/StudioSession.kt`

- Holds one `PluginDraft` behind a lock.
- `current()` returns the last committed value.
- `update(next)` commits first, then notifies listeners; equal values do not notify. That prevents a
  Compose recomposition loop and a duplicate autosave.
- Listeners run on the caller's thread. The editor's listener marshals to the main thread; the
  bridge calls `update` from an HTTP worker.
- A listener exception is contained, so a broken observer cannot make a committed change look
  rejected.

The session is in the library's main source set because both the debug bridge and the editor need
the same object. Release builds contain the session but never start a bridge.

### 3.2 `MakerStudio` — the process scope

`plugin-maker/src/main/kotlin/com/gamecore/pluginmaker/MakerStudio.kt`

A single `StudioSession`. It lives outside the activity so it survives activity recreation and is
reachable by the debug `Application` before the first frame. The editor reads it, the bridge reads
and writes it, and autosave persists it through the normal `DraftStore` path.

### 3.3 `MainActivity` — the visible mirror

`plugin-maker/src/main/kotlin/com/gamecore/pluginmaker/MainActivity.kt`

- Keeps a Compose `draft` state initialised from the session.
- Subscribes in a `DisposableEffect`; a bridge update from a worker thread is posted to the main
  thread before touching Compose.
- `onDraftChange` calls `session.update(...)`; the listener brings the new value back to the
  editor, so a manual edit and an MCP edit take the same path.
- No UI control was added. The editor is still the same six-tab screen.

### 3.4 `HttpTransport` — the closed HTTP subset

`plugin-studio/src/debug/kotlin/com/gamecore/studio/mcp/HttpTransport.kt`

`com.sun.net.httpserver` is Java SE and is not on Android. A framework would add a dependency and
an attack surface for a handful of routes. This is a byte-level parser for exactly:

- one request per connection, `HTTP/1.1` or `HTTP/1.0`;
- `Content-Length` bodies, no chunked encoding;
- 16 KiB of headers and 6 MiB of body, checked before allocation;
- a response that always says `Connection: close`, `Cache-Control: no-store`, `nosniff`.

Malformed input becomes a typed `BadHttpRequest` and a 400/413, never a stack trace.

### 3.5 `StudioMcpBridge` — routes and authority

`plugin-studio/src/debug/kotlin/com/gamecore/studio/mcp/StudioMcpBridge.kt`

- Binds `127.0.0.1` on port **47653** by default, backlog 16, four daemon workers.
- Requires `X-GameCore-Studio-Token: gamecore-studio-debug-v1` on every route except `/v1/health`.
- Uses `MessageDigest.isEqual` for the token comparison.
- Returns JSON only; every route is under `/v1`.
- Caps inspection packages at `PluginImporter.MAX_TOTAL_BYTES` (4 MiB).
- Logs method, path and outcome only — never a draft body or package bytes.
- A failed bind is logged and the app continues; a debug convenience must not crash the studio.
- `close()` stops the accept loop and the workers; tests use port 0 to avoid collisions.

### 3.6 `DebugBridgeApplication`, `StudioBridgeService` and the debug manifest

`plugin-maker/src/debug/kotlin/com/gamecore/pluginmaker/DebugBridgeApplication.kt`
`plugin-maker/src/debug/kotlin/com/gamecore/pluginmaker/StudioBridgeService.kt`
`plugin-maker/src/debug/AndroidManifest.xml`

The debug manifest adds `android:name=".DebugBridgeApplication"`, a non-exported foreground service
and three permissions: `INTERNET`, `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`. The
release manifest has none of them.

The application starts `StudioBridgeService` in `onCreate`. The service creates the bridge against
`MakerStudio.session`, calls `start()`, and shows a low-importance ongoing notification that names
the loopback port. The notification is not decoration: a foreground service raises the process
priority, so Android does not freeze the bridge the moment the user switches to a terminal. Before
this, four backgrounded requests could occupy all four workers and leave the port accepting TCP
connections that never received a response.

There is no exported component and no receiver. The service is `exported="false"`, the notification
opens the existing editor activity, and the socket remains loopback-only. A failed bind is logged
and the app stays usable.

### 3.7 MCP protocol — `protocol.py`

`tools/gamecore-studio-mcp/gamecore_mcp/protocol.py`

- One JSON object per line.
- Implements `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`,
  `prompts/list` and `prompts/get`.
- Notifications produce no response.
- JSON-RPC errors use the standard codes: `-32700` parse, `-32600` invalid request, `-32601`
  unknown method, `-32602` invalid params, `-32603` internal.
- A tool failure is **content** with `isError: true`, not a protocol error, so the agent sees the
  bridge's explanation.
- Batches are answered in order; notifications inside a batch are omitted.
- Logs go to stderr. stdout is protocol only.

### 3.8 MCP tools — `tools.py`

`tools/gamecore-studio-mcp/gamecore_mcp/tools.py`

Each tool has a JSON Schema and an annotation. Handlers call `StudioBridge`; export and inspect
also touch the local filesystem.

| Tool | Arguments | Result |
| --- | --- | --- |
| `studio_setup_guide` | none | Steps, APK path, server command, generic MCP config, live health |
| `studio_status` | none | App package/version and draft id/name/validity |
| `studio_get_draft` | none | Full storage JSON + validation |
| `studio_set_draft` | `draft` object | The replaced draft + validation |
| `studio_validate_draft` | optional `draft` | `{valid, problems, draftId, draftName}` |
| `studio_export_plugin` | optional `draft` | Path, SHA-256, size, entries |
| `studio_list_templates` | none | Eight templates with palettes |
| `studio_apply_template` | `id` | Updated draft + validation |
| `studio_inspect_package` | `path` | Inspection + import outcome |
| `studio_capabilities` | none | Boundary, with an offline fallback |

Export is stricter than the bridge alone:

1. The bridge refuses a draft with problems (422).
2. The client base64-decodes the package.
3. It checks the decoded size and SHA-256 against the bridge's values.
4. It sanitises the file name to a basename, forces `.gcplugin`, and resolves the destination.
5. It refuses any path whose parent is not the export directory.
6. It writes a `.tmp` file and `os.replace`s it into place, so a crash cannot leave a half package.

The default export directory is
`/sdcard/Download/GameCore Project/studio-mcp-exports`; override it with
`GAMECORE_STUDIO_EXPORT_DIR`.

### 3.9 MCP bridge client — `bridge.py`

`tools/gamecore-studio-mcp/gamecore_mcp/bridge.py`

A blocking `urllib` client with proxies disabled (so a terminal proxy cannot intercept loopback),
an 8-second timeout, and typed errors:

- `bridge_unreachable` — the app is closed or the port is wrong;
- `bridge_timeout` — the app did not answer;
- an HTTP status and the bridge's JSON for a refused request.

The server is single-threaded, so the MCP request order is the bridge request order.

### 3.10 Prompts and setup — `prompts.py`, `AGENT_PROMPT.md`, `setup.sh`

`prompts.py` exposes one MCP prompt, `setup_plugin_studio`. It is written to the agent: install and
open the debug APK, register the stdio command, call `studio_status`, then author and export.
`AGENT_PROMPT.md` is the copy-paste version for a coding agent that can run shell commands.
`setup.sh` checks Python, the APK, the installed package and bridge health; it can build, open the
installer, register with OpenCode and run the tests.

The fixed token is deliberately documented. It stops an accidental client from changing a draft;
it is not authentication against a hostile app on the same debug device. Do not expose the port.

## 4. Bridge routes

| Method | Path | Auth | Meaning |
| --- | --- | --- | --- |
| `GET` | `/v1/health` | no | `{ok, protocol, bridge}`; works without the app's data |
| `GET` | `/v1/status` | yes | App/version and draft summary |
| `GET` | `/v1/draft` | yes | Storage JSON + validation |
| `PUT`, `POST` | `/v1/draft` | yes | Replace the session draft |
| `POST` | `/v1/validate` | yes | Validate the current draft or a candidate |
| `POST` | `/v1/export` | yes | Compose and return base64, hash and entries |
| `POST` | `/v1/inspect` | yes | Inspect/import base64 package bytes |
| `GET` | `/v1/templates` | yes | List templates |
| `POST` | `/v1/templates` | yes | Apply a template to the live draft |
| `GET` | `/v1/capabilities` | yes | Capability boundary JSON |

All error bodies are `{error, message}`; validation errors also carry `{valid, problems}`; a refused
export is `422 invalid_draft`.

## 5. Every studio section and what MCP reaches

The editor has six workspaces. MCP does not add a seventh; it reads and writes the same draft the
six workspaces edit.

| Section | What it is | MCP surface |
| --- | --- | --- |
| **Build** | category, identity, read-only screens, one overlay, typed settings, dashboard widgets | `studio_get_draft` / `studio_set_draft`; the `sections` object carries screens, overlay, settings and widgets. No interactive controls exist to author. |
| **Theme** | eight templates, thirteen roles, shape, type, glass, glitch, identity, permissions, data scopes | `studio_list_templates` / `studio_apply_template` for the theme; full draft tools for the rest. Validation catches a glass `intensitySetting` that does not name an int/float setting. |
| **Logic** | seven edge triggers, six variables, flat AND conditions, seven broker actions | Rules live in `sections.rules` and are validated on write. There is no MCP rule runner; the Test tab's simulation stays a visibly labelled, local UI exercise. |
| **Preview** | device widths, orientation, font scale, brightness | Not exposed. It is a visual simulator, not a package operation, and the bridge has no screen. |
| **Test** | draft validation, simulated rule runner, conflict checks, WCAG and colour-vision checks, capability observations | `studio_validate_draft` and `studio_capabilities`; package checks through `studio_inspect_package`. Simulation and colour-vision analysis are not exposed. |
| **Export** | package inspector, SHA-256, save, import, boundary | `studio_export_plugin` writes through the same composer, verifies the hash, and writes outside the app; `studio_inspect_package` runs the same importer. The app's SAF “Save” button is unchanged. |

Identity and permissions are part of the top-level draft, not a separate section tool. The composer
derives `declares` from enabled sections and the required permissions/scopes from their content;
the bridge never grants anything.

## 6. Setup

### 6.1 Same Android device (Termux)

```bash
cd /root/GameCore-4.0
./gradlew :plugin-maker:assembleDebug
cp plugin-maker/build/outputs/apk/debug/plugin-maker-debug.apk \
   /sdcard/Download/GameCore-PluginMaker-1.1.0-debug.apk
bash tools/gamecore-studio-mcp/setup.sh --install
```

Tap the installer. Open **Plugin Maker** (the debug icon, package
`com.gamecore.pluginmaker.debug`) once. It starts a foreground service and shows a persistent
**Plugin Studio bridge is running** notification; you can then switch to Termux and leave the
editor in the background. The notification opens the editor when tapped.

Then register the server with the MCP client:

```bash
opencode mcp add gamecore-studio -- \
  python3 /root/GameCore-4.0/tools/gamecore-studio-mcp/server.py
```

Ask the agent to call `studio_status`. On OpenCode, config reloads when the file changes; use
`/reload` if the tools do not appear.

### 6.2 A computer with the phone on USB

The bridge binds the phone's loopback. Forward the port from the computer:

```bash
adb forward tcp:47653 tcp:47653
```

Then run the same `server.py` on the computer. The app must be installed and open on the phone.
This is still a local stdio server on the computer; the forward is the only network hop.

### 6.3 Any other MCP client

Use the server as a stdio command:

```json
{
  "mcpServers": {
    "gamecore-studio": {
      "command": "python3",
      "args": ["/root/GameCore-4.0/tools/gamecore-studio-mcp/server.py"]
    }
  }
}
```

The server needs Python 3.10+ and no packages. Set `GAMECORE_STUDIO_URL` to a different bridge URL,
`GAMECORE_STUDIO_TOKEN` to the debug token, or `GAMECORE_STUDIO_EXPORT_DIR` to a different output
folder.

## 7. Tests

```
GameCore Studio MCP
├── Kotlin, debug variant
│   └── plugin-studio/src/testDebug/.../StudioMcpBridgeTest.kt
│       ├── health is public, data routes require the token
│       ├── status and draft round-trip through DraftCodec
│       ├── set/validate accept valid JSON and reject malformed JSON
│       ├── export is deterministic and refuses an invalid draft
│       ├── inspect exchanges real package bytes with PluginImporter
│       ├── templates list/apply preserve the draft identity
│       ├── unknown routes/methods are refused
│       ├── a real loopback socket proves framing and auth
│       └── the session notifies only on change
├── Python
│   ├── tests/test_protocol.py  initialize, tools, prompts, errors, batches, stdio loop
│   └── tests/test_tools.py     every tool against a contract fake bridge
├── Host cross-check
│   └── :app PluginStudioPackageTest / PluginStudioMultiSectionPackageTest
│       (composer output decoded by GameCore's real codecs)
└── Manual end-to-end
    └── debug app open → studio_status → set draft → validate → export → inspect SHA-256
```

Run them:

```bash
./gradlew :plugin-studio:testDebugUnitTest
( cd tools/gamecore-studio-mcp && python3 -m unittest discover -s tests -t . )
```

The Kotlin suite currently has 127 tests (15 in the bridge class); the Python suite has 22.
The manual end-to-end path needs the debug APK installed because the bridge runs in the app process;
the fake bridge deliberately does not claim to replace that test.

## 8. Security and honesty

- **Source-set isolation.** The bridge, transport, token, service and notification are in
  `src/debug`; the release manifest has no Application name, service or network permission. Verify
  a release with `unzip -p app-release.apk classes*.dex | strings | grep -c StudioMcpBridge`
  (expect 0) and `aapt2 dump permissions` (expect no `INTERNET`).
- **Foreground service, not a background daemon.** The debug service is `exported="false"` and
  shows an ongoing notification while it runs. It has no receiver, no boot hook and no way to start
  before the user opens the app. Force-stopping the app removes the service.
- **Loopback only.** The socket binds `127.0.0.1`. There is no LAN or internet listener.
- **Fixed token.** `gamecore-studio-debug-v1` is a convenience gate, not authentication. Any
  process on the debug device can read the source. Do not run this on a shared device and do not
  forward the port to an untrusted network.
- **No execution.** A package is data. The bridge composes bytes; it does not load, interpret or run
  a plugin.
- **No grant.** Permissions are requests in the manifest. GameCore grants them after install; the
  studio and the bridge cannot.
- **No fake success.** The bridge returns `422` for an invalid draft; the MCP export client checks
  both size and SHA-256 before writing; `studio_inspect_package` reports importer failures rather
  than pretending the package is installable.
- **Release unchanged.** The release APK still uses the SAF save flow, no permissions, no listener.

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `bridge_unreachable` | app closed, service stopped, wrong build, or wrong port | open the debug app; check the bridge notification; `curl http://127.0.0.1:47653/v1/health` |
| `401 unauthorized` | missing/wrong `X-GameCore-Studio-Token` | use the debug token or set `GAMECORE_STUDIO_TOKEN` |
| tools missing in OpenCode | config not loaded | `/reload`, or `opencode mcp list` |
| `invalid_draft` on export | validation problems | call `studio_validate_draft` and fix each field |
| package not where expected | custom export dir | check `GAMECORE_STUDIO_EXPORT_DIR` or the tool's `path` |
| port already in use | another bridge/process | close the other app; the bridge logs and the editor still works |
| notification gone / requests hang | OEM stopped the foreground service | reopen the debug app; its service starts again |

## 10. Deliberate limits

- The MCP server is a **development tool**, not a shipped user feature. The release app has no
  bridge and no way to enable one.
- It exposes no UI automation, screenshots, installs, permission grants, rule execution or
  simulation. Those are separate authorities and separate risks.
- It edits one process-lifetime draft. Two agents writing at once follow last-write-wins; there is
  no merge protocol.
- It does not make a package trusted. Structural validity and SHA-256 integrity are not a signature
  by GameCore's release key; the host remains the authority on install and grants.
