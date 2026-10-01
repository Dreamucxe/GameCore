package com.gamecore.core.model

import com.gamecore.core.common.TextSanitizer

/**
 * Everything in Settings that is not a profile, a layout or a preset.
 *
 * One data class rather than a bag of loose keys, because almost every screen needs several of
 * these at once and a `Flow` per key would mean a screen recomposing five times for one change.
 * The store reads them all out of one encrypted file in one pass.
 *
 * Nothing here is a capability. [allowElevatedReads] is the user's permission for GameCore to use
 * Shizuku when it is there, not a claim that it is there; [autoApplyProfiles] is a preference, not
 * a statement that usage access has been granted. Conflating the two is how an app ends up with a
 * settings screen that promises behaviour the device will not perform.
 */
data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val accent: AccentChoice = AccentChoice.CYAN,
    val uiScalePercent: Int = 100,
    val useDynamicColour: Boolean = false,

    /**
     * Use [customAccentArgb] instead of one of the eight [AccentChoice] swatches.
     *
     * A flag rather than a magic enum entry: the palette stays a clean, ordered eight, and "custom" is a
     * mode on top of it. When true and [customAccentArgb] is a usable colour, the theme builds from that;
     * when true but no custom colour has been picked yet, the theme falls back to [accent] — never to a
     * broken or invisible accent. [useDynamicColour] still wins over both where the platform supplies it.
     */
    val useCustomAccent: Boolean = false,

    /**
     * The user's chosen custom accent as an ARGB int, or null when none has been picked.
     *
     * Stored as a value the theme layer can read without a `Context`, exactly like [AccentChoice.argb].
     * The alpha byte is forced opaque in [normalised] so a half-transparent pick cannot make text on the
     * accent unreadable, and a null here with [useCustomAccent] on simply falls back to the palette accent.
     */
    val customAccentArgb: Int? = null,

    /**
     * Tighten between-element spacing across the app (§3 "Compact density option").
     *
     * Off by default. When on, the section and card gaps shrink by [Density.COMPACT_FACTOR] while inner
     * padding and the 48.dp touch-target floor are untouched — a denser layout, not a cramped one.
     */
    val compactDensity: Boolean = false,

    /**
     * Whether the app animates, and whether it defers to the system's animator scale (§2 motion).
     *
     * [AnimationsMode.SYSTEM] (the default) multiplies our 150–250 ms tokens by the device's
     * `ANIMATOR_DURATION_SCALE`, so "reduce motion" in accessibility or Developer options genuinely reduces
     * ours — including to zero. [AnimationsMode.OFF] forces scale 0 regardless of the system; [ON] forces a
     * 1× scale even if the system is at 0, for a user who wants GameCore's motion without changing a global.
     */
    val animations: AnimationsMode = AnimationsMode.SYSTEM,

    /**
     * Global haptics switch (§3). Off means no view performs a haptic, whatever it would otherwise do.
     *
     * A preference, not a capability: it cannot conjure a vibrator the device lacks, and a caller still
     * checks for one. It exists because a constant tick on every toggle is exactly the kind of thing a user
     * turns off once and expects to stay off everywhere, overlay included.
     */
    val hapticsEnabled: Boolean = true,

    /** Whether GameCore may use the elevated shell for reads and writes when it is available. */
    val allowElevatedReads: Boolean = true,

    /** Detect game launches and apply the matching profile without asking. */
    val autoApplyProfiles: Boolean = true,

    /**
     * Whether the §B7 one-time resolution-override note is still armed.
     *
     * A preference, not a capability — it says nothing about whether the elevated shell can change a
     * resolution, only whether the editor should explain what doing so means before the first time. True
     * on a fresh install; the editor flips it false the moment the user continues past the note, which is
     * what makes it "one-time". Exposed in Settings so it is revocable: turning it back on re-arms the note,
     * the same way [showThermalWarnings] and [confirmBeforeDiscard] are plain on/off warnings the user owns.
     *
     * Global rather than per-profile because the thing being explained — what a system-level resolution
     * change is, and that it is not a guaranteed frame-rate win — is the same for every game, so a user who
     * has read it once for one game has read it for all of them.
     */
    val showResolutionOverrideNotice: Boolean = true,

    /**
     * Whether the §A2 one-time config-edit disclaimer is still armed.
     *
     * A preference, not a capability — it says nothing about whether the elevated shell can reach a game's
     * sandbox, only whether the config editor should explain what editing a game's own files means before
     * the first time. True on a fresh install; the editor flips it false the moment the user continues past
     * the notice, which is what makes it "one-time". Exposed in Settings so it is revocable: turning it back
     * on re-arms the notice, exactly like [showResolutionOverrideNotice] and the plain warnings the user owns.
     *
     * Global rather than per-game because the thing being explained — that editing a config file can corrupt
     * a save or make a game misbehave, and that GameCore secures one untouched original first and can restore
     * it — is the same for every game, so a user who has read it once has read it for all of them.
     */
    val showConfigEditNotice: Boolean = true,

    /**
     * Whether the §3.6 one-time Instant Replay disclaimer is still armed.
     *
     * A preference, not a capability — it says nothing about whether this device can hold a
     * `MediaProjection`, only whether the editor should explain what a rolling video buffer keeps before the
     * first time one is switched on. True on a fresh install; the editor flips it false the moment the user
     * continues past the disclaimer, which is what makes it "one-time". Exposed in Settings so it is
     * revocable: turning it back on re-arms the disclaimer, exactly like [showResolutionOverrideNotice] and
     * [showConfigEditNotice].
     *
     * Global rather than per-profile because the thing being explained — that a buffer is continuously
     * recording video to a private cache and what leaves the device when a clip is saved — is the same for
     * every game, so a user who has read it once for one game has read it for all of them.
     */
    val showInstantReplayNotice: Boolean = true,

    /** Record a session for every game launch that has a profile with tracking on. */
    val trackSessions: Boolean = true,

    /** How often the detection service checks which app is in front. */
    val detectionIntervalMillis: Long = DEFAULT_DETECTION_INTERVAL,

    /** Sampling interval for the monitoring pipeline while a session is being recorded. */
    val sampleIntervalMillis: Long = DEFAULT_SAMPLE_INTERVAL,

    /** Whether to probe network latency at all. It is the one metric that sends packets. */
    val measureLatency: Boolean = true,

    /** Host the latency prober pings. Configurable because the default may be blocked. */
    val latencyHost: String = DEFAULT_LATENCY_HOST,

    val keepScreenOnInGame: Boolean = false,
    val showThermalWarnings: Boolean = true,

    /**
     * Keep sampling while GameCore is closed and no overlay is on screen.
     *
     * Off by default, and the one setting in here that costs battery on its own: it holds a foreground
     * service open for the sole purpose of keeping the sample loop running, so it is the user's decision
     * rather than a default. With it off, sampling still happens for a session, an overlay or an open
     * screen — the loop is shared and lives as long as something reads it.
     */
    val backgroundMonitoring: Boolean = false,

    /** Ask before dropping a session shorter than the model's saveable floor. */
    val confirmBeforeDiscard: Boolean = true,

    val recordingQuality: RecordingQuality = RecordingQuality.BALANCED,

    /** Has the user been shown the one-time explanation of what this app can and cannot do? */
    val hasSeenIntroduction: Boolean = false,

    /**
     * The app version at which the §A setup wizard was last completed, or 0 if it never has been.
     *
     * A version and not a boolean, because a later release can add a feature the wizard should offer an
     * existing user — `decideEntry` compares this against the running version and can ask again for the
     * new part only. 0 rather than null so the value round-trips through `SharedPreferences.getInt`
     * without a separate "is set" key; it is mapped back to null at the
     * [com.gamecore.domain.setup.SetupSignals] boundary, where "never" is the meaningful state.
     */
    val setupCompletedVersion: Int = 0,

    /**
     * Whether the user dismissed the setup wizard and its Home card for good.
     *
     * Separate from [setupCompletedVersion] because skipping is not finishing: a user who dismisses has
     * told GameCore to stop asking, and that answer has to survive the version bump that would otherwise
     * bring the card back. Setup stays reachable from Settings either way — this silences the prompt, it
     * does not remove the feature.
     */
    val setupDismissed: Boolean = false,

    /**
     * Packages the launch-time memory reclaim must never close, whatever state they are in.
     *
     * The user's own list, on top of the protections
     * [com.gamecore.domain.memory.ReclaimFilter] applies without being asked. It exists because
     * GameCore cannot know that the app in the background is a heart-rate monitor, or a recorder
     * mid-take, or a download the user is waiting on — the filter can only see what Android says
     * the process is doing, and a cached process that matters is indistinguishable from one that
     * does not.
     *
     * Additive only. Nothing the user puts here makes the reclaim close something it otherwise
     * would not, so a malformed entry costs the feature nothing and is dropped on read.
     */
    val neverKillPackages: List<String> = emptyList(),

    /**
     * Packages whose §4 "suggested profile" card the user dismissed, so it never nags again.
     *
     * Held to the same package-name validation as [neverKillPackages] in [normalised], so a hand-edited
     * file cannot smuggle arbitrary text through it. Additive: an entry only ever silences a suggestion, so
     * a malformed one costs nothing and is dropped on read. A game is offered a suggestion again only while
     * it is absent from this set.
     */
    val suggestionDismissals: Set<String> = emptySet(),

    /**
     * The shortcut that opens GameCore's panel, and how it is fired.
     *
     * Off by default, and nested rather than flattened because the fields only make sense together:
     * a sensitivity belongs to a shake, a press window belongs to a key combination, and neither
     * means anything while [QuickTriggerSettings.enabled] is false.
     */
    val quickTrigger: QuickTriggerSettings = QuickTriggerSettings(),

    /**
     * Whether the Aim Lab feature is available.
     *
     * On by default. When off, Aim Lab is hidden from navigation and none of its components — sensors,
     * training loops, timers — are ever initialised: a genuine feature disable, not a hidden UI. The rest
     * of GameCore is unaffected either way.
     */
    val aimLabEnabled: Boolean = true,

    /**
     * The screen orientation Aim Lab training and HUD-editor screens request (§landscape).
     *
     * Applied via `requestedOrientation` only while such a screen is on top, and the previous orientation
     * is restored on exit — the rest of GameCore is never affected. Landscape is the default because the
     * 3D arena reads best wide. Stored as the [AimLabOrientation] name.
     */
    val aimLabOrientation: AimLabOrientation = AimLabOrientation.LANDSCAPE,

    /**
     * The horizontal field of view for the 3D training camera, in degrees (§camera).
     *
     * The vertical FOV is derived from the real aspect ratio at draw time, so aim feel matches in both
     * orientations. Clamped to [AIMLAB_FOV_MIN]..[AIMLAB_FOV_MAX] in [normalised].
     */
    val aimLabHorizontalFovDegrees: Int = AIMLAB_FOV_DEFAULT,

    /**
     * The Scout zoom factor (§Scout), in tenths, so a whole-number preference maps to a 0.1× step: 25 is
     * 2.5×, the loupe's own default. The zoom draws a centre crop into a corner pane, never touching the
     * game. Clamped to [SCOUT_ZOOM_MIN_TENTHS]..[SCOUT_ZOOM_MAX_TENTHS] in [normalised].
     */
    val scoutZoomTenths: Int = SCOUT_ZOOM_DEFAULT_TENTHS,

    /**
     * The Scout dark-scene brightness lift (§Scout), 0–100 percent, applied to the zoomed crop only via a
     * colour matrix — the magnifier has no such control. Zero is a faithful crop; higher values raise
     * shadow detail in a dim scene. Clamped to 0..[SCOUT_LIFT_MAX] in [normalised].
     */
    val scoutLiftPercent: Int = 0,

    /**
     * Whether the hunting filter (§Hunt) was left on. The persisted record of the overlay's on/off, the
     * same role [SecurePreferenceStore.showCrosshairOverlay] plays for the crosshair, read back by
     * [com.gamecore.domain.overlay.OverlayController.restoreManualState] — which only restores it when the
     * saved [huntFilter] needs no capture.
     */
    val huntEnabled: Boolean = false,

    /** Which grade the hunting filter draws (§Hunt). Stored by name; falls to [HuntFilter.DEFAULT] on read. */
    val huntFilter: HuntFilter = HuntFilter.DEFAULT,

    /**
     * Whether the high-sensitivity wheels feel-guide (§Wheels) was left on. The persisted on/off for the
     * ring overlay, restored plainly on the next launch because — unlike Scout and the capture grades — it
     * reads nothing and needs no projection.
     */
    val wheelGuideEnabled: Boolean = false,

    /**
     * The wheels guide ring's radius (§Wheels) as a percentage of the shorter screen edge, so it scales
     * across displays. Clamped to [WHEEL_RADIUS_MIN]..[WHEEL_RADIUS_MAX] in [normalised].
     */
    val wheelGuideRadiusPercent: Int = WHEEL_RADIUS_DEFAULT,

    /**
     * The gain the wheels remap applies inside GameCore's own Aim Lab surface (§Wheels), as a percentage
     * where 100 is neutral: a value above 100 makes a given stick displacement turn faster. It never
     * touches another app — the guide overlay is feel-only and the remap is confined to GameCore's training
     * surface. Clamped to [WHEEL_SENSITIVITY_MIN]..[WHEEL_SENSITIVITY_MAX] in [normalised].
     */
    val wheelSensitivityPercent: Int = WHEEL_SENSITIVITY_DEFAULT,

    /**
     * Whether the advanced HUD stats (GPU usage, §3) are on offer.
     *
     * On by default. When off, the advanced readouts are not offered in the HUD editor or the pill's stat
     * picker — a genuine feature disable rather than a greyed control. A boolean, so [normalised] leaves it
     * untouched.
     */
    val advancedHudEnabled: Boolean = true,

    /**
     * Whether the Network Stability feature is available.
     *
     * On by default. When off, Network Stability is hidden from navigation and its controller never composes
     * the readers behind it, so nothing is sampled for it — the rest of GameCore is unaffected either way,
     * exactly like [aimLabEnabled].
     */
    val networkStabilityEnabled: Boolean = true,

    /**
     * Whether the custom modules feature is available.
     *
     * On by default. When off, the custom modules are hidden and never initialised — a genuine feature
     * disable, not a hidden UI, in the same shape as [aimLabEnabled]. A boolean, so [normalised] leaves it
     * untouched.
     */
    val customModulesEnabled: Boolean = true,

    /**
     * Whether Screen Extraction is available (§3.7).
     *
     * On by default. When off, the feature is hidden from navigation and its ViewModel never asks for a
     * `MediaProjection`, so nothing is captured — a genuine feature disable, not a greyed control, in the
     * same shape as [aimLabEnabled]. A boolean, so [normalised] leaves it untouched. It is a preference,
     * not a capability: it says nothing about whether this device can hold a projection.
     */
    val screenExtractionEnabled: Boolean = true,

    /**
     * Whether the Touch Sampling Monitor is available (§3.7).
     *
     * On by default. When off, the monitor screen is hidden and its capture pad is never attached, so no
     * pointer stream is read. A preference, not a capability — it never claims the panel's true sampling
     * rate, only whether the feature is offered. A boolean, so [normalised] leaves it untouched.
     */
    val touchSamplingEnabled: Boolean = true,

    /**
     * The global master switch for the §3.7 Volume Button Point Trigger.
     *
     * On by default, but inert until a per-game [com.gamecore.core.model.VolumeTriggerConfig] is assigned
     * and both the accessibility service and Shizuku are available — this switch only says the feature is
     * offered, never that the device can inject a tap. When off, the volume keys are never intercepted on
     * the trigger path, so normal volume control is untouched. A boolean, so [normalised] leaves it alone.
     */
    val volumePointTriggerEnabled: Boolean = true,

    /**
     * Whether the §3.7.1 dock customisation screen is offered (feature 4).
     *
     * On by default. When off, the customisation screen is hidden from Settings and the dock draws its
     * shipped arrangement — the saved order and hidden set are *kept*, not cleared, so switching it back on
     * returns the user to the dock they built rather than to a default one. A preference, not a capability:
     * it says nothing about whether the device can draw an overlay. A boolean, so [normalised] leaves it
     * untouched, in the same shape as [aimLabEnabled].
     */
    val dockCustomizationEnabled: Boolean = true,

    /**
     * Whether the dock's quick actions are offered at all (feature 5).
     *
     * On by default. When off, the dock panel draws only its toggle grid and no action chips, and the
     * customisation screen says why rather than showing an inert action list — a genuine feature disable,
     * not a greyed control. Separate from [dockCustomizationEnabled] because they are different decisions:
     * a user can want a rearranged dock without one-tap commands on it, or the commands without the
     * rearranging. A boolean, so [normalised] leaves it untouched.
     */
    val quickActionsEnabled: Boolean = true,

    /**
     * The app [versionCode] whose What's New card the user has already seen, or 0 if none (§3.7 Updates).
     *
     * A version and not a boolean, for the same reason as [setupCompletedVersion]: the one-time startup
     * card must show exactly once per feature-bearing update, so the gate compares this against the running
     * [com.gamecore.BuildConfig.VERSION_CODE]. 0 rather than null so it round-trips through
     * `SharedPreferences.getInt` without a separate "is set" key; a genuine fresh install seeds it to the
     * current version so the card never appears retroactively on the very first launch.
     */
    val lastSeenWhatsNewVersionCode: Int = 0,
) {
    /**
     * Clamps every numeric field into a range the rest of the app can rely on.
     *
     * Called on read as well as on write. A value read back from a file that was edited by hand,
     * or written by a version with different bounds, is not trusted just because it came out of
     * storage. [neverKillPackages] is held to the same rule: every entry is validated as a package
     * name here, so a hand-edited file cannot put arbitrary text in front of the user or into an
     * argument list, and duplicates collapse.
     */
    fun normalised(): AppSettings = copy(
        uiScalePercent = uiScalePercent.coerceIn(MIN_UI_SCALE, MAX_UI_SCALE),
        detectionIntervalMillis = detectionIntervalMillis
            .coerceIn(MIN_DETECTION_INTERVAL, MAX_DETECTION_INTERVAL),
        sampleIntervalMillis = sampleIntervalMillis
            .coerceIn(MIN_SAMPLE_INTERVAL, MAX_SAMPLE_INTERVAL),
        latencyHost = latencyHost.trim().ifBlank { DEFAULT_LATENCY_HOST },
        neverKillPackages = neverKillPackages
            .mapNotNull { TextSanitizer.validatePackageName(it) }
            .distinct()
            .take(MAX_NEVER_KILL_ENTRIES),
        suggestionDismissals = suggestionDismissals
            .mapNotNull { TextSanitizer.validatePackageName(it) }
            .toSet(),
        quickTrigger = quickTrigger.normalised(),
        aimLabHorizontalFovDegrees = aimLabHorizontalFovDegrees.coerceIn(AIMLAB_FOV_MIN, AIMLAB_FOV_MAX),
        scoutZoomTenths = scoutZoomTenths.coerceIn(SCOUT_ZOOM_MIN_TENTHS, SCOUT_ZOOM_MAX_TENTHS),
        scoutLiftPercent = scoutLiftPercent.coerceIn(0, SCOUT_LIFT_MAX),
        wheelGuideRadiusPercent = wheelGuideRadiusPercent.coerceIn(WHEEL_RADIUS_MIN, WHEEL_RADIUS_MAX),
        wheelSensitivityPercent = wheelSensitivityPercent
            .coerceIn(WHEEL_SENSITIVITY_MIN, WHEEL_SENSITIVITY_MAX),
        // Force the custom accent opaque. A pick that arrived with a transparent (or partly transparent)
        // alpha byte — from a hand-edited file, or a picker that let alpha through — would make text drawn
        // on the accent unreadable; the accent is always a solid fill, so the alpha is not the user's to set.
        customAccentArgb = customAccentArgb?.let { it or ALPHA_OPAQUE },
    )

    companion object {
        const val MIN_UI_SCALE = 85
        const val MAX_UI_SCALE = 130

        /**
         * Two seconds between foreground checks.
         *
         * `UsageStatsManager` queries are not free — each one asks system_server to scan an event
         * window — and a game launch being noticed two seconds late costs nothing, because the
         * game is still loading. Below a second the polling itself would show up in the very CPU
         * figures this app reports.
         */
        const val DEFAULT_DETECTION_INTERVAL = 2_000L
        const val MIN_DETECTION_INTERVAL = 1_000L
        const val MAX_DETECTION_INTERVAL = 15_000L

        const val DEFAULT_SAMPLE_INTERVAL = 2_000L
        const val MIN_SAMPLE_INTERVAL = 1_000L
        const val MAX_SAMPLE_INTERVAL = 30_000L

        /**
         * The default latency target.
         *
         * A DNS resolver rather than a game server: GameCore has no idea which server the game in
         * front is talking to, and pretending a figure measured against one host is "your ping in
         * this match" would be inventing a number. The HUD labels this as network latency to a
         * reference host, which is what it is.
         */
        const val DEFAULT_LATENCY_HOST = "1.1.1.1"

        /**
         * A ceiling on the never-close list, so that a corrupted file cannot turn one preference
         * into a set the filter walks for every candidate. Far above any plausible real list.
         */
        const val MAX_NEVER_KILL_ENTRIES = 200

        /** Aim Lab 3D camera horizontal FOV bounds and default (§camera). */
        const val AIMLAB_FOV_MIN = 60
        const val AIMLAB_FOV_MAX = 120
        const val AIMLAB_FOV_DEFAULT = 90

        /** Scout zoom bounds and default, in tenths of a factor (§Scout): 2.0×–8.0×, default 2.5×. */
        const val SCOUT_ZOOM_MIN_TENTHS = 20
        const val SCOUT_ZOOM_MAX_TENTHS = 80
        const val SCOUT_ZOOM_DEFAULT_TENTHS = 25

        /** Ceiling on the Scout dark-scene lift, in percent (§Scout). Floor is a plain 0. */
        const val SCOUT_LIFT_MAX = 100

        /** Wheels guide ring radius bounds and default, as a percent of the shorter screen edge (§Wheels). */
        const val WHEEL_RADIUS_MIN = 8
        const val WHEEL_RADIUS_MAX = 40
        const val WHEEL_RADIUS_DEFAULT = 22

        /** Wheels remap gain bounds and default, in percent where 100 is neutral (§Wheels). */
        const val WHEEL_SENSITIVITY_MIN = 50
        const val WHEEL_SENSITIVITY_MAX = 300
        const val WHEEL_SENSITIVITY_DEFAULT = 150

        /** OR-mask that forces an ARGB int fully opaque, used to keep the custom accent readable. */
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
    }
}

