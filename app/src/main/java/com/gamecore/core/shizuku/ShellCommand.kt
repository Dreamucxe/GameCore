package com.gamecore.core.shizuku

import com.gamecore.BuildConfig
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.CpuAffinityMask
import com.gamecore.core.model.DisplaySize

/**
 * Every command GameCore can run through an elevated shell, enumerated.
 *
 * [ElevatedShell.execute] takes one of these rather than a string or an argument
 * vector, so there is no expressible way for any caller — including a future one —
 * to run a command that is not written down in this file. The set of things GameCore
 * can do with ADB-level authority is therefore reviewable in one place, and the
 * review is enforced by the compiler rather than by convention.
 *
 * Four kinds of command are deliberately *absent*, and the absence is the point:
 *
 *  * `am force-stop` and `pm trim-caches`. Force-stop ends an app whatever it is
 *    doing and cancels its alarms and its jobs with it; trim-caches empties every
 *    application's cache so that a number on a dial can move. Both are what a fake
 *    "RAM booster" reaches for, and neither is reachable from here at any privilege
 *    level. [ClearSharedCache] is not a way back to the second: it names one package,
 *    is built only for a package the user tapped, and reaches one directory that the
 *    owning app itself is required to treat as disposable.
 *  * `pm clear`. It empties an app's data directory, which is where saves, logins and
 *    settings live. The cache feature exists precisely because that command is the
 *    wrong tool for it, and no argument about convenience gets it into this file.
 *  * `cmd thermalservice override-status`. Lying to the thermal service about how
 *    hot the device is switches off the protection that keeps it from damaging
 *    itself. Not exposed for any profile setting, ever.
 *  * `cmd package compile`, and anything else that rewrites another application's
 *    on-disk artefacts. GameCore does not modify games.
 *
 * `am kill` stood in that first bullet until the launch-time memory reclaim was built,
 * and [KillBackgroundApp] is the result, so the change is written down here rather than
 * quietly made. What that command reaches is the platform's own
 * `killBackgroundProcesses`, bounded to processes Android already ranks as expendable;
 * what reaches it is one feature, off by default, which reads the process list before it
 * builds a single command and names every app it left running along with the reason. The
 * bullet still holds for the two commands it now names. What this file can no longer
 * claim is that no path to closing an app exists at all.
 *
 * `taskset` is the second such amendment, and it crosses a line the rest of this file does
 * not, so it is recorded in the same way. Every other command here either reads something
 * global or writes something about *this* device — a settings key, a display size,
 * GameCore's own permissions. [SetCpuAffinity] is the first that takes a handle to another
 * running process and changes how the kernel schedules it. Four things bound it. It is
 * built only for the pid of the game the user configured, resolved from the same
 * [RunningProcesses] dump the reclaim pass already reads, so there is no second way to name
 * a process and no way to name one the user did not choose. It writes an affinity mask and
 * nothing else — not a priority, not a cgroup, not a scheduling policy, none of which
 * appear in any argv here. The mask comes from one of two presets computed from this
 * device's own core layout, never from typed input. And the previous mask is read first and
 * recorded in the same restore ledger every other change goes through, so it is undone by
 * the mechanism that undoes refresh rate rather than by a second one. `-a` is present
 * because the brief is whole-process affinity: without it the kernel moves one thread and
 * leaves the render thread wherever it was, which is a change that reads as working and is
 * not.
 *
 * Arguments are validated by the factory functions in [ShellCommand.Companion]
 * rather than trusted: an index is range-checked, a package name has to match the
 * platform's grammar, a refresh rate has to be a plain number in a plausible range.
 * The vector reaches `exec` as a real `String[]` and no shell ever parses it, so
 * quoting is not the concern — the concern is that a malformed argument arriving at
 * `settings put` writes a malformed value into the device's settings provider,
 * which is worse than a rejected command.
 */
