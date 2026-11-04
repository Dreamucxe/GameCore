# GameCore 4.6 feature map and user guide

This is the user-facing map of GameCore 4.6. It explains what each part of the app does, how the parts connect, what access each feature needs, and what GameCore deliberately cannot do.

> **The rule used throughout the app:** if GameCore cannot measure or confirm something, it says **Unavailable**, **Requires Shizuku**, **Not supported**, or gives the failure reason. It does not replace a missing reading with zero, estimate a value, or call an accepted request a successful change without reading it back.

## 1. Start here: a practical first setup

You can use GameCore as a read-only device monitor without granting every optional access. Grant only what matches the features you intend to use.

1. **Open GameCore and complete or skip the setup wizard.** Every wizard step is optional.
2. **Choose the features you want.** This controls which access requests the wizard shows; choosing a feature does not grant anything by itself.
3. **Create a first game profile.** Pick the installed app, then leave every setting you do not want GameCore to change on **Leave alone**.
4. **For automatic profile detection**, grant **Usage access** and leave **Apply profiles automatically** on.
5. **For overlays**, grant **Display over other apps**, then configure the floating button, stats pill, crosshair, or a HUD layout.
6. **Set up Shizuku only if needed.** It unlocks selected device-level controls, but GameCore works without it.
7. Open **Settings → Setup health** at any time to see what is ready, what is missing, and which feature each missing item affects.

### The seven setup-wizard steps

| Step | What it does |
| --- | --- |
| **Welcome** | Chooses a language and explains local storage, privacy, and the optional latency connection. |
| **Features** | Offers automatic profiles, overlays, Do Not Disturb, refresh control, Aim Lab, thermal downshift, network checks, and full performance under battery saver. |
| **Permissions** | Opens only the Android grants needed by the selected features, then rechecks the real system state when you return. |
| **Shizuku** | Explains the optional elevated route, whether it is connected, and how many controls are usable on this device. |
| **First game** | Creates one profile and optionally fills it from a preset. Every value remains editable. |
| **Automatic adjustments** | Offers thermal downshift, a network check, and full-performance behavior. All start off. |
| **Done** | Lists what is ready and what was skipped. A skipped optional is not treated as an error. |

## 2. Navigation map

The bottom bar has five destinations when Aim Lab is enabled and four when it is disabled:

```text
GameCore
├── Home
│   ├── Global search
│   ├── Featured game profile → Profile editor
│   ├── Live CPU / RAM / battery / temperature / refresh readings
│   ├── Device
│   │   ├── Codec & GPU scanner
│   │   ├── Device check
│   │   └── Sensor calibration
│   ├── Status → Setup health / Permissions / Shizuku / Games
│   ├── Overlays → Overlay setup / HUD / Crosshair
│   └── Tools
│       ├── Aim Lab
│       ├── HUD builder
│       ├── Controller lab
│       ├── Touch heatmap
│       ├── Gyro & aim
│       ├── Screen Extraction
│       ├── Touch sampling monitor
│       └── Gaming tools
├── Games
│   ├── Detection diagnostics, exclusions, and pinned profile
│   ├── Experimental emulator hand-off
│   ├── Add / import / export / edit profiles
│   └── Profile editor
│       ├── Display, sound, overlays, and performance
│       ├── Volume-button point trigger
│       ├── CPU affinity and smart features
│       ├── Session recording, energy estimate, and Instant Replay
│       └── Config-file browser and editor
├── Aim Lab (can be disabled in Settings)
│   ├── Training: Flick / Tracking / Reaction / Gyro or Tilt / Recoil / Movement / Free practice
│   ├── Setup: Sensitivity lab / Weapon editor / Control & HUD editor
│   └── Progress: Statistics / Personal records / History → Results
├── Sessions
│   ├── Live recording
│   ├── Combined game and Aim Lab history
│   └── Game report → graphs / timeline / card / receipt
└── Settings
    ├── Appearance / app icon / text / accessibility / behavior
    ├── Overlays / games / detection / monitoring / network / recording
    ├── Access and tools
    │   ├── Setup health / Permissions / Shizuku
    │   ├── Plugins → Plugin detail → Plugin screens / automation history
    │   ├── Dashboard composer / appearance schedule / Theme Engine
    │   ├── Graphics / charge bypass / network stability
    │   ├── Screen Extraction / touch sampling / gaming tools
    │   └── Display recovery
    ├── Data → retention / CSV export / backup & restore / reset
    └── About → What's new / developer / license
```

