package com.gamecore.core.model

import com.gamecore.core.common.TextSanitizer

/**
 * The GameCore High Sensitivity Wheel: a real virtual joystick that injects its own touch stream.
 *
 * This is deliberately *not* the older feel-only guide ring. The guide is still a legitimate feature and
 * still lives behind [AppSettings.wheelGuideEnabled]; this model is the injected control that sits on top
 * of it. A touch that lands inside the wheel is consumed by GameCore's overlay, processed by
 * [com.gamecore.core.wheel.WheelTouchProcessor], and re-emitted into the game as a pointer whose movement
 * is amplified by the profile below. The game never learns that its finger was reshaped.
 *
 * The model is Android-free on purpose. The service, the settings screen and the JVM tests all construct
 * these values, and none of them should need a `Context` to answer "what is 1.8× of 37 px".
 */
data class WheelProfile(
    /** Stable identity used by [WheelConfig.activeProfileId] and the per-game bindings. */
    val id: String = DEFAULT_ID,
    val name: String = DEFAULT_NAME,
    /** The uniform multiplier, 1.0×–4.0×. Applied after the response curve and before the radius clamp. */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /** Independent axis gains, multiplied into [sensitivity]. 1.0 leaves an axis untouched. */
    val xSensitivity: Float = 1f,
    val ySensitivity: Float = 1f,
    /** How far the injected pointer may travel from the gesture's anchor, in screen pixels. */
    val radiusPx: Int = DEFAULT_RADIUS_PX,
    /** Movement below this many pixels from the anchor is ignored, so a resting thumb does not drift. */
    val deadzonePx: Int = DEFAULT_DEADZONE_PX,
    /** The drawn wheel's diameter in dp. A visual, not the clamp radius — the radius is the maths. */
    val diameterDp: Int = DEFAULT_DIAMETER_DP,
    /** Drawn opacity at rest, 0.25–1. A game-mode reduction is applied by the overlay, not stored here. */
    val opacity: Float = DEFAULT_OPACITY,
    /** Low-pass strength, 0–0.9. 0 is immediate; higher values trade a little latency for stability. */
    val smoothing: Float = DEFAULT_SMOOTHING,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
    val curve: WheelResponseCurve = WheelResponseCurve.LINEAR,
    val style: WheelVisualStyle = WheelVisualStyle.HALO,
    val accent: WheelAccent = WheelAccent.CYAN,
    /** Locked wheels cannot be dragged into a new position; they still process touches. */
    val locked: Boolean = false,
    /**
     * The wheel centre as a fraction of the usable display, measured at the centre of the circle.
     * Stored rather than in pixels for the same reason every other floating control stores fractions:
     * a position chosen on a 1080-wide portrait screen is meaningless on a 2400-wide landscape one.
     */
    val xFraction: Float = DEFAULT_X_FRACTION,
    val yFraction: Float = DEFAULT_Y_FRACTION,
    /** Draws the direction vector and the clamp ring during play/calibration. */
    val showVector: Boolean = true,
    /** A short vibration when the pointer crosses into or out of the deadzone. */
    val haptics: Boolean = true,
) {
    /**
     * A profile that is safe to draw and to feed into the processor, whatever was on disk.
     *
     * Ranges are enforced here rather than in each slider for the reason every other model in this package
     * gives: a rule the UI enforces and the store does not is a rule the next caller does not get. A
     * corrupt file and a hand-edited backup both pass through this one method.
     */
    fun normalised(): WheelProfile = copy(
        id = id.trim().ifBlank { DEFAULT_ID }.take(MAX_ID_LENGTH),
        name = TextSanitizer.sanitizeName(name).trim().ifBlank { DEFAULT_NAME }.take(MAX_NAME_LENGTH),
        sensitivity = finiteOr(sensitivity, DEFAULT_SENSITIVITY).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
        xSensitivity = finiteOr(xSensitivity, 1f).coerceIn(MIN_AXIS_GAIN, MAX_AXIS_GAIN),
        ySensitivity = finiteOr(ySensitivity, 1f).coerceIn(MIN_AXIS_GAIN, MAX_AXIS_GAIN),
        radiusPx = radiusPx.coerceIn(RADIUS_RANGE),
        deadzonePx = deadzonePx.coerceIn(DEADZONE_RANGE),
        diameterDp = diameterDp.coerceIn(DIAMETER_RANGE),
        opacity = finiteOr(opacity, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY),
        smoothing = finiteOr(smoothing, DEFAULT_SMOOTHING).coerceIn(MIN_SMOOTHING, MAX_SMOOTHING),
        xFraction = finiteOr(xFraction, DEFAULT_X_FRACTION).coerceIn(0f, 1f),
        yFraction = finiteOr(yFraction, DEFAULT_Y_FRACTION).coerceIn(0f, 1f),
    )

    /** A duplicate under a new identity and name, for the profile list's "Duplicate" action. */
    fun duplicated(newId: String, newName: String): WheelProfile = copy(
        id = newId,
        name = newName,
        locked = false,
    ).normalised()

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "Balanced"

        const val MIN_SENSITIVITY = 1f
        const val MAX_SENSITIVITY = 4f
        const val DEFAULT_SENSITIVITY = 1.8f

        const val MIN_AXIS_GAIN = 0.25f
        const val MAX_AXIS_GAIN = 2f

        const val MIN_RADIUS_PX = 50
        const val MAX_RADIUS_PX = 500
        const val DEFAULT_RADIUS_PX = 200
        val RADIUS_RANGE = MIN_RADIUS_PX..MAX_RADIUS_PX

        const val MIN_DEADZONE_PX = 0
        const val MAX_DEADZONE_PX = 24
        const val DEFAULT_DEADZONE_PX = 2
        val DEADZONE_RANGE = MIN_DEADZONE_PX..MAX_DEADZONE_PX

        const val MIN_DIAMETER_DP = 96
        const val MAX_DIAMETER_DP = 280
        const val DEFAULT_DIAMETER_DP = 120
        val DIAMETER_RANGE = MIN_DIAMETER_DP..MAX_DIAMETER_DP

        const val MIN_OPACITY = 0.05f
        const val MAX_OPACITY = 1f
        const val DEFAULT_OPACITY = 0.82f

        const val MIN_SMOOTHING = 0f
        const val MAX_SMOOTHING = 0.9f
        const val DEFAULT_SMOOTHING = 0f

        /** Low and left, where a left thumb rests in a landscape shooter. */
        const val DEFAULT_X_FRACTION = 0.18f
        const val DEFAULT_Y_FRACTION = 0.68f

        const val MAX_NAME_LENGTH = 32
        const val MAX_ID_LENGTH = 64

        val DEFAULT = WheelProfile()
    }
}