sealed class ShellCommand(
    val argv: List<String>,
    val description: String,
    val effect: Effect,
) {

    /**
     * Whether running this changes anything.
     *
     * [Effect.CHANGES_SETTING] commands are the only ones a game profile may apply.
     * Every one that writes a value is paired with a read of that value taken
     * beforehand, and the optimizer records it before writing so the change can be
     * undone when the game exits. Two write no value and cannot be undone —
     * [KillBackgroundApp] and [ClearSharedCache], since a closed app comes back when the
     * user opens it and a cache refills as the game runs, not when GameCore restores
     * something — so both are accounted for in the report of the pass that ran them
     * rather than in the restore ledger. Neither is reachable from a profile: one is the
     * launch-time reclaim, the other is a button the user pressed.
     */
    enum class Effect { READ_ONLY, CHANGES_SETTING }

    val isReadOnly: Boolean get() = effect == Effect.READ_ONLY

    // ---------------------------------------------------------------- read-only

    /** Verifies a shell exists and reports the uid it runs as. */
    data object Probe : ShellCommand(listOf("id"), "Verify shell access", Effect.READ_ONLY)

    /**
     * System-wide CPU counters, through the shell.
     *
     * GameCore reads `/proc/stat` directly first — it is world-readable on most
     * devices — and only falls back to here when SELinux on a particular vendor
     * kernel denies the app the read.
     */
    data object CpuStat : ShellCommand(
        listOf("cat", "/proc/stat"), "Read CPU counters", Effect.READ_ONLY,
    )

    data object MemInfo : ShellCommand(
        listOf("cat", "/proc/meminfo"), "Read memory counters", Effect.READ_ONLY,
    )

    /**
     * Thermal status and every sensor the thermal HAL exposes, with names and
     * throttling thresholds. `PowerManager.getCurrentThermalStatus()` gives the
     * coarse status without Shizuku; this gives the per-sensor detail that lets the
     * thermal graph label a reading "CPU" rather than "thermal zone 7".
     */
    data object ThermalService : ShellCommand(
        listOf("dumpsys", "thermalservice"), "Read thermal sensors", Effect.READ_ONLY,
    )

    data object BatteryDump : ShellCommand(
        listOf("dumpsys", "battery"), "Read battery detail", Effect.READ_ONLY,
    )

    /**
     * Display configuration, including the mode list and which mode is active.
     *
     * The point of reading this with Shizuku is verification, not discovery:
     * `Display.getSupportedModes()` already enumerates the modes, but only the
     * platform's own dump can confirm that a requested mode was actually adopted —
     * which is exactly what the refresh-rate control has to prove before it reports
     * success on a device whose standard API silently does nothing.
     */
    data object DisplayDump : ShellCommand(
        listOf("dumpsys", "display"), "Read display modes and the active mode", Effect.READ_ONLY,
    )

    /**
     * The panel's own size, and the size override sitting on it if there is one.
     *
     * The only place both numbers appear. `Display` and `WindowManager` report the size the app is being
     * given, which while an override is active is the override — so from inside the process there is no way
     * to tell a 1080×1440 phone from a 1080×2400 phone that has been stretched, and no way to know what
     * "back to native" would mean. Two lines of this command's output answer both, which is why it is read
     * before a size is set as well as after.
     */
    data object GetDisplaySize : ShellCommand(
        listOf("wm", "size"), "Read the display size and any override", Effect.READ_ONLY,
    )

    /**
     * SurfaceFlinger's frame timing, which is the only non-root source of
     * whole-device frame data that exists.
     *
     * Used *solely* to decide whether a reliable per-frame signal is present on this
     * device, and it very often is not: the section shape differs between Android
     * versions and vendors, and several OEMs ship a SurfaceFlinger whose latency
     * dump is empty. When it cannot be parsed with confidence, the FPS reading
     * reports "not available on this device" — it is never turned into an estimate.
     */
    data object SurfaceFlingerLatency : ShellCommand(
        listOf("dumpsys", "SurfaceFlinger", "--latency"),
        "Check whether frame timing is exposed",
        Effect.READ_ONLY,
    )

    /** Which package is in the foreground, when usage access has not been granted. */
    data object TopActivity : ShellCommand(
        listOf("dumpsys", "activity", "activities"),
        "Read the foreground activity",
        Effect.READ_ONLY,
    )

    /**
     * Every running process, each with the platform's own label for why it is still alive.
     *
     * The read that makes closing background apps checkable rather than hopeful, and it answers both
     * questions the reclaim pass has out of one dump. Which packages have a process at all: closing an
     * app that was not running is how a booster inflates its count, and an app GameCore never saw
     * running is one it never claims to have closed. And what each one is doing: the LRU list prints
     * `(top-activity)`, `(fg-service)`, `(cch-empty)` and a dozen others after each process name, which
     * is the difference between a cached app nobody will miss and the one playing the user's music.
     *
     * Read a second time, unchanged, after the closes — a package still in the list was not closed,
     * whatever the shell said on the way out.
     *
     * `dumpsys activity services` was the obvious alternative for the foreground-service half and is
     * not used: it answers one of the two questions and would need this dump beside it to answer the
     * other, and two dumps during a game launch cost twice what one does.
     */
    data object RunningProcesses : ShellCommand(
        listOf("dumpsys", "activity", "processes"),
        "Read running processes and what each is doing",
        Effect.READ_ONLY,
    )

    /**
     * Reads the affinity mask of every thread in one process.
     *
     * The read half of the pair, and the reason the write half can be verified rather than
     * hoped about. `taskset -ap <pid>` prints one `current affinity mask:` line per thread,
     * which is what makes both checks possible: the mask before a change is what goes into
     * the restore ledger, and the masks after one are what decide whether the kernel
     * actually took it. A shell that exits zero has told you the syscall returned, not that
     * every thread moved — a thread created between the write and the read has the mask it
     * inherited, and a game engine that pins its own threads may have overridden it back.
     *
     * Read-only and reaching another process, which no other read here does. `-p` is the
     * whole reason it is safe to say that: without it `taskset` runs a command, and this
     * file exposes no way to spell that.
     */
    class ReadCpuAffinity private constructor(val pid: Int) : ShellCommand(
        listOf("taskset", "-ap", pid.toString()),
        "Read which cores process $pid may run on",
        Effect.READ_ONLY,
    ) {
        companion object {
            /** Null unless the pid is one Linux could have issued. See [validPidOrNull]. */
            fun of(pid: Int): ReadCpuAffinity? = validPidOrNull(pid)?.let { ReadCpuAffinity(it) }
        }
    }

    /** Reads one key from the settings provider, so a write can be undone. */
    class GetSetting private constructor(
        val namespace: SettingsNamespace,
        val key: String,
    ) : ShellCommand(
        listOf("settings", "get", namespace.token, key),
        "Read ${namespace.token}/$key",
        Effect.READ_ONLY,
    ) {
        companion object {
            /**
             * Restricted to the same allow-list as the write. There is no reason for
             * this app to read a settings key it will never change, and a read whose
             * key came from anywhere but [WritableSetting] could not be paired with a
             * restore.
             */
            fun of(setting: WritableSetting) = GetSetting(setting.namespace, setting.key)
        }
    }

    /** A single system property. Read-only; `setprop` is not exposed here at all. */
    class GetProp private constructor(val key: String) : ShellCommand(
        listOf("getprop", key), "Read property $key", Effect.READ_ONLY,
    ) {
        companion object {
            fun of(prop: ReadableProperty) = GetProp(prop.key)
        }
    }

    /**
     * Per-frame timing for one package, from the platform's own rendering profiler.
     *
     * The honest FPS source, and the reason the FPS reading is so often absent. This
     * returns real frame timestamps — nothing is inferred from them — but only for
     * content drawn through HWUI, the View-based pipeline. A game built on Unity,
     * Unreal or any other engine that renders into a `SurfaceView` from native code
     * bypasses HWUI entirely, and for it this dump comes back with an empty
     * framestats section.
     *
     * That empty section is the answer, not a failure to be worked around: it means
     * the platform does not expose this game's frame timing to an app, and the
     * reading reports "not available on this device" rather than substituting
     * GameCore's own window refresh rate, which would be a different number wearing
     * the game's name.
     */
    class GfxInfoFrameStats private constructor(val packageName: String) : ShellCommand(
        listOf("dumpsys", "gfxinfo", packageName, "framestats"),
        "Read frame timing for $packageName",
        Effect.READ_ONLY,
    ) {
        companion object {
            /**
             * Null unless the argument is a well-formed package name. This is the only
             * command that takes a value originating outside GameCore's own code — the
             * package comes from the profile the user configured — so it is the one
             * that has to check.
             */
            fun of(packageName: String): GfxInfoFrameStats? =
                TextSanitizer.validatePackageName(packageName)?.let { GfxInfoFrameStats(it) }
        }
    }

    // ------------------------------------------------------------ state-changing

    /**
     * Writes one key in the settings provider.
     *
     * This is the whole of GameCore's write surface, and it is narrow on purpose:
     * only the keys enumerated in [WritableSetting] are reachable, each is a
     * documented `Settings.System`/`Settings.Global`/`Settings.Secure` key, each value
     * is checked against that key's declared form before the command is built, and each
     * is paired with a [GetSetting] read taken beforehand so the write can be undone
     * when the game exits.
     */
    class PutSetting private constructor(
        val namespace: SettingsNamespace,
        val key: String,
        val value: String,
    ) : ShellCommand(
        listOf("settings", "put", namespace.token, key, value),
        "Set ${namespace.token}/$key to $value",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            /**
             * Null on a value outside the setting's declared form or range. A rejected
             * command is a feature reporting "could not be applied"; an accepted
             * malformed one writes nonsense into the device's settings provider, where
             * it outlives GameCore's process and possibly GameCore's installation.
             */
            fun of(setting: WritableSetting, value: String): PutSetting? =
                if (setting.accepts(value)) {
                    PutSetting(setting.namespace, setting.key, value)
                } else {
                    null
                }
        }
    }

    /**
     * Grants a permission to GameCore itself.
     *
     * Only the two appop-backed permissions the app declares and cannot request
     * through a normal dialog are reachable, and only for GameCore's own package.
     * It is what lets a Shizuku user grant usage access without walking through three
     * Settings screens; without Shizuku the app opens those screens instead.
     */
    class GrantSelfPermission private constructor(
        packageName: String,
        val permission: String,
    ) : ShellCommand(
        listOf("pm", "grant", packageName, permission),
        "Grant $permission to GameCore",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            /**
             * [ownPackageName] is compared against the compiled applicationId rather
             * than trusted, so this command cannot be aimed at another application even
             * if a caller passes a different name. Returns null when it does not match.
             */
            fun of(
                ownPackageName: String,
                permission: SelfGrantablePermission,
            ): GrantSelfPermission? = if (ownPackageName == BuildConfig.APPLICATION_ID) {
                GrantSelfPermission(ownPackageName, permission.androidName)
            } else {
                null
            }
        }
    }

    /**
     * Sets an app-op for GameCore itself. Same restriction: own package only.
     *
     * `pm grant` does not work for appop-backed permissions such as
     * PACKAGE_USAGE_STATS on every Android version, so this is the path that
     * actually succeeds for those.
     */
    class SetSelfAppOp private constructor(
        packageName: String,
        val op: String,
        val mode: String,
    ) : ShellCommand(
        listOf("appops", "set", packageName, op, mode),
        "Set app-op $op for GameCore to $mode",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            fun of(ownPackageName: String, op: SelfAppOp, allow: Boolean): SetSelfAppOp? =
                if (ownPackageName == BuildConfig.APPLICATION_ID) {
                    SetSelfAppOp(ownPackageName, op.opName, if (allow) "allow" else "default")
                } else {
                    null
                }
        }
    }

    /**
     * Sets the display's logical size, which is what the aspect-ratio feature is.
     *
     * `wm` is the fourth program with write authority here, and the narrowest: `size` is the only
     * subcommand reachable, and the only argument it can be given is a pair of pixel counts. Its siblings
     * are absent for the same reason the three families named at the top of this file are — `wm density`
     * leaves a device whose UI is too large or too small to operate, from a value the user cannot see to
     * correct; `wm dismiss-keyguard` unlocks a screen the user locked. Neither is a game setting.
     *
     * A size override outlives the process that set it and the reboot after it, so this is also the command
     * with the longest reach in the file. That is why the bounds below are checked here rather than only at
     * the call site, why [ResetDisplaySize] exists as its own case rather than as a magic argument, and why
     * the controller records the previous size before writing.
     */
    class SetDisplaySize private constructor(val size: DisplaySize) : ShellCommand(
        listOf("wm", "size", size.argument),
        "Set the display size to ${size.label}",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            /**
             * Null on anything that is not a plausible size for a screen.
             *
             * The lower bound is [DisplaySize.MIN_SIDE], the same floor the editor refuses; the upper one is
             * larger than any panel that exists. Whether a size suits *this* display is a separate question
             * that needs the panel's own size to answer — [DisplaySize.rejectionFor] does that, and this
             * check stands whether or not it was asked.
             */
            fun of(size: DisplaySize): SetDisplaySize? =
                if (size.shortSide >= DisplaySize.MIN_SIDE && size.longSide <= MAX_SIDE_PIXELS) {
                    SetDisplaySize(size)
                } else {
                    null
                }

            private const val MAX_SIDE_PIXELS = 16_384
        }
    }

    /**
     * Clears the size override, whoever set it.
     *
     * The undo, and deliberately not `wm size <the physical size>`: an override that happens to equal the
     * panel is still an override, still a row in the display settings, and still something a later reader
     * would report as a stretched display. Reset removes it instead.
     */
    data object ResetDisplaySize : ShellCommand(
        listOf("wm", "size", "reset"), "Clear the display size override", Effect.CHANGES_SETTING,
    )

    /**
     * Ends the background processes of one package, and nothing more than those.
     *
     * The exception to the first bullet at the top of this file, and narrow enough to be worth stating
     * precisely. `am kill` reaches `ActivityManager.killBackgroundProcesses` inside the platform — the
     * same call GameCore makes on its own behalf when there is no shell to run this one — so what it can
     * do is bounded by that method rather than by the authority running it: processes the platform ranks
     * at or below a plain background service, and no others. A package with an activity on screen, a
     * foreground service, or a provider something else is reading survives it, and the command reports
     * success either way, having done nothing.
     *
     * That bound is why this is `kill` and not `force-stop`. Force-stop ends an app whatever it is doing
     * and takes its alarms and jobs with it; this is closer to what the platform does by itself under
     * memory pressure, a few seconds earlier and to a chosen set.
     *
     * GameCore does not lean on the bound. [com.gamecore.domain.memory.BackgroundAppReclaimer] reads
     * [RunningProcesses] first and will not build this command for an app it saw in a protected state,
     * or for one whose state it could not read — "the platform would have ignored it anyway" is a reason
     * not to fear a mistake, not a reason to make one.
     *
     * `--user current` rather than the default of every user: a work profile's apps are not this user's
     * to close, and the pass that chose this package never enumerated them.
     */
    class KillBackgroundApp private constructor(val packageName: String) : ShellCommand(
        listOf("am", "kill", "--user", "current", packageName),
        "Close the background processes of $packageName",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            /**
             * Null unless the argument is a well-formed package name, checked here for the reason
             * [GfxInfoFrameStats.of] checks: the value came from a profile, an allowlist or another
             * app's process list, none of which are GameCore's own code.
             */
            fun of(packageName: String): KillBackgroundApp? =
                TextSanitizer.validatePackageName(packageName)?.let { KillBackgroundApp(it) }
        }
    }

    /**
     * Deletes the shared-storage cache directory of one package, and nothing else under it.
     *
     * The whole of what GameCore can do about a game's cache, and narrower than the phrase
     * "clear cache" suggests, so the boundary is worth stating as a path rather than as a promise.
     * `/storage/emulated/<user>/Android/data/<pkg>/cache` is the directory the platform hands the
     * owning app through `getExternalCacheDir`, and the contract on it is the app's own: the system
     * may delete it when storage runs low, so an app that keeps anything it needs there has already
     * lost it without GameCore's help. Its siblings are left alone and are not reachable through
     * this command at all — `files` next to it holds saves, `Android/obb/<pkg>` holds the downloaded
     * assets a re-download would cost gigabytes to replace, and neither appears in any argv here.
     *
     * The internal cache, `/data/data/<pkg>/cache`, is not in reach and is not attempted. A shell
     * running as uid 2000 cannot read another app's data directory, GameCore holds no root path to
     * one, and the honest form of that is a reported platform limit with the app's own storage page
     * offered — not a command that fails and is called a failure of the device.
     *
     * `rm -rf` on a single literal path, exec'd as an argument vector with no shell anywhere in the
     * chain, so there is no glob to expand and no word-splitting to exploit; the target is the
     * directory itself rather than its contents, because a wildcard would need a shell to mean
     * anything. `-f` is what makes an
     * already-absent directory exit zero, which is the common case for a game that has never
     * written to shared storage, and is why the caller measures the cache again afterwards instead
     * of reading success out of the exit code.
     *
     * Both arguments are validated. The package name goes through the platform's grammar, which
     * admits no `/` and no `..`, so no traversal survives it. The user id is the app's own, derived
     * from its uid rather than accepted from a caller, and range-checked anyway.
     */
    class ClearSharedCache private constructor(
        val packageName: String,
        val userId: Int,
        val path: String,
    ) : ShellCommand(
        listOf("rm", "-rf", path),
        "Clear the shared-storage cache of $packageName",
        Effect.CHANGES_SETTING,
    ) {
        companion object {

            /**
             * Null unless both arguments are usable.
             *
             * The id bound is deliberately loose — Android numbers secondary users and work
             * profiles from 10 upwards and a device can hold several — and its job is only to keep
             * a nonsensical value out of a path, not to guess which users exist.
             */
            fun of(packageName: String, userId: Int): ClearSharedCache? {
                val valid = TextSanitizer.validatePackageName(packageName) ?: return null
                if (userId < 0 || userId > MAX_USER_ID) return null
                return ClearSharedCache(valid, userId, sharedCachePath(valid, userId))
            }

            /** The directory [of] targets, exposed so a caller can name it without building one. */
            fun sharedCachePath(packageName: String, userId: Int): String =
                "/storage/emulated/$userId/Android/data/$packageName/cache"

            const val MAX_USER_ID = 999
        }
    }

    /**
     * Restricts one process, and every thread in it, to a set of cores.
     *
     * The only command in this file that changes how another process runs rather than what
     * some setting on this device says, and the top-of-file note records why it is admitted
     * and what bounds it. What it does not do is worth being equally exact about: it sets a
     * mask, which is a *permission* to use cores, not a reservation of them and not a clock
     * speed. Nothing else about the process changes — not its priority, not its cgroup, not
     * its scheduling policy — because none of those has an argv here.
     *
     * `-a` is required, not decorative. Plain `-p` moves the thread whose id was named and
     * leaves every other thread in the process where it was, so on a game that means the
     * main thread is pinned and the render thread — the one that misses frames — is not.
     * A whole-process change is what was asked for, so `-a` is baked into the vector rather
     * than being a parameter a caller could forget.
     *
     * The mask is validated only for being non-zero, and that bound is honest about what it
     * can check. Zero cores is the one mask that is definitely wrong — a process allowed to
     * run nowhere does not run — while a mask naming a core this device does not have is
     * something the factory has no way to detect, because a command does not know the core
     * count. That check belongs to the caller, which derives every mask it passes from
     * [com.gamecore.core.model.CpuClusterLayout], read from this device's own `cpufreq`
     * nodes. If one ever got through, `taskset` answers with `EINVAL` and the result is
     * reported as not honoured, which is the correct outcome and not a silent one.
     */
    class SetCpuAffinity private constructor(
        val pid: Int,
        val mask: Int,
    ) : ShellCommand(
        listOf("taskset", "-ap", CpuAffinityMask.hex(mask), pid.toString()),
        "Keep process $pid on cores ${CpuAffinityMask.hex(mask)}",
        Effect.CHANGES_SETTING,
    ) {
        companion object {
            /**
             * Null unless the pid could have been issued by Linux and the mask names at
             * least one core. Arguments are in pid-then-mask order even though the vector
             * is mask-then-pid, because every other function in this feature takes the pid
             * first and an argv's order is `taskset`'s business rather than a caller's.
             */
            fun of(pid: Int, mask: Int): SetCpuAffinity? {
                val validPid = validPidOrNull(pid) ?: return null
                if (mask == 0) return null
                return SetCpuAffinity(validPid, mask)
            }
        }
    }

    /**
     * The call-site surface.
     *
     * Each of these delegates to the validating factory on the command it builds, so
     * the check lives next to the type it protects and every caller reaches it through
     * one namespace. A `null` return always means the same thing — the arguments were
     * rejected — and callers report that as "could not be applied" rather than
     * proceeding.
     */
    companion object {

        fun getSetting(setting: WritableSetting): GetSetting = GetSetting.of(setting)

        fun putSetting(setting: WritableSetting, value: String): PutSetting? =
            PutSetting.of(setting, value)

        fun putSetting(setting: WritableSetting, value: Int): PutSetting? =
            PutSetting.of(setting, value.toString())

        fun putSetting(setting: WritableSetting, value: Float): PutSetting? =
            PutSetting.of(setting, formatFloat(value))

        fun grantSelfPermission(
            ownPackageName: String,
            permission: SelfGrantablePermission,
        ): GrantSelfPermission? = GrantSelfPermission.of(ownPackageName, permission)

        fun setSelfAppOp(ownPackageName: String, op: SelfAppOp, allow: Boolean): SetSelfAppOp? =
            SetSelfAppOp.of(ownPackageName, op, allow)

        fun getProp(prop: ReadableProperty): GetProp = GetProp.of(prop)

        /** Null when the size is not a plausible screen. See [SetDisplaySize.of]. */
        fun setDisplaySize(size: DisplaySize): SetDisplaySize? = SetDisplaySize.of(size)

        fun frameStats(packageName: String): GfxInfoFrameStats? =
            GfxInfoFrameStats.of(packageName)

        /** Null when the package name is malformed. See [KillBackgroundApp]. */
        fun killBackgroundApp(packageName: String): KillBackgroundApp? =
            KillBackgroundApp.of(packageName)

        /**
         * Null when the package name is malformed or the user id is not a plausible one.
         * See [ClearSharedCache] for what this does and does not reach.
         */
        fun clearSharedCache(packageName: String, userId: Int): ClearSharedCache? =
            ClearSharedCache.of(packageName, userId)

        /** Null when the pid is not one Linux could have issued. */
        fun readCpuAffinity(pid: Int): ReadCpuAffinity? = ReadCpuAffinity.of(pid)

        /**
         * Null when the pid is implausible or the mask names no cores at all. See
         * [SetCpuAffinity] for what a mask is and is not.
         */
        fun setCpuAffinity(pid: Int, mask: Int): SetCpuAffinity? = SetCpuAffinity.of(pid, mask)

        /**
         * Validates a package name. Exposed so a caller that has to decide whether a
         * profile's stored package is still usable can ask without building a command.
         */
        fun isValidPackageName(raw: String?): Boolean =
            TextSanitizer.validatePackageName(raw) != null

        /**
         * A float as the settings provider expects it: `Locale.US`, so a device set to
         * a locale that uses a decimal comma does not write `60,0` into a key the
         * platform parses with `Float.parseFloat`. One decimal is enough for both a
         * refresh rate and an animation scale, and the trailing `.0` is kept because
         * that is the form the platform's own UI writes.
         */
        private fun formatFloat(value: Float): String =
            String.format(java.util.Locale.US, "%.1f", value)
    }
}

