# GameCore

An Android gaming overlay, performance monitor and per-game profile manager — built on the
rule that every number it shows is one Android actually reported.

[![Download APK](https://img.shields.io/badge/Download-GameCore%20v1.0%20APK-2962FF?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Dreamucxe/GameCore/releases/latest/download/GameCore.apk)

Android 8.0 (API 26) or newer · signed release build · sideload, no store listing · works
fully offline

---

## What makes it different

Most "game booster" apps are theatre. They show a RAM figure, animate a progress bar, kill
some processes Android immediately restarts, and report success. GameCore is built the
other way around: its central type is

```kotlin
sealed interface Observed<out T> {
    data class Value<T>(val value: T, val source: DataSource, val precision: Precision)
    data class Restricted(val reason: RestrictionReason, val unlockedBy: AccessLevel?, ...)
    data class Failed(val detail: String)
    data object AwaitingSample
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
system call worked when it did not. No continuously killing background apps. No thermal
limit override. No modification of protected system files.

The full set of shell commands the app can construct lives in one file
(`core/shizuku/ShellCommand.kt`) and a unit test enumerates every one of them and asserts
that `am`, `cmd`, `su`, `sh`, `rm`, `setprop`, `force-stop`, `install` and friends are
unreachable at any privilege level.

---

## Features

### Floating overlay

- A draggable gaming button that snaps to whichever edge its centre is nearer, stays on
  screen when the device rotates, and remembers where you left it.
- Tapping it opens a control panel **directly below the button**, wherever the button
  happens to be — brightness and media-volume sliders, screenshot, screen recording,
  Do Not Disturb, orientation lock, flashlight, and a shortcut back into the game.
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

One profile per package, holding target refresh rate, brightness, orientation lock, screen
timeout, media volume, Do Not Disturb, which overlays to raise, a HUD layout, a crosshair
preset, a performance mode and whether to track the session.

Every adjustable field is nullable, and **null means leave it alone** — not "use a
default". A profile that sets only brightness records what brightness was, changes it, and
puts exactly that back when the game exits. Nothing else is touched, so nothing else can be
restored to a value the device never had.

Automatic detection applies a profile when its game comes to the foreground and unwinds it
when the game leaves. You can also launch a game straight from its profile screen.

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
average and peak temperature, the refresh rate it ran at and the network it used.

The session report draws those as graphs. The history screen filters, sorts, deletes and
aggregates them. A session interrupted by the app's process being killed is repaired on the
next launch and marked as ended by process death, rather than being silently dropped or
left running forever.

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
change, for animation scales, and for a handful of `dumpsys` reads Android does not expose
to ordinary apps.

When it is absent, capabilities that need it say so and point at the setup screen. Nothing
silently degrades into a fake result.

What the app can run with that authority is a closed, enumerated set:

| Program | Used for |
| --- | --- |
| `id` | confirming the shell's own uid before trusting it |
| `cat` | `/proc/stat`, `/proc/meminfo` |
| `dumpsys` | `thermalservice`, `battery`, `display`, `SurfaceFlinger --latency`, `gfxinfo` |
| `settings get/put` | eleven specific keys, each with its own value range |
| `getprop` | three `ro.*` chipset properties |
| `pm grant` | two permissions, **to this app only** |
| `appops set` | two app-ops, **to this app only** |

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
| `PACKAGE_USAGE_STATS` | automatic game detection, and therefore apply-on-launch |
| `QUERY_ALL_PACKAGES` | listing installed games so you can make a profile |
| `WRITE_SETTINGS` | brightness, screen timeout and orientation lock |
| `ACCESS_NOTIFICATION_POLICY` | the Do Not Disturb toggle |
| `MODIFY_AUDIO_SETTINGS` | the volume slider |
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
- **Nothing is exported** except the launcher activity. No content provider of our own, no
  exported receivers, no deep links.
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
Java, no XML layouts — the only XML is Android resources and the manifest. 168 source files,
about 38k lines.

```
app/
├── core/
│   ├── common/       Observed<T>, AccessLevel, Formatters, TextSanitizer
│   ├── model/        the domain types — profiles, HUD, sessions, readings
│   ├── permissions/  the catalog and live permission state
│   ├── system/       readers and controllers over the platform APIs
│   ├── overlay/      window geometry, drag/snap arithmetic, the overlay hosts
│   └── shizuku/      the elevated shell, its command set, root detection
├── data/
│   ├── database/     Room + SQLCipher, entities, DAOs, mappers
│   ├── preferences/  EncryptedSharedPreferences, the Keystore-held DB key
│   └── repository/   the only thing the domain layer talks to
├── domain/
│   ├── gaming/       game detection, profile apply and unwind, session tracking
│   ├── monitoring/   sampling, aggregation, thermal and battery trends
│   ├── optimization/ OptimizationManager over Standard / Shizuku optimizers
│   └── overlay/      what is on screen and why
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
the formatters, the sanitizer, the command builder, the aggregators, the state reducers. No
mocking framework, no Robolectric, no emulator — the suite runs on any JDK.

## Offline by design

There is no backend, no account system, no cloud sync, no analytics and no crash reporting.
Nothing about your device or your play sessions leaves it. The single network call in the
whole app is the latency probe, which exists because you asked for a ping figure and can be
turned off.

## License

MIT — see [LICENSE](LICENSE).