The global search on Home can find screens, settings, and features without remembering which card contains them.

Some editors are intentionally reached from the feature they configure rather than duplicated in Settings:
colour correction opens from a profile, the in-game panel, or Search; media access opens from the panel;
quick-launch apps and macros open from Overlay; and a game’s config browser opens from that game’s profile.

## 3. Three ideas that make the rest easier to understand

### 3.1 A reading is a value or a reason

CPU, memory, battery, thermal, GPU, display, touch, network, storage, and frame data come from different Android and device-specific sources. Not every phone exposes every source. A tile may therefore show:

- a measured value and its source;
- **Measuring** while enough samples are collected;
- **Requires permission** or **Requires Shizuku**;
- **Not available on this device**; or
- a specific read failure.

An unavailable FPS signal, for example, is not replaced with an estimate based on refresh rate.

### 3.2 “Leave alone” is not a default

Most profile fields are optional. **Leave alone** means GameCore does not write that setting at all. If brightness is the only field set, GameCore reads the old brightness, changes it for the game, and restores exactly the value it read when the game ends. It does not reset unrelated settings or restore an assumed factory default.

### 3.3 Apply and restore are one operation

When a profile changes device state, GameCore records a restore entry first. If the app or session is interrupted, Home shows **Settings still changed** with two explicit choices:

- **Put them back** — attempt the recorded restores; or
- **Leave as they are** — accept the current device state and clear the outstanding entries.

Display-size changes receive extra protection: a confirmation countdown, automatic rollback when not confirmed, launch-time repair, and a dedicated **Display recovery** screen.

## 4. Home: the live dashboard

Home is both a status screen and the fastest route into the rest of the app.

### Search

Tap **Search screens, settings and features** to search the app-wide feature index. Results open the existing destination; search does not create a second copy of a setting.

### Featured game profile

The hero card shows one saved profile, its enabled state, installed state, and short chips describing its effects. You can:

- start the game;
- apply the profile immediately;
- edit the profile;
- switch between saved profiles with the pager; or
- open the complete Games list.

If recorded sessions support a useful profile suggestion for a game that has none, Home offers a reviewable draft and lists the measured reasons behind the suggestion. Dismissing it does not create a profile.

### Live readings

The built-in dashboard contains:

- CPU usage;
- RAM use;
- battery state;
- primary temperature;
- current refresh rate;
- display details; and
- free storage.

RAM and temperature include short-lived trends made only from samples collected while Home is visible. A real platform thermal-throttling state appears as a warning banner.

**Settings → Appearance → Show GameCore stats on Home** can hide the five built-in live tiles. Plugin dashboard widgets remain, as do the Device, Status, and action cards.

**Settings → Appearance → 3D Home stat designs** switches those five tiles between the original flat icons and five distinct host-drawn instruments. CPU is a pinned processor die, RAM is a pair of memory modules, battery is a filled battery pack, temperature is a glass thermostat, and refresh rate is a display with a circular sweep. The shape changes with the metric; it is not one badge recoloured five times.

### Device, Status, Overlays, and Tools

- **Device** opens the codec/GPU scanner, a device fitness check, and sensor calibration.
- **Status** summarizes Shizuku, overlay access, usable controls, and profile count.
- **Overlays** reports what the service is actually drawing and can hide every overlay window.
- **Tools** links to Aim Lab, the HUD builder, controller input, touch heatmap, motion readings, extraction, touch sampling, and the full gaming-tools screen.

### During a tracked game

Home changes to show the active game, session duration, profile result, and each restorable setting the profile changed. Individual changes or the complete set can be reverted before the session ends.

## 5. Games and per-game profiles

### Games screen

The Games tab contains:

- the real detection state: **Watching** or **Not watching**;
- the most recent foreground-app decision and its reason;
- the most recent real profile application and result;
- the global automatic-apply switch;
- a pinned profile used only for ordering and a badge;
- an exclusion list for packages that must never trigger detection;
- Add, Import, and per-profile Export actions;
- manual **Apply now**, **Play**, enable/disable, edit, and delete actions; and
- **Restore** for settings applied manually but not yet put back.

