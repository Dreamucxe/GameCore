package com.gamecore.core.shizuku

import com.gamecore.BuildConfig
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.TextSanitizer

/**
 * Every command GameCore can run through an elevated shell, enumerated.
 *
 * [ElevatedShell.execute] takes one of these rather than a string or an argument
 * vector, so there is no expressible way for any caller — including a future one —
 * to run a command that is not written down in this file. The set of things GameCore
 * can do with ADB-level authority is therefore reviewable in one place, and the
 * review is enforced by the compiler rather than by convention.
 *
 * Three kinds of command are deliberately *absent*, and the absence is the point:
 *
 *  * `am force-stop`, `am kill`, `pm trim-caches`. Killing other applications to
 *    free memory is what a fake "RAM booster" does: Android relaunches them
 *    moments later, the user's music stops, and no frame is gained. GameCore has no
 *    path to any of them at any privilege level.
 *  * `cmd thermalservice override-status`. Lying to the thermal service about how
 *    hot the device is switches off the protection that keeps it from damaging
 *    itself. Not exposed for any profile setting, ever.
 *  * `cmd package compile`, and anything else that rewrites another application's
 *    on-disk artefacts. GameCore does not modify games.
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
     * Each is paired with a read of the current value, and the optimizer records
     * that value before writing so the change can be undone when the game exits.
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
     * documented `Settings.System`/`Settings.Global` key, each value is checked
     * against that key's declared form before the command is built, and each is
     * paired with a [GetSetting] read taken beforehand so the write can be undone
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

        fun frameStats(packageName: String): GfxInfoFrameStats? =
            GfxInfoFrameStats.of(packageName)

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

/** The two settings tables GameCore touches. `secure` is not among them. */
enum class SettingsNamespace(val token: String) {
    SYSTEM("system"),
    GLOBAL("global"),
}

/**
 * Every settings key GameCore may write, with the form its value has to take.
 *
 * Two things are collapsed into one list on purpose. Some of these keys are
 * writable by an ordinary app that holds WRITE_SETTINGS (brightness, rotation,
 * screen timeout) and some are not writable without ADB-level authority at all
 * (the refresh-rate bounds, the animation scales, battery saver). Both paths write
 * *the same keys with the same validation*, so the allow-list and the range checks
 * live here once, and [requiredAccess] is what the capability layer reads to tell
 * the user which of the two paths a given profile setting needs on their device.
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
    ;

    abstract fun accepts(value: String): Boolean
}

/**
 * The permissions GameCore may grant to itself with Shizuku.
 *
 * Both are permissions the app declares in its own manifest and neither can be
 * granted by a runtime dialog: usage access and modify-system-settings are appop
 * gates the user normally reaches through two or three levels of Settings. With
 * Shizuku running, GameCore can set them directly; without it, the app deep-links
 * to the right Settings screen and explains what to tap. Nothing here can be aimed
 * at another package.
 */
enum class SelfGrantablePermission(val androidName: String, val userLabel: String) {
    PACKAGE_USAGE_STATS("android.permission.PACKAGE_USAGE_STATS", "Usage access"),
    WRITE_SETTINGS("android.permission.WRITE_SETTINGS", "Modify system settings"),
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