/** How raw displacement is shaped before sensitivity is applied. */
enum class WheelResponseCurve(val label: String, val description: String) {
    LINEAR("Linear", "Movement maps straight through. The default, and the easiest to learn."),
    PROGRESSIVE("Progressive", "Small movements stay precise; large movements accelerate."),
    AGGRESSIVE("Aggressive", "Reaches full throw sooner. Fast, but less precise near the centre."),
    PRECISE("Precise", "Expands the centre of the stick for fine aim at the cost of top-end speed."),
    ;

    /**
     * Maps a normalised throw (0..1) through the curve.
     *
     * The output may exceed 1: [PROGRESSIVE] is `x + x³ / 2`, so a full throw is 1.5 and the following
     * sensitivity/clamp step is what bounds it. That is intentional — the curve is a gain shape, not a
     * limiter, and the one clamp lives in the processor so every curve obeys the same maximum.
     */
    fun shape(normalised: Float): Float {
        val x = normalised.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            PROGRESSIVE -> x + (x * x * x) * 0.5f
            AGGRESSIVE -> 1f - (1f - x) * (1f - x)
            PRECISE -> kotlin.math.sqrt(x)
        }
    }
}

/** The wheel's drawn treatment. Every style uses the same geometry and the same touch area. */
enum class WheelVisualStyle(val label: String) {
    HALO("Halo"),
    ORBIT("Orbit"),
    MINIMAL("Minimal"),
    NEON("Neon"),
}

/** The accent palette. The first entry is the GameCore cyan used by the rest of the overlay layer. */
enum class WheelAccent(val label: String, val argb: Int) {
    CYAN("Cyan", 0xFF35D6E8.toInt()),
    VIOLET("Violet", 0xFF9B7BFF.toInt()),
    LIME("Lime", 0xFF9BE84A.toInt()),
    AMBER("Amber", 0xFFFFC24D.toInt()),
    CRIMSON("Crimson", 0xFFFF5D73.toInt()),
}

/** Which runtime state the wheel is in. Never persisted: a restart always starts [DISABLED]. */
enum class WheelOperatingMode(val label: String) {
    /** No window and no injection. */
    DISABLED("Disabled"),

    /** The wheel is shown and can be dragged. Touches do not reach the game. */
    POSITIONING("Positioning"),

    /** The wheel consumes touches and injects the amplified stream. The normal playing state. */
    GAMING("Gaming"),

    /** Touches are visualised against the saved maths but nothing is injected. */
    CALIBRATION("Calibration"),
    ;

    /** Calibration and positioning consume touches without forwarding them. */
    val capturesTouches: Boolean get() = this == POSITIONING || this == CALIBRATION