The experimental **Emulators** screen stores ROM references and hands a selected file to an emulator already installed on the device. GameCore does not ship an emulator.

### Building a profile

Open **Games → Add**, choose an app, and edit these sections:

| Profile section | Available choices and behavior |
| --- | --- |
| **Game** | Enable or disable the profile without deleting it; start the installed game. |
| **Display** | Supported refresh rates, aspect/display-size stretch, native-resolution percentages, brightness, orientation, timeout, and a saved colour-correction preset. |
| **Sound** | Media volume and Do Not Disturb. Ringer and alarm volume are not changed. |
| **Overlays** | Floating button, stats pill, crosshair preset, and a named HUD layout. |
| **Volume trigger** | Map volume up/down to a saved screen point, tap or press-and-hold. Requires the global feature switch, accessibility service, and Shizuku. |
| **Performance** | Balanced, Performance, Battery saver, or Custom; optional Shizuku path; optional free-RAM pass. |
| **CPU cores** | Experimental affinity presets derived from the real core layout. A missing core layout produces no guessed presets. |
| **Smart features** | Thermal refresh downshift, pre-launch and in-session network warnings, full performance under battery saver, and charge bypass. |
| **Session** | Record measured session history for this game. |
| **Energy** | Estimate battery cost and remaining play time from completed sessions; it stays unavailable until enough real history exists. |
| **Instant Replay** | Keep a 15, 30, 60, or 120 second rolling video window. It is currently video-only and writes a clip only when asked. |
| **Game files** | Browse and edit text files inside the game’s shared `Android/data/<package>/files` area, with an untouched original and explicit diff. Requires Shizuku. |

### Display size versus resolution

These controls are intentionally separate:

- **Display size / aspect stretch** changes the logical shape reported to the game and stretches the result to the panel. It is not a wider field of view.
- **Resolution percentage** keeps the panel’s shape and lowers the logical pixel dimensions. A game with its own internal renderer may ignore that lower size, so this is not a guaranteed FPS gain.

Both use the same system-level display override and are mutually exclusive. They can survive a reboot, which is why GameCore records, confirms, and recovers them more aggressively than ordinary settings.

### Performance modes

| Mode | What it actually means |
| --- | --- |
| **Balanced** | Leaves the scheduler and thermal governor in charge. |
| **Performance** | Requests the highest supported refresh rate, disables battery saver, and disables window animations. It does not raise CPU or GPU clocks. |
| **Battery saver** | Requests the lowest supported refresh rate and enables Android battery saver. |
| **Custom** | Applies only the fields selected elsewhere in the profile. |

### Free RAM on launch

This is off by default and is the one profile action aimed at other apps. GameCore excludes itself, the game, launcher, keyboard, system packages, apps with foreground services, and everything in **Settings → Never close these apps**.

With Shizuku, GameCore can enumerate, request closure, and check what remains. Without it, Android’s ordinary API does not report what it actually closed, so GameCore says **asked Android to close** instead of inventing a count.

### Smart features

- **Auto-cool when hot** lowers refresh rate only after the measured temperature/status remains hot, observes the configured floor, and raises it again after cooling. It reports a refresh change; it does not claim to cool the hardware directly.
- **Network check** uses the configured reference host, not the game server. A pre-launch warning never blocks launch, and failed handshakes are not labelled packet loss.
- **Keep full performance** temporarily disables Android battery saver. It cannot bypass hardware limits or thermal throttling.
- **Charge bypass** works only on supported, plugged-in devices with a writable charge-control node and Shizuku. It reduces battery heat/wear; it adds no speed.

## 6. Overlay system

All windows in this section require **Display over other apps**. The UI reports the window state returned by the overlay service, not merely the requested switch value. While a running profile owns the overlay, manual controls are locked and name the game that is in charge.

### Floating button

The draggable button can:

- snap to a corner or nearest edge;
- remember separate portrait and landscape positions;
- change size, normal opacity, and faded opacity;
- vibrate on tap;
- show a thermal warning dot; and
- open either the quick sheet or full panel.

### Quick sheet and full control panel

The quick sheet keeps the game visible and holds up to six pinned toggles, brightness, media volume, the game icon, session clock, and user-built macros. It can close automatically, open the full panel on one tap, or use double-tap for the full panel.

The full panel organizes controls into display, overlays, capture, and session areas. Depending on device support and granted access, it can expose:

