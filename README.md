# GameCore

An Android gaming overlay, performance monitor and per-game profile manager — built on the
rule that every number it shows is one Android actually reported.

[![Download APK](https://img.shields.io/badge/Download-GameCore%20v1.3%20APK-2962FF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Dreamucxe/GameCore/releases/latest/download/GameCore.apk)

Android 8.0 (API 26) or newer · signed release build · sideload, no store listing · works
fully offline

---

## New in 1.3

Three additions, and four reported bugs fixed.

- **Game storage.** A screen that measures what every game is holding in cache, and clears one
  game's shared-storage cache on a tap. It reads the size, deletes, waits, reads again, and reports
  **the difference between the two readings** rather than an exit code — so a clear that freed
  nothing says nothing was freed, instead of claiming a number it did not measure. It never touches
  a save, a login or a downloaded asset. [Details](#game-storage).
- **What the connection did.** Every session now carries a latency log: probes that completed,
  probes that did not, the worst reply, jitter, the longest run of failures, and one of five
  verdicts with the figures behind it in a sentence. No packet-loss percentage anywhere, because a
  refused TCP handshake is not a dropped packet. [Details](#sessions).
- **A card you can share.** A finished session renders to a 1080×1260 image and goes to your own
  share sheet. A reading this device could not take is a dash on that card and never a zero, an
  interrupted recording says its length is a lower bound, and the sample count travels with the
  averages. Drawn on the device; nothing is uploaded. [Details](#sessions).

The bugs, all four found in use rather than in a test: a crosshair that drew the dot preset whatever
you picked — and the HUD, which had the identical bug, because a layout you arranged is not "any
layout"; a profile edited after leaving a game that did nothing until GameCore was restarted; the
resolution card stuck on "loading" after quitting a game; and a Do Not Disturb popup that put DND
back on after you had turned it off by hand. The last one is now impossible by construction:
GameCore restores a device setting only when it made the write and nothing outside GameCore has
changed it since.

---

## Colour correction

A display colour screen, with the same values reachable from inside a game.

- **Eleven values.** Red, green and blue gain; gamma as one slider or three; saturation,
  contrast, hue rotation and a brightness offset. Every slider shows its number, and tapping
  the number opens a keypad for an exact entry — held to the field's own range, so a hue turns
  ±180° while a gain stops at ±100%, and an out-of-range entry is refused rather than quietly
  clamped to something you did not type.
- **Presets you own.** Seven ship — Vibrant, Warm, Cool, Night, Protanopia, Deuteranopia,
  Tritanopia — and they are ordinary saved rows, not a fixed menu: open one, move a slider,
  save it under a new name, rename it, delete it. There is no cap on how many you keep.
- **Per game.** A profile can carry a colour preset. It is applied when the game comes to the
  front and put back when it leaves, from the reading taken before the change — the same
  restore rule every other profile field follows.
- **In the overlay panel.** Saturation, contrast and hue sit directly under the existing volume
  and brightness sliders, a Colour tile joins the action grid, and a long press on it drops your
  saved presets in as chips. Everything applies as the slider moves, with no confirm step.
- **In the session report.** Which preset, and which values, were active while the session ran.

And the reason the feature is built the way it is: **Android exposes no per-channel colour
matrix to an app.** `ColorDisplayManager`'s matrix and `SurfaceControl.setDisplayColorTransform`
are hidden platform API that neither `WRITE_SECURE_SETTINGS` nor a shell running as uid 2000 can
reach. So GameCore projects what you asked for onto the sinks that do exist on the device it is
running on — night-display warmth, the display colour mode, the daltonizer,
reduce-bright-colours, colour inversion — and the screen lists every write it would make, names
each value this device has no sink for, and says why. A gamma slider that moved and quietly did
nothing would have been easier to build, and would have been exactly the lie this project exists
to avoid.

Colour correction needs `WRITE_SECURE_SETTINGS`, which Android never grants an app on its own —
Shizuku or a wireless-ADB pairing is the only way to hold it. Without it the screen says so and
points at the setup, rather than presenting sliders that write nothing.

---

## What makes it different

Most "game booster" apps are theatre. They show a RAM figure, animate a progress bar, kill
some processes Android immediately restarts, and report success. GameCore does have a
switch that closes background apps when a game starts — off by default, per game, and it
tells you what it measured afterwards rather than what it hoped for, including when the
measurement failed or came out negative. The difference is not the capability. It is that
the number is a reading and not a flourish. Its central type is

```kotlin
sealed interface Observed<out T> {
    data class Value<T>(val value: T, val source: DataSource, val precision: Precision)
    data class Restricted(val reason: RestrictionReason, val unlockedBy: AccessLevel?, ...)
    data class Failed(val detail: String, val cause: String?)
}
```

A reading is either a value that names where it came from and how exact it is, or an
absence that names its reason. There is no default, no fallback constant and no estimate
dressed up as a measurement. The consequence is visible in the app: some cards say
*"Not available on this device"* or *"Requires Shizuku"* instead of showing a number. That
is the feature, not a gap.

### Things this app deliberately does not do

No root. No modifying game files, no code injection, no reading or writing another
process's memory, no anti-cheat interference. No fabricated statistics. No claiming a
system call worked when it did not. No background service that closes apps on a timer, and
nothing closed without a profile asking for it — never your launcher, your keyboard, the
game itself, anything holding a foreground service, or anything on your never-close list.
No thermal limit override. No modification of protected system files. No "clean everything"
button — the storage screen deletes one named directory belonging to one game you tapped,
and never a save, a login or a downloaded asset. No packet-loss percentage, because a
refused TCP handshake is not a dropped packet. And nothing is uploaded: the shareable
session card is drawn on the device and handed to your own share sheet.

The full set of shell commands the app can construct lives in one file
(`core/shizuku/ShellCommand.kt`) and a unit test enumerates every one of them and asserts
that `cmd`, `su`, `sh`, `setprop`, `force-stop`, `pm clear`, `pm trim-caches`, `install` and
friends are unreachable at any privilege level. Two of the programs it can invoke reach
something outside GameCore, and each is reachable in exactly one form, pinned literally by
the test so that a second use cannot be added without it failing: `am kill --user current
<package>`, the memory reclaim's command, and `rm -rf
/storage/emulated/<user>/Android/data/<package>/cache`, the cache clear's. Both validate the
package name against the platform's grammar before a command exists, and both reach `exec`
as an argument vector, so there is no shell in the chain to expand a glob or split a word.

---

## Features

### Floating overlay

- A draggable gaming button that snaps to whichever edge its centre is nearer, stays on
  screen when the device rotates, and remembers where you left it.
- Tapping it opens a control panel **directly below the button**, wherever the button
  happens to be — brightness and media-volume sliders, saturation, contrast and hue,
  screenshot, screen recording, Do Not Disturb, orientation lock, flashlight, colour presets,
  display shape, and a shortcut back into the game.
- A configurable performance pill: pick which stats it shows and set its position, size,
  opacity, corner radius, text size and update interval.
- A crosshair overlay with ten designs — cross, dot, ring, ring-and-dot, cross-in-ring, T,
  X, chevron, corner brackets, or a PNG you import — and independent control of size,
  thickness, centre gap, rotation, opacity, colour and screen position. The drawn designs
  use Compose primitives, so they stay crisp at any size and ship no bitmaps.
- A visual HUD builder: drag widgets onto a live preview, choose from 16 stats, set each
  widget's text size, opacity, colour, label and background, and save layouts that a game
  profile can raise by name.

### Game profiles

One profile per package, holding target refresh rate, brightness, display size, orientation
lock, screen timeout, media volume, Do Not Disturb, which overlays to raise, a HUD layout, a
crosshair preset, a colour preset, a performance mode, whether to free memory on launch, and
whether to track the session.

Every adjustable field is nullable, and **null means leave it alone** — not "use a
default". A profile that sets only brightness records what brightness was, changes it, and
puts exactly that back when the game exits. Nothing else is touched, so nothing else can be
restored to a value the device never had.

Automatic detection applies a profile when its game comes to the foreground and unwinds it
when the game leaves. You can also launch a game straight from its profile screen.

**Free RAM on launch** is the one setting whose effect lands on your other apps, so it is off
by default, per game, and it runs last — after the overlay is up and the profile is applied,
because those are the things you are waiting for. GameCore never closes itself, the game, your
launcher, your keyboard, anything holding a foreground service (music, a recording, a
download, a navigation route), any part of the system, or anything on your never-close list in
Settings. With Shizuku it enumerates what is actually running, closes through the elevated
shell and re-reads the list to confirm; without it, it asks Android through
`killBackgroundProcesses()`, which reports nothing about what it did — so the summary says
*"asked Android to close"* rather than claiming a count it cannot verify. The freed figure is
two `ActivityManager` readings with the pass between them, and it is allowed to say it could
not measure, or that available memory did not rise.

### Monitoring

Per-core CPU usage and frequency from `/proc/stat` and `sysfs`, memory from `/proc/meminfo`
and `ActivityManager`, battery level, charging source, health, temperature, voltage and
instantaneous current, thermal status, display mode and rotation, network type, latency and
throughput, and free storage — with live graphs, a configurable sampling interval, and
sampling that stops the moment nothing is looking at it.

Battery drain is reported as **percent per hour** rather than raw points lost, and only
after five minutes of discharging, so a two-minute session cannot extrapolate to a
headline figure.

### Sessions

Every tracked session is written to an encrypted Room database: the game, start and end
time, duration, battery delta, average and peak CPU load, average and peak memory use,
average and peak temperature, the refresh rate it ran at, the network it used, what its
latency probes did, and the colour preset and values the display was held at. A session
recorded before 1.1 reads back as no colour reading rather than as a neutral one — an absence,
not an invented zero.

The session report draws those as graphs. The history screen filters, sorts, deletes and
aggregates them. A session interrupted by the app's process being killed is repaired on the
next launch and marked as ended by process death, rather than being silently dropped or
left running forever.

**What the connection did, across the whole session.** The latency probe answers "how is it
right now" from a burst of handshakes. A tracked session also keeps what happened across all
of them: how many completed, how many did not, how many came back at least twice the
session's own running average *and* at least 40 ms above it, the worst reading, the average
spread between consecutive probes, and the longest unbroken run of failures. The report turns
that into one word — *Stable*, *Spiky*, *Unreliable*, *No reply*, *Not measured* — and one
sentence naming the figures behind it. The two are not symmetric on purpose: "stable" is
withheld until at least three probes have gone out, while a single handshake that did not
complete is reported at once. Two quiet probes are not evidence of a good connection; one
refused handshake is evidence of a bad moment. A spike is measured against the session's own
average rather than a fixed threshold, because 180 ms is an event on a connection that has
been sitting at 30 ms and unremarkable on one that has been sitting at 190 ms. None of it is
called packet loss.

**A card you can share.** The report has a share action that draws a 1080×1260 PNG on the
device — game, date, duration, average frame rate, average heat, battery drain — and hands it
to the standard share sheet as a one-shot read grant through the app's own non-exported
`FileProvider`, never as a file path. The card keeps the rules of the screen it came from,
with less room to explain them: a reading the device could not take is an em dash and never a
zero, an interrupted session's duration is marked as a lower bound, a per-hour battery figure
is only printed when the session was long enough to support one (below that the tile becomes
the points actually lost, which is a subtraction of two real readings), and the sample count
travels with the averages — four samples and four hundred are different claims, and a card is
read alone in a chat window with nothing beside it to qualify it. The game's own label is the
one string on it GameCore did not write, so it is stripped of control characters, collapsed
and clamped before being drawn, and it contributes nothing to the filename.

### Game storage

What each of your games is holding in cache, and the one part of it GameCore is willing to
delete. Every cache cleaner on a store shows one number and one button; the number is usually
the whole data directory and the button usually runs `pm clear`, which is why the reviews of
those apps are full of people who lost a save. So each game gets three figures instead of one:

- **Cache** — what the platform reports for the package, the same number as that app's own
  storage page in Settings.
- **Clearable** — the part of it GameCore can actually reach: the shared-storage cache
  directory, and nothing else. A separate reading rather than a fraction of the first, because
  Android 11 and below will not break the total down, and guessing the split would be guessing
  how much a button is about to free.
- **Untouched** — the rest. Saves, logins, settings, and the assets a game downloaded after
  install. Nothing GameCore does touches it, and it is on screen so that "freed 900 MB" can
  never be read as having come out of it.

The list is your games — an app that declares itself one, or an app you made a profile for,
which is better evidence than the manifest — ordered largest cache first, with rows whose size
could not be read placed last rather than treated as empty.

A tap reads the cache figure, deletes `Android/data/<package>/cache`, waits for the platform's
accounting to catch up, and reads the figure again. What it reports is **the difference between
those two readings**: not the size that was there beforehand, and not the exit code of the
delete, since `rm -rf` on an empty directory and on two gigabytes exit identically. The figure
is allowed to come out at nothing — the reachable part may already have been empty, or the game
may have refilled it in the second it took to look, and both are ordinary.

The copy of the cache inside the game's private data directory is out of reach: a shell running
as the shell user cannot open another app's data directory, and GameCore holds no root path to
one. That part is reported as remaining, next to a button for Android's own storage page for
the app, which needs nothing granted and reaches more than this does. Measuring needs usage
access, deleting needs Shizuku, and when either is missing the screen says which rather than
offering an action that would achieve nothing.

### Refresh rate, honestly

Only rates the panel actually advertises are offered — the list comes from
`Display.getSupportedModes()`, not from a hardcoded 60/90/120.

A change is reported as applied **only after it has been read back and confirmed.** This
matters for a specific, verified platform behaviour: on several MediaTek and PowerVR
devices the standard `preferredDisplayModeId` / `Surface.setFrameRate()` calls are accepted
without error and then ignored. An app that reports success because the setter did not
throw is lying to its user on every one of those devices. GameCore instead reports
*"accepted the request but stayed at 60 Hz"* and offers the Shizuku path, which does work
there.

Frame rate gets the same treatment. Reliable per-frame FPS is generally not available
through public APIs without root or instrumentation, and what is available varies by OEM
and GPU. GameCore detects whether a usable signal exists on the device it is running on,
and shows *"Not available on this device"* when it does not — rather than estimating.

---

## Shizuku is optional

The app is fully usable without it. Shizuku (or a wireless-ADB pairing) raises the app to
ADB-level authority, which is what most devices require for a *device-wide* refresh-rate
change, for animation scales, for the display's colour keys, for reading and reshaping the
display's own size, for deleting a game's shared-storage cache, and for a handful of `dumpsys`
reads Android does not expose to ordinary apps.

When it is absent, capabilities that need it say so and point at the setup screen. Nothing
silently degrades into a fake result.

What the app can run with that authority is a closed, enumerated set:

| Program | Used for |
| --- | --- |
| `id` | confirming the shell's own uid before trusting it |
| `cat` | `/proc/stat`, `/proc/meminfo` |
| `dumpsys` | `thermalservice`, `battery`, `display`, `SurfaceFlinger --latency`, `gfxinfo` |
| `settings get/put` | nineteen specific keys, each with its own value range |
| `getprop` | three `ro.*` chipset properties |
| `pm grant` | three permissions, **to this app only** |
| `appops set` | two app-ops, **to this app only** |
| `wm size` | reading the display's size, setting a per-game override, and clearing it |
| `am kill` | closing one named background app, when a profile asks to free memory |
| `rm -rf` | one game's shared-storage cache directory, on a tap in Game storage |

There is no shell interpreter in that list, so there is no string to inject into. Every
argument that originates outside the app's own code — a package name from a stored profile,
a value from a slider — is validated against a form before a command is built, and a
rejected argument produces no command at all rather than a malformed one. `pm grant` and
`appops set` are checked against the compiled application id, so neither can be aimed at
another app.

---

## Permissions, and why each one is there

Every entry below is explained in the app's own Permissions screen too, with a button to the
exact settings page that grants it. Nothing is requested until the feature that needs it is
switched on.

| Permission | What stops working without it |
| --- | --- |
| `SYSTEM_ALERT_WINDOW` | the floating button, pill, crosshair and HUD |
| `PACKAGE_USAGE_STATS` | automatic game detection and apply-on-launch, and cache sizes |
| `QUERY_ALL_PACKAGES` | listing installed games so you can make a profile |
| `KILL_BACKGROUND_PROCESSES` | "free RAM on launch" on a device without Shizuku |
| `WRITE_SETTINGS` | brightness, screen timeout and orientation lock |
| `ACCESS_NOTIFICATION_POLICY` | the Do Not Disturb toggle |
| `MODIFY_AUDIO_SETTINGS` | the volume slider |
| `WRITE_SECURE_SETTINGS` | colour correction (granted through Shizuku or adb only) |
| `ACCESS_NETWORK_STATE`, `INTERNET` | network type, latency and throughput |
| `POST_NOTIFICATIONS` | any foreground service, since each must post one |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | detection and tracking surviving Doze |
| `FOREGROUND_SERVICE_*` | the four background services |
| Shizuku `API_V23` | the elevated capabilities, if you use Shizuku at all |

The four services each declare their real Android 14 foreground-service type rather than one
generic value: `dataSync` for session tracking, `mediaProjection` for screen recording with
its own separate consent flow, and `specialUse` with a manifest justification for the
overlay, detection and monitoring services, which is the closest the platform offers.
Nothing runs when it is not needed — each service stops itself.

---

## Security

- **Release builds go through R8** with obfuscation and resource shrinking, so a decompiled
  APK does not hand over the optimization engine's internals or the shell command structure.
- **The Room database is encrypted with SQLCipher**, and its key is generated in and held by
  the Android Keystore — not stored in a file the app could leak. Preferences go through
  `EncryptedSharedPreferences`.
- **`android:allowBackup="false"`.** The session database and encrypted preferences never
  leave the device through a backup transport.
- **One intent filter in the whole manifest**, the launcher activity's. No exported receivers
  and no deep links, and every other activity and service is `exported="false"`. Two content
  providers exist and neither is a way in: the AndroidX `FileProvider` that hands out
  screenshots, recordings, exports and session cards is not exported and grants a read on one
  URI at a time, and the Shizuku startup provider must be exported for Shizuku's own server
  process to bind it, so it is guarded by `INTERACT_ACROSS_USERS_FULL` — a signature-level
  permission no ordinary app holds.
- **The files that leave the device leave by your hand.** Screenshots, recordings, session
  exports and session cards are written inside the app's own storage and reach another app only
  through the share sheet, as a content URI with a one-shot read grant rather than a path, which
  is why GameCore asks for no storage permission on any API level. A card's name is a timestamp
  and nothing else — a game's own label has no business in a path — at most four are kept before
  the older ones are deleted, and the backup rules exclude the whole of `files/` from both cloud
  backup and device transfer.
- **No cleartext traffic**, enforced by a network security config. The only outbound
  connection the app ever makes is the TCP latency probe, to a host you can change or switch
  off in Settings.
- **All external text is sanitized before storage and again before render.** A game's
  `android:label` is another developer's string and an imported HUD layout is a file from
  anywhere, so both pass a filter that strips bidi overrides, zero-width characters, control
  characters and combining-mark stacks, and truncates on a grapheme boundary rather than
  mid-surrogate. There is a unit test per hostile input, with each invisible character built
  from its code point so the test file stays reviewable.
- **No security-relevant behaviour branches on `BuildConfig.DEBUG`** or on a preference, and
  no shell output, session data or device identifier is logged in a release build.
- **Root is detected, not required and not exploited.** If the sandbox is not intact,
  GameCore says so plainly rather than continuing to assume its own guarantees hold.

---

## Architecture

Kotlin only, Jetpack Compose with Material 3, MVVM, Hilt, Coroutines and `StateFlow`. No
Java, no XML layouts — the only XML is Android resources and the manifest. 198 source files,
about 49k lines.

```
app/
├── core/
│   ├── common/       Observed<T>, AccessLevel, Formatters, TextSanitizer
│   ├── model/        the domain types — profiles, HUD, colour, sessions, readings
│   ├── permissions/  the catalog and live permission state
│   ├── system/       readers and controllers over the platform APIs
│   ├── overlay/      window geometry, drag/snap arithmetic, the overlay hosts
│   └── shizuku/      the elevated shell, its command set, root detection
├── data/
│   ├── database/     Room + SQLCipher, entities, DAOs, mappers
│   ├── preferences/  EncryptedSharedPreferences, the Keystore-held DB key
│   └── repository/   the only thing the domain layer talks to
├── domain/
│   ├── color/        the colour values, and which sink each one can reach
│   ├── display/      refresh rate and display size, applied and read back
│   ├── gaming/       game detection, profile apply and unwind, session tracking
│   ├── memory/       which apps a reclaim pass may close, and what it measured
│   ├── monitoring/   sampling, aggregation, thermal, battery and latency trends
│   ├── optimization/ OptimizationManager over Standard / Shizuku optimizers
│   ├── overlay/      what is on screen and why
│   └── storage/      per-game cache measurement, and the one delete it offers
├── service/          the four foreground services + screen recording
└── ui/               one package per screen, each a Screen + State + ViewModel
```

The UI never executes a shell command or touches a system API directly; it reads state and
calls into the domain layer. `OptimizationManager` chooses between `StandardAndroidOptimizer`
and `ShizukuOptimizer` based on what `DeviceCapabilityChecker` found, and every operation
comes back as one of *available*, *requires Shizuku*, *requires a permission*,
*unsupported*, *applied* or *failed* — there is no boolean that means "probably".

---

## Building

```bash
git clone https://github.com/Dreamucxe/GameCore.git
cd GameCore

./gradlew :app:assembleDebug          # installable debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests, no device needed
./gradlew :app:assembleRelease        # R8 + shrinking; signed if you have a key
./gradlew :app:lintDebug              # zero warnings is the standard here
```

Kotlin 2.0.21 · AGP 8.5.2 · Gradle 8.9 · JDK 17 · compileSdk 34 · minSdk 26.

`assembleRelease` produces a **signed** APK when a `keystore.properties` is present at the
project root:

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without it the build still succeeds and emits an unsigned APK, so a contributor with no
signing key is not blocked — but an unsigned APK will not install on a device.

The unit tests are deliberately written against the pure, Android-free seams: the geometry,
the formatters, the sanitizer, the command builder, the aggregators, the state reducers, the
per-session latency fold, and every word the shareable card is allowed to print. 26 suites,
293 tests. No mocking framework, no Robolectric, no emulator — the suite runs on any JDK.

## Offline by design

There is no backend, no account system, no cloud sync, no analytics and no crash reporting.
Nothing about your device or your play sessions leaves it on its own. The single network call
in the whole app is the latency probe, which exists because you asked for a ping figure and
can be turned off. The session card is the one artefact meant to leave, and it leaves the way
a screenshot does: drawn on the device, then handed to whichever app you choose from your own
share sheet. GameCore has nowhere of its own to send it.

## License

MIT — see [LICENSE](LICENSE).