/**
 * A process id, or null if that number is not one.
 *
 * File-private and shared by the two `taskset` commands, so the bound is written once. The
 * ceiling is `pid_max`'s own upper limit — Linux defaults the runtime value to 32768 and lets a
 * kernel raise it to 2^22, and Android kernels do — so this rejects what could never be a pid
 * rather than pretending to know what this kernel's current ceiling is. Zero and negatives are
 * refused outright: pid 0 is the scheduler and a negative argument to `taskset` is a process
 * *group*, which is a different and much wider thing than the one process this feature is
 * allowed to touch.
 *
 * A pid that passes here can still be gone, or belong to something else entirely by the time the
 * command runs — pids are reused. Nothing about that is fixable in a validator, so the caller
 * resolves the pid from a process dump immediately before use and verifies the result afterwards
 * rather than trusting either end.
 */
private fun validPidOrNull(pid: Int): Int? =
    if (pid > 0 && pid <= MAX_PID) pid else null

/** 2^22, the largest value Linux will accept for `pid_max`. */
private const val MAX_PID = 4_194_304

/**
 * The three settings tables GameCore touches.
 *
 * `secure` was deliberately absent until the colour-correction feature needed it, and it
 * is worth writing down why it is admissible now. WRITE_SECURE_SETTINGS is declared
 * `signature|privileged|development`, and it is that third flag that matters: a
 * development permission can be granted by `pm grant` from a shell running as uid 2000,
 * which is exactly the authority Shizuku provides. Once granted it does not expire, so
 * the app then writes `Settings.Secure` in its own process — which is the only path fast
 * enough for a slider that applies as it moves.
 *
 * The keys reachable in this namespace are still only the ones enumerated in
 * [WritableSetting], each is still read back after the write, and each is still paired
 * with a restore point taken beforehand.
 */