- brightness and media volume;
- colour saturation, contrast, hue, and saved presets;
- display aspect and refresh rate;
- rotation lock, Do Not Disturb, and torch;
- screenshot and screen recording;
- media controls and quick-launch apps;
- overlay toggles and shortcuts back into GameCore; and
- hold-to-confirm session ending.

The panel has four shapes. **Turbo wings** is the default for fresh installs: two angled plates fold in from the screen edges, the game stays visible between them, and each wing carries a configurable 3D gauge. The left wing holds the header, readouts, levels and media; the right holds the action grid, quick apps and any open chip row. **Turbo deck** is one tall GAMETURBO plate anchored to the left edge, with the game visible on the right; its quick tiles, switches, primary action, action grid, levels and media all live on the plate, which scrolls on its own. **Centered** is the original one-plate panel near the floating button, with a stored width and a resize grip. **Split edges** is two rectangular plates pinned to the sides. All four remain available, and the centered width is kept while another layout is on.

Change the shape in **Settings › Overlay**, from the panel's own **Layout** tile, or in-game: long-press the floating button, or tap **Layout** in the quick sheet, to open the picker. The picker, the panel tile and the settings card all write the same preference, so the next open (and any open panel) follows the choice immediately. Tap the centre gap or the close button to leave the wings or split layout; tap the strip of game or the close button to leave the deck.

Each Turbo wings gauge can measure CPU, GPU, RAM or temperature, chosen separately for the left and right side in **Settings › Overlay**. Tapping the caption under a gauge cycles the same choices in-game. The four choices have different 3D rings — an amber circuit ring, a cyan facet ring, a green memory-stack ring and a heat ring — so the choice changes the object, not only the number. A reading the device cannot supply shows *n/a* and the reason; it is never drawn as a zero. The gauges are sampled for the panel independently of the stats pill, so choosing one does not add it to the pill.

The panel’s quick-app list and macros have their own editors. A macro replays a bounded sequence of panel actions; it is not a shell script or arbitrary automation language.

### Stats pill

Configure layout, drawing position, labels, text size, opacity, corner radius, stats and their order, and update interval. Sampling starts only while a visible consumer needs it and stops when the screen is off or no consumer is observing.

### Floating dock

The dock is a second draggable, edge-snapping surface. It has independent size, opacity, and panel width. When dock customisation is enabled, **Settings → Dock controls** chooses and reorders its toggles and quick actions. Screen-opening actions route to the existing GameCore screen rather than duplicating behavior.

### Crosshair, HUD, Scout, Hunt, and Wheels

- **Crosshair** offers eleven vector styles plus an imported PNG, with size, thickness, gap, rotation, opacity, color, position, and saved presets.
- **HUD builder** creates named layouts from live stats. Drag items in a preview, change the stat, text size, opacity, color, label, and background plate, then select the layout in a profile.
- **Scout** magnifies the center of the shared screen feed. It does not read another player’s state.
- **Hunt** draws a whole-screen Movie, Instrument, Film, or Sketch color grade.
- **Wheels** is a visual sensitivity guide and affects sensitivity only inside GameCore’s own Aim Lab. It cannot change another game’s input.

## 7. Monitoring and diagnostics

### Live Performance

**Settings → Live performance** expands the Home readings into:

- CPU total and per-core readings/frequencies, with a segmented bar graph for every exposed core;
- memory use;
- battery level, health, temperature, voltage, source, and current where reported;
- thermal sensors and platform throttling state;
- GPU load where the vendor exposes a readable node;
- display mode and refresh rate;
- network transport, latency, throughput, and stability burst;
- real frame timing where a supported source exists; and
- graphs with gaps for missing readings rather than false zeroes.

Device actions on this screen are offered only when they need no missing target value. Brightness, volume, display size, colour presets, and CPU affinity remain in the profile/tools screens because Performance would otherwise have to invent a value or a game to act on.

### Additional diagnostics

