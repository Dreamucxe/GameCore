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

    /** Whether GameCore may use the elevated shell for reads and writes when it is available. */
    val allowElevatedReads: Boolean = true,

    /** Detect game launches and apply the matching profile without asking. */
    val autoApplyProfiles: Boolean = true,

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
        quickTrigger = quickTrigger.normalised(),
        aimLabHorizontalFovDegrees = aimLabHorizontalFovDegrees.coerceIn(AIMLAB_FOV_MIN, AIMLAB_FOV_MAX),
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

/** Dark-first, as §27 asks, but the user's system setting wins by default. */
enum class ThemeChoice(val label: String) {
    SYSTEM("Follow system"),
    DARK("Dark"),
    LIGHT("Light"),
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
}