enum class SettingsNamespace(val token: String) {
    SYSTEM("system"),
    GLOBAL("global"),
    SECURE("secure"),
}

/**
 * Every settings key GameCore may write, with the form its value has to take.
 *
 * Two things are collapsed into one list on purpose. Some of these keys are
 * writable by an ordinary app that holds WRITE_SETTINGS (brightness, rotation,
 * screen timeout) and some are not writable without ADB-level authority at all
 * (the refresh-rate bounds, the animation scales, battery saver, and every colour
 * key in `secure`). Both paths write *the same keys with the same validation*, so the
 * allow-list and the range checks live here once, and [requiredAccess] is what the
 * capability layer reads to tell the user which of the two paths a given profile
 * setting needs on their device.
 *
 * [restoreDefault] is the value used when a profile is unwound and no pre-change
 * reading was captured — the app takes a reading before every write, so this is the
 * fallback for the case where GameCore was killed between the write and the restore.
 */
enum class WritableSetting(
    val namespace: SettingsNamespace,
    val key: String,
    val requiredAccess: AccessLevel,
    val form: ValueForm,
    val restoreDefault: String,
    val userDescription: String,
) {
    /**
     * Lower bound on the display's refresh rate.
     *
     * Writing both bounds is what actually pins a rate on AOSP-derived builds: the
     * display manager picks a mode inside the window they describe. Setting only the
     * peak leaves the platform free to drop to 60 whenever it judges the content
     * static, which is precisely the behaviour a user pinning 120 Hz for a game is
     * trying to prevent.
     */
    MIN_REFRESH_RATE(
        SettingsNamespace.SYSTEM, "min_refresh_rate", AccessLevel.SHIZUKU,
        ValueForm.REFRESH_RATE, "0",
        "The lowest refresh rate the display is allowed to drop to.",
    ),

    PEAK_REFRESH_RATE(
        SettingsNamespace.SYSTEM, "peak_refresh_rate", AccessLevel.SHIZUKU,
        ValueForm.REFRESH_RATE, "0",
        "The highest refresh rate the display is allowed to use.",
    ),

    /** 0..255. Manual brightness only takes effect with automatic mode switched off. */
    SCREEN_BRIGHTNESS(
        SettingsNamespace.SYSTEM, "screen_brightness", AccessLevel.NORMAL,
        ValueForm.BRIGHTNESS, "128",
        "Screen brightness.",
    ),

    /** 0 = manual, 1 = automatic. */
    SCREEN_BRIGHTNESS_MODE(
        SettingsNamespace.SYSTEM, "screen_brightness_mode", AccessLevel.NORMAL,
        ValueForm.BOOLEAN_INT, "1",
        "Whether brightness follows the light sensor.",
    ),

    /** 0 = rotation locked, 1 = follows the accelerometer. */
    ACCELEROMETER_ROTATION(
        SettingsNamespace.SYSTEM, "accelerometer_rotation", AccessLevel.NORMAL,
        ValueForm.BOOLEAN_INT, "1",
        "Whether the screen rotates with the device.",
    ),

    /** 0..3, one of the Surface rotation constants. Only read while rotation is locked. */
    USER_ROTATION(
        SettingsNamespace.SYSTEM, "user_rotation", AccessLevel.NORMAL,
        ValueForm.ROTATION, "0",
        "The orientation the screen is locked to.",
    ),

    /** Milliseconds. Raised during a session so a long cut-scene does not blank the screen. */
    SCREEN_OFF_TIMEOUT(
        SettingsNamespace.SYSTEM, "screen_off_timeout", AccessLevel.NORMAL,
        ValueForm.TIMEOUT_MILLIS, "60000",
        "How long the screen stays on without input.",
    ),

    /**
     * The three animation scales, as Developer options exposes them.
     *
     * A real, measurable, entirely reversible saving: the system stops compositing
     * window transitions, so the frames spent on them are not spent. It changes
     * nothing inside the game and claims nothing about the game's own frame rate.
     */
    WINDOW_ANIMATION_SCALE(
        SettingsNamespace.GLOBAL, "window_animation_scale", AccessLevel.SHIZUKU,
        ValueForm.ANIMATION_SCALE, "1.0",
        "How long window-open animations take.",
    ),

    TRANSITION_ANIMATION_SCALE(
        SettingsNamespace.GLOBAL, "transition_animation_scale", AccessLevel.SHIZUKU,
        ValueForm.ANIMATION_SCALE, "1.0",
        "How long screen-transition animations take.",
    ),

    ANIMATOR_DURATION_SCALE(
        SettingsNamespace.GLOBAL, "animator_duration_scale", AccessLevel.SHIZUKU,
        ValueForm.ANIMATION_SCALE, "1.0",
        "How long in-app animations take.",
    ),

    /**
     * Battery saver. 0 or 1.
     *
     * Written only by the Battery Saver performance profile, where switching the
     * platform's own power-saving mode on is the honest implementation of what the
     * profile's name promises — as opposed to inventing a GameCore-branded saving
     * that does nothing.
     */
    LOW_POWER(
        SettingsNamespace.GLOBAL, "low_power", AccessLevel.SHIZUKU,
        ValueForm.BOOLEAN_INT, "0",
        "Android's own battery saver.",
    ),

    /**
     * Night display: the platform's own warm-shift, and the only colour sink on stock
     * Android that moves the white point.
     *
     * Two keys again, and again both are needed: the temperature is stored whether the
     * shift is on or not, so writing the temperature alone changes nothing the user can
     * see. The pair is what GameCore's "warmth" projects onto — it cannot cool, because
     * `ColorDisplayService` clamps the temperature to the device's own
     * `config_nightDisplayColorTemperature` bounds, whose maximum is warmer than
     * daylight on every build. A request to cool the screen is reported as unreachable
     * rather than written and left to clamp.
     */
    NIGHT_DISPLAY_ACTIVATED(
        SettingsNamespace.SECURE, "night_display_activated", AccessLevel.SHIZUKU,
        ValueForm.BOOLEAN_INT, "0",
        "Whether Android's warm night shift is on.",
    ),

    NIGHT_DISPLAY_COLOR_TEMPERATURE(
        SettingsNamespace.SECURE, "night_display_color_temperature", AccessLevel.SHIZUKU,
        ValueForm.COLOR_TEMPERATURE, "4000",
        "How warm the night shift is, in kelvin.",
    ),

    /**
     * The display colour mode: natural, boosted, saturated, automatic.
     *
     * Present on devices whose `config_availableColorModes` lists more than one, absent
     * on the rest, and vendor modes occupy their own range above 255 — so this is the one
     * colour key whose write is expected to be reported unconfirmed rather than applied
     * on a good number of devices. That is the read-back doing its job: the alternative
     * is a saturation slider that claims to have worked everywhere.
     */
    DISPLAY_COLOR_MODE(
        SettingsNamespace.SECURE, "display_color_mode", AccessLevel.SHIZUKU,
        ValueForm.COLOR_MODE, "0",
        "The display's colour mode.",
    ),

    /**
     * The daltonizer — the platform's colour-vision correction, applied in the
     * compositor.
     *
     * The enabled flag and the mode are separate keys because the platform stores the
     * last mode across a disable, and because monochromacy is a mode rather than a
     * separate feature: it is how a request for full greyscale is honestly satisfied.
     */
    DALTONIZER_ENABLED(
        SettingsNamespace.SECURE, "accessibility_display_daltonizer_enabled",
        AccessLevel.SHIZUKU, ValueForm.BOOLEAN_INT, "0",
        "Whether Android's colour-vision correction is on.",
    ),

    DALTONIZER_MODE(
        SettingsNamespace.SECURE, "accessibility_display_daltonizer",
        AccessLevel.SHIZUKU, ValueForm.DALTONIZER_MODE, "-1",
        "Which colour-vision correction is applied.",
    ),

    /**
     * Reduce-bright-colours, API 31 and later. Dims below the panel's own minimum
     * backlight by scaling output in the compositor, which is what a negative brightness
     * offset actually is. There is no positive counterpart: nothing in the platform can
     * push output above the panel's maximum, and the app says so.
     */
    REDUCE_BRIGHT_COLORS_ACTIVATED(
        SettingsNamespace.SECURE, "reduce_bright_colors_activated", AccessLevel.SHIZUKU,
        ValueForm.BOOLEAN_INT, "0",
        "Whether extra dimming is on.",
    ),

    REDUCE_BRIGHT_COLORS_LEVEL(
        SettingsNamespace.SECURE, "reduce_bright_colors_level", AccessLevel.SHIZUKU,
        ValueForm.PERCENT, "0",
        "How much extra dimming is applied.",
    ),

    /** Full colour inversion, device-wide, as the accessibility settings expose it. */
    COLOR_INVERSION_ENABLED(
        SettingsNamespace.SECURE, "accessibility_display_inversion_enabled",
        AccessLevel.SHIZUKU, ValueForm.BOOLEAN_INT, "0",
        "Whether colours are inverted device-wide.",
    ),
    ;

    fun accepts(value: String): Boolean = form.accepts(value)
}

