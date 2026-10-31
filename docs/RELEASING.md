# Releasing a GameCore build

Standing procedure for every version this tree ships. Everything below is run on this
arm64 host; nothing else needs a workstation.

## 1. Toolchain

```bash
source /root/android-env.sh      # JDK 17, Android SDK 34, C.utf8 locale, PATH
```

`~/.gradle/gradle.properties` already points Gradle at the system `aapt2`
(`android.aapt2FromMavenOverride=/usr/bin/aapt2`) because the SDK's own aapt2 is an
x86_64 binary that cannot run here, and it turns the daemon off so a build fits in
this device's memory.

The locale is not optional. Several test names contain an em dash, and the Kotlin
compiler writes one class file per test name; under `POSIX` the daemon fails with
`InvalidPathException: Malformed input or input contains unmappable characters`.

## 2. Version bump

| File | Change |
| --- | --- |
| `app/build.gradle.kts` | `versionCode` and `versionName` |
| `app/src/main/java/com/gamecore/core/plugin/PluginApi.kt` | `HOST_VERSION_CODE` must equal the new `versionCode` — `PluginHostVersionTest` fails the build otherwise |
| `app/src/main/java/com/gamecore/core/whatsnew/WhatsNewRegistry.kt` | one `ReleaseNote`, newest first, describing only what actually shipped |
| `README.md` | a "What's new in …" section for the same release |

`versionCode` is monotonic and is what plugins compare against (`minHostVersion`),
so it only ever goes up.

### Current baseline

This tree is **GameCore 4.0**, `versionCode` **49**, signed with the real
GameCore certificate. The next release starts here and bumps to `versionCode`
**50**; nothing is built against the older 3.x line any more. The working copy
lives at `/root/GameCore-4.0`.

Every release is archived to `/sdcard/Download` as
`GameCore-<version>.apk`, `GameCore-<version>-source.zip` and their `.sha256`
sidecars — see section 5.

## 3. Build and test

```bash
source /root/android-env.sh
cd /root/GameCore-4.0
./gradlew :gamecore-plugin-sdk:test --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleRelease --no-daemon
```

`keystore.properties` and `release.keystore` sit in the project root and are what
sign the release build; without them `assembleRelease` still succeeds but produces
an unsigned APK. The release build runs R8 and shrinks resources, so it is the
build that must be installed and tested on a device — the debug build does not
exercise the same rules.

The SDK test suite rewrites every `dist/*.gcplugin` with fresh zip timestamps
(member contents are unchanged, the sidecar hashes move with them). That is the
upstream workflow, not a defect, but it means `dist/` churns after any SDK test run.

## 4. Verify the artifact

```bash
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -3
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
unzip -l app/build/outputs/apk/release/app-release.apk | grep -E '\.ttf|assets/licenses'
```

Check the version code and name, the signing certificate, and that every bundled
font and licence text is inside the APK.

## 5. Archive every version to Download

Everything GameCore lives in one folder, and each version in its own folder inside
it, together with its source, so a build on disk can always be traced back to the
tree that produced it and Download's root never fills up with loose files:

```
/sdcard/Download/GameCore Project/
    GameCore-<version>/
        GameCore-<version>.apk
        GameCore-<version>.apk.sha256
        GameCore-<version>-source.zip
        GameCore-<version>-source.zip.sha256
        SHA256SUMS.txt
        fonts/                      (when dist/liquidglass-font-* exist)
            liquidglass-font-*.gcplugin
            SHA256SUMS.txt
    LiquidGlass-GameCore-<version>/  (plugin packages, when built separately)
    LiquidGlass-Fonts/
    GameCore-<older versions>/
    ...
```

```bash
cd /root/GameCore-4.0
tools/archive-release.sh <version-label> [path-to-apk]
```

The source zip is the project tree **without** build output and **without the signing
material** — `keystore.properties`, `release.keystore`, `local.properties`, `build/`,
`.gradle/` and `.kotlin/` are all excluded. The keystore never leaves this machine.

The script does the whole job: it creates the version folder, copies the APK,
writes the source zip with `python3`'s `zipfile` (no `zip` binary needed), copies the
Liquid Glass packages from `dist/` into `fonts/`, and writes a `.sha256` beside each
artifact plus a combined `SHA256SUMS.txt`.

Never write a release artifact to Download's root or to the project folder's root —
the version folder keeps every file that belongs to a release in one place.

Any extra data that ships with the release — for example the Liquid Glass font
packages — goes in its own folder next to the APK, with its own checksum file.