| Screen | Purpose |
| --- | --- |
| **Codec & GPU scanner** | Reports Android’s exposed GPU and video-codec capabilities. |
| **Device check** | Shows feature readiness and routes to Android settings for fixable gaps. |
| **Sensor calibration** | Captures gyro/accelerometer bias, noise, and drift from a still device. |
| **Gyro & aim** | Live gyroscope/accelerometer and aim-motion summary. |
| **Touch heatmap** | Shows only touches delivered to GameCore; it cannot observe touches in another app. |
| **Touch sampling monitor** | Reports the arrival rate of touches GameCore can legitimately receive, not the panel manufacturer’s claimed hardware rate. |
| **Controller lab** | Shows connected controllers, axes, buttons, and current input. |
| **Network stability** | Combines transport, Wi-Fi band/signal/link estimate, latency, jitter, and failed probes. It cannot pin routes or throttle other apps. |
| **Screen Extraction** | Uses a user-approved screen feed to lift local text and shapes from a frame. It is disabled entirely when its feature switch is off. |

## 8. Sessions and reports

The Sessions tab merges game sessions and Aim Lab runs into one history. It provides All/Games/Aim Lab filters, optional per-game filters, sorting, an all-time summary, the current live recording, and explicit deletion actions.

### What a tracked game session stores

Where readings exist, a session can store duration, battery delta and drain rate, CPU/RAM/temperature averages and peaks, refresh rate, frame timing, network handshakes and stability, active colour preset, profile application results, restore events, and sample count. A process-killed session is repaired on the next launch and marked as interrupted rather than silently discarded.

### Session report

The report includes:

- conditions captured at start;
- measured aggregates with sample counts;
- live graphs;
- connection verdicts such as Stable, Spiky, Unreliable, No reply, or Not measured;
- frame-time spike attribution only where evidence supports it;
- a timeline of profile, thermal, refresh, power, network, and frame events;
- a shareable 1080×1260 summary card; and
- a detailed PNG receipt.

Cards and receipts are rendered on the device and shared through a one-file `content://` grant. They are not uploaded by GameCore.

## 9. Aim Lab

Aim Lab is a built-in, optional training section. Turning it off in Settings removes its tab and routes but keeps recorded data.

### Training

| Mode | What it trains |
| --- | --- |
| **Flick** | Snap to appearing and disappearing targets. |
| **Tracking** | Keep aim on a moving target. |
| **Reaction** | Respond as soon as a target appears; records response time. |
| **Gyro / Tilt** | Aim by moving the device; tilt is used when a gyroscope is unavailable. |
| **Recoil** | Counter a fictional weapon’s kick pattern. |
| **Movement** | Aim while strafing. |
| **Free practice** | Unscored sandbox; runs with shots can still enter history. |

### Setup and progress

- **Sensitivity lab** edits camera and gyro sensitivity, curves, and deadzone.
- **Weapon editor** builds fictional training weapons with fire rate, recoil, and spread.
- **Control / HUD editor** moves the on-screen stick, aim, and shoot controls.
- **Statistics** shows trends across stored runs.
- **Personal records** shows the best result per mode and difficulty.
- **Session history** opens a full result report for each run.

The Aim Lab home shows real totals and bests only after a run exists. A daily recommendation names the mode, difficulty, and measured reason behind it.

## 10. Plugins

A `.gcplugin` is a bounded data archive interpreted by GameCore. It is not an APK and does not run its own code.

### Installing

1. Open **Settings → Plugins**.
2. Tap **Choose files**. One or several files can be selected.
3. Read the preview: name, version, size, compatibility, requested permissions, and replacement status.
4. Confirm installation. A new plugin is **off** and has **no granted permissions**.
5. Open its detail page, grant only the permissions/scopes wanted, save them, and enable it.

### Manager safety controls

- **Turn all plugins off** denies every plugin action while preserving individual choices.
- **Use GameCore’s own theme** always restores the built-in appearance.
- Search covers name, id, developer, description, and version.
- Sort by name, install/update date, or version.
- Suspended plugins can be retried; quarantined plugins remain listed but do not load.
- Rollback restores a retained previous package disabled with permissions cleared.
- Removing a sideloaded plugin also removes its grants, settings, and namespace data. Bundled plugins can be disabled but not deleted from the APK.

### Detail and consent

The detail screen shows the exact package digest, origin, sections, compatibility, run state, and every requested permission grouped by risk. Scoped data such as device stats, thermals, games, sessions, motion, or automation history is opened one scope at a time.

High-risk device requests need both the named permission and **Advanced access**. Even then, GameCore’s broker performs only fixed, reviewed, range-limited actions; the plugin never receives Shizuku, a shell, Binder access, or a command string.