/**
 * The permitted shape of a settings value.
 *
 * Every one is an explicit grammar plus a range, not a "does it look numeric"
 * check. `settings put system peak_refresh_rate 1e9` is numeric, parses as a float,
 * and is not a refresh rate; a device asked to run at a billion hertz picks
 * something arbitrary, and the user has no idea GameCore did it.
 */
enum class ValueForm {
    /** A refresh rate in Hz. 0 means "no bound", which is how a pin is released. */
    REFRESH_RATE {
        override fun accepts(value: String): Boolean {
            val f = value.toFloatOrNull() ?: return false
            return f == 0f || (f >= 20f && f <= 480f)
        }
    },

    /** Exactly "0" or "1". Not "true", not "yes", not "01". */
    BOOLEAN_INT {
        override fun accepts(value: String): Boolean = value == "0" || value == "1"
    },

    BRIGHTNESS {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it in 0..255 } == true
    },

    /** One of the four Surface.ROTATION_* constants. */
    ROTATION {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it in 0..3 } == true
    },

    /** 15 s to 30 min. Neither an instant blank nor a screen left on all night. */
    TIMEOUT_MILLIS {
        override fun accepts(value: String): Boolean =
            value.toLongOrNull()?.let { it in 15_000L..1_800_000L } == true
    },

    /**
     * An animation scale. 0 disables animations, 1 is normal; the platform's own UI
     * offers up to 10, and anything above that is not a setting a user wants.
     */
    ANIMATION_SCALE {
        override fun accepts(value: String): Boolean {
            val f = value.toFloatOrNull() ?: return false
            return f >= 0f && f <= 10f && !f.isNaN()
        }
    },

    /**
     * A colour temperature in kelvin.
     *
     * The window is wider than any device's own night-display bounds on purpose: those
     * bounds are a per-device config value that GameCore cannot read, so the honest
     * approach is to reject only what is not a colour temperature at all and let the
     * read-back report the clamping that a particular build applies.
     */
    COLOR_TEMPERATURE {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it in 1_000..10_000 } == true
    },

    /**
     * A display colour mode.
     *
     * 0..3 are the platform's own — natural, boosted, saturated, automatic — and
     * 256..511 is the range reserved for vendor modes, which is admitted because a
     * restore has to be able to write back whichever one the device was already using.
     */
    COLOR_MODE {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it in 0..3 || it in 256..511 } == true
    },

    /**
     * A daltonizer mode. Exactly the five values the platform defines: -1 disabled,
     * 0 monochromacy, 11 protanomaly, 12 deuteranomaly, 13 tritanomaly. A number in
     * between is not a mode, and writing one leaves the compositor with a filter index
     * it does not recognise.
     */
    DALTONIZER_MODE {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it == -1 || it == 0 || it in 11..13 } == true
    },

    /** 0..100, as the platform's own strength keys are scaled. */
    PERCENT {
        override fun accepts(value: String): Boolean =
            value.toIntOrNull()?.let { it in 0..100 } == true
    },
    ;

    abstract fun accepts(value: String): Boolean
}