    /** True when this mode injects. */
    val injects: Boolean get() = this == GAMING
}

/** What the floating wheel button's tap does. */
enum class WheelButtonStyle(val label: String) {
    ORB("Orb"),
    RING("Ring"),
    MINIMAL("Minimal"),
}

/** The glyph inside the floating wheel button. */
enum class WheelButtonIcon(val label: String) {
    WHEEL("Wheel"),
    GAMEPAD("Gamepad"),
    CROSSHAIR("Crosshair"),
    BOLT("Bolt"),
}

/**
 * The little floating button that arms and disarms the wheel.
 *
 * Separate from [FloatingButtonConfig] because it is a different control with a different job: that one
 * opens GameCore's control panel, this one is the emergency handle for input injection. It is persisted
 * so it comes back after a restart, which is exactly what the emergency-deactivation rule needs — the
 * button must be findable even when the wheel itself is off. Its *pressed* state is not persisted; see
 * [WheelConfig].
 */
data class WheelButtonConfig(
    val visible: Boolean = true,
    val xFraction: Float = DEFAULT_X_FRACTION,
    val yFraction: Float = DEFAULT_Y_FRACTION,
    val sizeDp: Int = DEFAULT_SIZE_DP,
    val opacity: Float = DEFAULT_OPACITY,
    val style: WheelButtonStyle = WheelButtonStyle.ORB,
    val icon: WheelButtonIcon = WheelButtonIcon.WHEEL,
    /** Base colour while disabled/positioning. Error, calibration and gaming retain semantic colours. */
    val accent: WheelAccent = WheelAccent.CYAN,
    val haptics: Boolean = true,
    /** Pulls the handle to the nearer horizontal edge when a drag ends. */
    val snapToEdge: Boolean = true,
    /** Fades towards [idleOpacity] when the wheel is off, so the handle is present but quiet. */
    val idleFade: Boolean = true,
) {
    fun normalised(): WheelButtonConfig = copy(
        xFraction = finiteOr(xFraction, DEFAULT_X_FRACTION).coerceIn(0f, 1f),
        yFraction = finiteOr(yFraction, DEFAULT_Y_FRACTION).coerceIn(0f, 1f),
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        opacity = finiteOr(opacity, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY),
    )

    /** Opacity while the wheel is off: never below a findable floor. */
    val idleOpacity: Float
        get() = if (idleFade) (opacity * 0.45f).coerceAtLeast(0.18f) else opacity

    companion object {
        const val MIN_SIZE_DP = 48
        const val MAX_SIZE_DP = 72
        const val DEFAULT_SIZE_DP = 52
        val SIZE_RANGE = MIN_SIZE_DP..MAX_SIZE_DP

        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1f
        const val DEFAULT_OPACITY = 0.9f

        /** Upper right, out of a left thumb's way. */
        const val DEFAULT_X_FRACTION = 0.9f
        const val DEFAULT_Y_FRACTION = 0.28f

        val DEFAULT = WheelButtonConfig()
    }
}

/**
 * Everything durable about the wheel: the profile library, which one is active, the per-game bindings
 * and the floating button.
 *
 * **The running state is not in here.** There is deliberately no `enabled` flag and no saved
 * [WheelOperatingMode]. Starting GameCore again must never restore a state in which GameCore is injecting
 * touches into another app; the user arms the wheel each session with the floating button or the screen's
 * switch. The button's visibility, position and look *are* persisted, because that is how the user finds
 * the handle that disarms it.
 */