### What plugins may add

- a schema-1–4 theme, typography, glass, or glitch request;
- read-only GameCore-rendered screens;
- one bounded, non-touchable monitoring overlay;
- typed settings stored in the plugin namespace;
- read-only Home dashboard widgets; and
- edge-triggered rules using GameCore’s closed action list.

They cannot run scripts, native code, a WebView, arbitrary network access, arbitrary Android intents, shell commands, interactive screen controls, or plugin-to-plugin messaging. See [Plugin Studio feature guide](PLUGIN_STUDIO_FEATURE_GUIDE.md) for creation instructions.

## 11. Appearance and interface customization

### Core appearance

Settings includes language selection, Follow system/Dark/Light/AMOLED themes, eight accents plus a custom color, Android 12+ dynamic color, compact density, built-in Home-stat visibility, a 3D/original Home-stat design switch, Redline skin, a background image and readability scrim, app icon choice, in-app font scale, animation policy, and haptics.

Accessibility settings include Aim Lab target palettes, a left-handed Aim Lab layout, and larger touch targets. These do not silently change text scale or unrelated spacing.

### Theme Engine, schedule, and dashboard

- **Theme Engine** maps measured CPU, memory, and thermal conditions to a live backdrop, with threshold calibration, manual overrides, and local save points.
- **Theme & dashboard schedule** applies time windows and per-game overrides above manual choices. When no schedule applies, the previous manual choices return.
- **Dashboard composer** arranges built-in panels and granted plugin widgets into a saved Home layout.
- **Glass Control Center** appears only while a schema-4 glass theme is active and moves its material intensity with a real preview.

### Colour correction

The colour screen offers red/green/blue gain, combined or separate gamma, saturation, contrast, hue, and brightness offset, plus editable presets. Android exposes no universal app-level color matrix, so GameCore maps requests only onto supported system sinks and lists values the device cannot represent. The feature normally needs `WRITE_SECURE_SETTINGS` through Shizuku or ADB.

## 12. Gaming tools and files

### Gaming tools

The full Tools screen provides screenshot and video recording through Android MediaProjection consent, quality selection, saved capture share/delete, brightness, automatic brightness, orientation lock, media volume and transport buttons, Do Not Disturb, and flashlight where supported.

Recordings are currently video-only. Files are kept in GameCore’s private storage until shared; uninstalling the app deletes them.

### Game storage

For each game, GameCore separates:

- **Cache** — Android’s reported package cache;
- **Clearable** — only the shared-storage cache directory GameCore can safely reach; and
- **Untouched** — saves, logins, settings, private cache, and downloaded assets.

Measuring package sizes needs usage access. Deleting the shared cache needs Shizuku. GameCore never uses `pm clear` and never presents the entire data directory as safely clearable.

### Config-file editor

The config browser is restricted to the selected game’s shared `Android/data/<package>/files` tree. Before the first edit it explains the risk. A save keeps an untouched original, shows a line-by-line diff, stages the replacement, commits the complete file atomically, and offers restore checkpoints.

It cannot reach a game’s private sandbox, executable code, or another package’s path.

## 13. Settings map

| Settings card | Main controls |
| --- | --- |
| **Appearance** | Language, theme, accent, dynamic color, density, Home stats, Redline skin, background image. |
| **App icon** | Select a bundled launcher icon. |
| **Display & text** | In-app font scale and text-size presets. |
| **Accessibility** | Aim Lab target palette, handedness, larger touch targets. |
| **Behavior** | Animation policy and haptics. |
| **Overlays** | Overlay, dock, crosshair, Scout, Hunt, Wheels, HUD, and feature gates. |
| **Games** | Automatic apply, keep-screen-awake, safety notices, detection interval, never-close list, storage, quick trigger, Aim Lab. |
| **Detection** | Current exclusion count and pinned profile; editing lives on Games. |
| **Monitoring** | Session recording, sample interval, background monitoring, thermal warnings, elevated reads, extraction/touch/trigger gates, performance, recovery. |
| **Network** | Latency measurement and reference host; network-stability gate. |
| **Recording** | Capture quality and route to Tools. |
| **Access and tools** | Setup, permissions, Shizuku, plugins, dashboard, schedule, Theme Engine, graphics, charge bypass, network stability, tools. |
| **Data** | Counts, retention, backup/restore, session CSV, clear history, reset settings. |
| **About** | Installed version, Android/device, privacy, license, changelog, developer links. |