/**
 * Whether GameCore animates, and how it treats the system's animator duration scale (§2 motion).
 *
 * Stored by name so a build that never heard of a value falls back to [SYSTEM] on read. The name is the
 * stable key; the [label] is display text only.
 */
enum class AnimationsMode(val label: String) {
    /**
     * Multiply our 150–250 ms tokens by the device's `ANIMATOR_DURATION_SCALE`. The default: a user who has
     * dialled motion down (accessibility, Developer options) gets less of ours too, down to none at 0×.
     */
    SYSTEM("Follow system"),

    /** Always animate at 1×, even if the system scale is 0. For motion GameCore-side without a global change. */
    ON("On"),

    /** Never animate. Forces scale 0 regardless of the system setting. */
    OFF("Off"),
    ;

    companion object {
        fun fromName(name: String?): AnimationsMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/**
 * The orientation an Aim Lab training or HUD-editor screen requests (§landscape).
 *
 * Applied only while such a screen is on top, via `requestedOrientation`, and undone on exit — the rest
 * of GameCore keeps whatever orientation it had. Landscape is sensor-based so both landscape directions
 * work; the others lock. Name is the stable stored key.
 */
enum class AimLabOrientation(val label: String) {
    LANDSCAPE("Landscape"),
    REVERSE_LANDSCAPE("Reverse landscape"),
    PORTRAIT("Portrait"),
    AUTO("Auto-rotate"),
    ;

    companion object {
        fun fromName(name: String?): AimLabOrientation = entries.firstOrNull { it.name == name } ?: LANDSCAPE
    }
}

/**
 * Dark-first, as §27 asks, but the user's system setting wins by default.
 *
 * [AMOLED] is a true-black variant of dark (§3): a pure `#000000` background so an OLED panel switches
 * those pixels off entirely, with near-black surfaces separated by outlines rather than by a lighter
 * fill. Stored by name; an older build that never heard of it falls back to the default on read.
 */
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow system"),
    DARK("Dark"),
    LIGHT("Light"),
    AMOLED("AMOLED black"),
}

/**
 * Accent colours as ARGB literals rather than resource ids.
 *
 * The theme is built in Compose from these values, so an accent is a value the settings store can
 * round-trip by name and the theme layer can read without a `Context`.
 */
enum class AccentChoice(val label: String, val argb: Int) {
    CYAN("Cyan", 0xFF00E5FF.toInt()),
    GREEN("Green", 0xFF4CE07A.toInt()),
    AMBER("Amber", 0xFFFFB300.toInt()),
    VIOLET("Violet", 0xFF9C6BFF.toInt()),
    ROSE("Rose", 0xFFFF5A87.toInt()),
    BLUE("Blue", 0xFF4C8DFF.toInt()),
    TEAL("Teal", 0xFF2BD4C0.toInt()),
    ORANGE("Orange", 0xFFFF8A4C.toInt()),
    ;

    companion object {
        /**
         * The fixed palette the swatch row offers (§3: "a fixed palette of 8"). Exactly the eight entries
         * above; the custom colour is not one of these — it lives in [AppSettings.customAccentArgb] and is
         * chosen with [useCustomAccent], so the palette stays a stable, ordered eight.
         */
        val PALETTE: List<AccentChoice> = entries
    }
}