data class WheelConfig(
    val profiles: List<WheelProfile> = listOf(WheelProfile.DEFAULT),
    val activeProfileId: String = WheelProfile.DEFAULT_ID,
    val button: WheelButtonConfig = WheelButtonConfig.DEFAULT,
    /**
     * Package name to profile id, for automatic per-game selection. Only applied while the wheel is armed:
     * a binding changes *which* profile is used, never whether injection is on.
     */
    val bindings: Map<String, String> = emptyMap(),
    /** Whether a game being detected switches the active profile to its binding. */
    val autoSwitch: Boolean = false,
    /**
     * The user has read the synthetic-touch warning and chosen to enable the wheel anyway.
     *
     * Durable because a consent prompt on every arm would train the user to tap through it. It gates
     * [com.gamecore.core.wheel.WheelController.engage] and is the only persisted thing about enabling:
     * the operating mode itself is still process-only and always starts DISABLED.
     */
    val riskAcknowledged: Boolean = false,
    /** Runtime precision scales the active profile by this amount; it never rewrites the profile. */
    val precisionMultiplier: Float = DEFAULT_PRECISION_MULTIPLIER,
    /** Runtime turbo scales the active profile by this amount; it never rewrites the profile. */
    val turboMultiplier: Float = DEFAULT_TURBO_MULTIPLIER,
) {
    /** The active profile, falling back to the first saved one if the id no longer exists. */
    val activeProfile: WheelProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull() ?: WheelProfile.DEFAULT

    /**
     * A config safe to draw and to inject with.
     *
     * **Profile identity is repaired, not discarded.** Duplicates keep the first occurrence, blank ids and
     * names are repaired by [WheelProfile.normalised], and a library that somehow became empty gets the
     * shipped default back. Bindings to profiles that no longer exist are dropped rather than carried as a
     * reference that can never resolve. The cap is enforced here too: a hand-edited file cannot turn the
     * profile picker into an unusable list, nor multiply the work the overlay does per frame.
     */
    fun normalised(): WheelConfig {
        val cleaned = profiles
            .map { it.normalised() }
            .distinctBy { it.id }
            .take(MAX_PROFILES)
            .ifEmpty { listOf(WheelProfile.DEFAULT) }
        val ids = cleaned.mapTo(HashSet()) { it.id }
        val safeActive = activeProfileId.takeIf { it in ids } ?: cleaned.first().id
        val safeBindings = bindings
            .mapNotNull { (packageName, profileId) ->
                val safePackage = TextSanitizer.validatePackageName(packageName) ?: return@mapNotNull null
                if (profileId !in ids) return@mapNotNull null
                safePackage to profileId
            }
            .toMap()
        return copy(
            profiles = cleaned,
            activeProfileId = safeActive,
            button = button.normalised(),
            bindings = safeBindings,
            precisionMultiplier = finiteOr(precisionMultiplier, DEFAULT_PRECISION_MULTIPLIER)
                .coerceIn(MIN_PRECISION_MULTIPLIER, MAX_PRECISION_MULTIPLIER),
            turboMultiplier = finiteOr(turboMultiplier, DEFAULT_TURBO_MULTIPLIER)
                .coerceIn(MIN_TURBO_MULTIPLIER, MAX_TURBO_MULTIPLIER),
        )
    }

    /** Replaces one profile in place, keeping its position in the list. */
    fun withProfile(profile: WheelProfile): WheelConfig = copy(
        profiles = profiles.map { if (it.id == profile.id) profile.normalised() else it },
    )

    /** Adds a profile if there is room and the id is unused. Returns this config when it cannot. */
    fun plusProfile(profile: WheelProfile): WheelConfig {
        val safe = profile.normalised()
        if (profiles.size >= MAX_PROFILES || profiles.any { it.id == safe.id }) return this
        return copy(profiles = profiles + safe)
    }

    /** Removes a profile. The last one cannot be removed; the shipped default is restored instead. */
    fun minusProfile(id: String): WheelConfig {
        if (profiles.size <= 1) return this
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.isEmpty()) return this
        val nextActive = if (activeProfileId == id) remaining.first().id else activeProfileId
        return copy(
            profiles = remaining,
            activeProfileId = nextActive,
            bindings = bindings.filterValues { it != id },
        )
    }

    /** The profile bound to [packageName], or null when automatic selection is off or nothing is bound. */
    fun profileFor(packageName: String?): WheelProfile? {
        if (!autoSwitch || packageName.isNullOrBlank()) return null
        val id = bindings[packageName] ?: return null
        return profiles.firstOrNull { it.id == id }
    }

    /** This config with the active profile switched, provided the id names a saved profile. */
    fun withActiveProfile(id: String): WheelConfig =
        if (profiles.any { it.id == id }) copy(activeProfileId = id) else this

    companion object {
        /** More than this turns a quick switch into a scroll through a list nobody memorises. */
        const val MAX_PROFILES = 12

        const val MIN_PRECISION_MULTIPLIER = 0.25f
        const val MAX_PRECISION_MULTIPLIER = 1f
        const val DEFAULT_PRECISION_MULTIPLIER = 0.5f

        const val MIN_TURBO_MULTIPLIER = 1f
        const val MAX_TURBO_MULTIPLIER = 2f
        const val DEFAULT_TURBO_MULTIPLIER = 1.5f

        val DEFAULT = WheelConfig()

        /** The sensitivities the directive names, used by the quick-pick chips. */
        val SENSITIVITIES = listOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f)
    }
}

/**
 * A stored float can be NaN or infinite if a file was hand-edited. `coerceIn` does not repair either, and
 * a NaN reaching the sensitivity maths would produce a NaN touch coordinate that the platform rejects — or
 * worse, accepts. Every float that feeds geometry or drawing passes through this before its range clamp.
 */
private fun finiteOr(value: Float, fallback: Float): Float =
    if (value.isNaN() || value.isInfinite()) fallback else value