## 14. Access and permission map

| Access | Enables | Without it |
| --- | --- | --- |
| **Display over other apps** | Floating button, pill, crosshair, HUD, plugin overlay | Overlay windows stay off; profiles and history still work. |
| **Usage access** | Automatic game detection and package cache measurement | Profiles can be applied manually; cache totals may be unavailable. |
| **Modify system settings** | Brightness, orientation lock, timeout | Those steps are skipped and reported. |
| **Do Not Disturb access** | Session DND switch | DND controls remain unavailable. |
| **Notifications** | Foreground services and thermal/network notices | Background overlay/tracking/monitoring may not run on modern Android. |
| **Unrestricted battery use** | Better survival during long sessions | Android may throttle sampling or stop services; interrupted data is labelled. |
| **See installed apps** | Complete game picker | The picker may be incomplete on Android 11+. |
| **Notification access** | Panel media identity and transport | The media strip shows an Enable prompt; other controls still work. |
| **MediaProjection consent** | Capture, extraction, Scout/Hunt feed | Android asks when capture starts; GameCore cannot pre-approve it. |
| **Accessibility service** | Detect volume-trigger key presses | Point triggers cannot fire; ordinary volume behavior remains. |
| **Shizuku / ADB-level access** | Selected display/system controls, safe cache deletion, config editing, CPU affinity, charge bypass, verified app closure, extra reads | Each dependent action is skipped or unavailable with a reason. |

Shizuku is optional and does not give plugins a shell. GameCore itself exposes only a closed, validated set of commands and arguments.

## 15. Backup, export, privacy, and safety

### Backup and restore

The version-2 backup can include settings, appearance, overlay/button/dock configuration, profiles, HUD layouts, crosshairs, colour presets, macros, completed game sessions, Aim Lab sessions, personal records, and an optional source-device snapshot. It does **not** include the APK or installed plugin packages.

Restore previews counts and device differences before writing. Merge is the non-destructive default; Replace resolves configuration conflicts in favor of the file but does not delete unrelated local history. History is additive and duplicate-aware in either mode.

### Other exports

- Settings can export game session history as CSV. Missing cells remain empty.
- Games can export/import individual profiles and resolve collisions.
- Session cards/receipts and captures go through the Android share sheet.
- Plugin Studio saves through the system file picker.

### Privacy and hard limits

- No account, GameCore backend, ads, analytics, or crash-report upload.
- The optional latency check opens a payload-free TCP connection to the configured reference host. It can be disabled.
- Session and preference stores are encrypted; keys live in Android Keystore-backed storage.
- No root requirement, process injection, game-memory reading, anti-cheat interference, thermal-limit override, generic “clean everything,” or claim of game-server ping/packet loss.

## 16. Common problems

| Symptom | What to check |
| --- | --- |
| A profile never applies automatically | Games → Detection & apply; Usage access; automatic apply; exclusions; profile enabled state. |
| A requested setting is skipped | Read the result beside that field; it may need special access, Shizuku, or device support. |
| An overlay does not appear | Overlay access, notification permission, profile ownership, and service status shown on Overlay/HUD. |
| FPS is unavailable | The device may expose no trustworthy frame-timing source. Refresh rate is not substituted for FPS. |
| A display looks stretched after interruption | Home → Settings still changed, or Settings → Display recovery. |
| Media controls show Enable | Grant Notification access from Media access. |
| A session has gaps | Check notification/battery restrictions and sample interval. Gaps are retained rather than changed to zero. |
| Cache cannot be cleared | Usage access measures; Shizuku deletes only the shared cache. |
| A plugin is enabled but not in force | Save pending grants; review compatibility, quarantine/suspension, global kill switch, and Advanced access. |

## 17. Related guides

- [Plugin Studio feature guide](PLUGIN_STUDIO_FEATURE_GUIDE.md)
- [Plugin format](PLUGIN_FORMAT.md)
- [Plugin SDK](PLUGIN_SDK.md)
- [Plugin security model](PLUGIN_THREAT_MODEL.md)
- [Plugin Studio capability boundary](PLUGIN_STUDIO_CAPABILITIES.md)
- [Release and build instructions](RELEASING.md)