/**
 * The permissions GameCore may grant to itself with Shizuku.
 *
 * All three are declared in the app's own manifest and none can be granted by a runtime
 * dialog. Usage access and modify-system-settings are appop gates the user normally
 * reaches through two or three levels of Settings; with Shizuku running, GameCore can
 * set them directly, and without it the app deep-links to the right Settings screen and
 * explains what to tap.
 *
 * WRITE_SECURE_SETTINGS is the different one, and the difference is worth stating
 * precisely because it is the reason the colour feature exists at all. It is declared
 * `signature|privileged|development`; the `development` flag is what makes `pm grant`
 * from a uid-2000 shell succeed, which is the same mechanism as `adb shell pm grant`.
 * There is no Settings page for it and no appop behind it, so [appOp] is null and a
 * device without Shizuku cannot be walked to a screen that grants it — the app says so
 * rather than offering a button that cannot work.
 *
 * Nothing here can be aimed at another package.
 */
enum class SelfGrantablePermission(
    val androidName: String,
    val userLabel: String,
    /** The op behind it, for the versions where `pm grant` refuses. Null when there is none. */
    val appOp: SelfAppOp? = null,
) {
    PACKAGE_USAGE_STATS(
        "android.permission.PACKAGE_USAGE_STATS", "Usage access", SelfAppOp.GET_USAGE_STATS,
    ),
    WRITE_SETTINGS(
        "android.permission.WRITE_SETTINGS", "Modify system settings", SelfAppOp.WRITE_SETTINGS,
    ),
    WRITE_SECURE_SETTINGS("android.permission.WRITE_SECURE_SETTINGS", "Change secure settings"),
}

/** The app-ops behind those two permissions, for the versions where `pm grant` will not do it. */
enum class SelfAppOp(val opName: String, val userLabel: String) {
    GET_USAGE_STATS("android:get_usage_stats", "Usage access"),
    WRITE_SETTINGS("android:write_settings", "Modify system settings"),
}

/**
 * The system properties GameCore reads, all three of them identification only.
 *
 * They answer one question the app genuinely needs answered — is this a MediaTek
 * platform, where the standard `Surface.setFrameRate`/`WindowManager` refresh-rate
 * path is known to be accepted and then ignored — so that the display controller
 * knows to verify the result against the platform's own dump rather than trusting
 * that its request was honoured. Reading them changes nothing; `setprop` is not
 * reachable from anywhere in this app.
 */
enum class ReadableProperty(val key: String) {
    BOARD_PLATFORM("ro.board.platform"),
    HARDWARE("ro.hardware"),
    CHIPNAME("ro.hardware.chipname"),
}
