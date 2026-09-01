package com.gamecore.core.model

/**
 * The floating performance pill's configuration.
 *
 * Position is stored as pixels rather than fractions, unlike [HudWidget], and the difference
 * is intentional: the pill is dragged by the user in a live overlay and snapped to an edge, so
 * the pixel position *is* what they chose. Rotating the device re-snaps it to the nearest edge
 * rather than trying to preserve a fraction that would put it somewhere they did not put it.
 */
data class OverlayConfig(
    val showPill: Boolean = false,
    val pillX: Int = 0,
    val pillY: Int = 200,
    val stats: List<HudStat> = HudStat.DEFAULT_SET,
    val updateIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    val textSizeSp: Int = 11,
    val opacityPercent: Int = 85,
    val cornerRadiusDp: Int = 12,
    val isVertical: Boolean = false,
    val showLabels: Boolean = true,
) {
    fun normalised(): OverlayConfig = copy(
        stats = stats.distinct().take(MAX_STATS),
        updateIntervalMillis = updateIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS),
        textSizeSp = textSizeSp.coerceIn(TEXT_SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
    )

    companion object {
        /**
         * The bounds, named, because two places need them.
         *
         * The clamp above is the one that matters — a config assembled anywhere is valid after it — but
         * the settings screen's sliders have to span exactly the same span, and a slider whose range was
         * a literal typed a second time is a slider whose top end silently clamps to something else.
         */
        val TEXT_SIZE_RANGE = 8..20
        val OPACITY_RANGE = 20..100
        val CORNER_RANGE = 0..32

        const val DEFAULT_INTERVAL_MILLIS = 1_000L

        /**
         * Half a second is the floor, and it is a real one rather than a round number: every
         * tick is a `/proc` read, a binder call and a recomposition, and below this the pill
         * costs the game more frames than the numbers on it are worth.
         */
        const val MIN_INTERVAL_MILLIS = 500L
        const val MAX_INTERVAL_MILLIS = 10_000L
        const val MAX_STATS = 8
    }
}

/**
 * The floating button's configuration and remembered position.
 *
 * [snapToEdge] is on by default because a button that sits under the user's thumb in the
 * middle of a game is a button that gets pressed by accident. The position is stored raw and
 * the snap applied at layout time, so switching the setting off puts the button back where it
 * was dragged rather than where it was snapped to.
 */
data class FloatingButtonConfig(
    val show: Boolean = true,
    val x: Int = 0,
    val y: Int = 400,
    val sizeDp: Int = 48,
    val opacityPercent: Int = 80,
    val snapToEdge: Boolean = true,
    /** Fades to this while a game is in the foreground and the panel is closed. */
    val idleOpacityPercent: Int = 40,
    val hapticFeedback: Boolean = true,
) {
    fun normalised(): FloatingButtonConfig = copy(
        sizeDp = sizeDp.coerceIn(SIZE_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        idleOpacityPercent = idleOpacityPercent.coerceIn(IDLE_OPACITY_RANGE),
    )

    companion object {
        const val MIN_SIZE_DP = 36
        const val MAX_SIZE_DP = 72

        val SIZE_RANGE = MIN_SIZE_DP..MAX_SIZE_DP

        /** A button below 30% is hard to find; below 10% while idle it is invisible, not subtle. */
        val OPACITY_RANGE = 30..100
        val IDLE_OPACITY_RANGE = 10..100
    }
}

/**
 * Which overlay windows are up, for the dashboard's status row and for the service to
 * reconcile against.
 *
 * A single flag would not do: the overlay service can be running with the button up and the
 * pill down, and a dashboard that showed "overlay: on" would not tell the user why they cannot
 * see their stats.
 */
data class OverlayStatus(
    val serviceRunning: Boolean = false,
    val buttonVisible: Boolean = false,
    val pillVisible: Boolean = false,
    val crosshairVisible: Boolean = false,
    val hudVisible: Boolean = false,
    val hasPermission: Boolean = false,
) {
    val anythingVisible: Boolean
        get() = buttonVisible || pillVisible || crosshairVisible || hudVisible

    val summary: String
        get() = when {
            !hasPermission -> "Overlay permission not granted"
            !serviceRunning -> "Off"
            !anythingVisible -> "Running, nothing shown"
            else -> listOfNotNull(
                "Button".takeIf { buttonVisible },
                "Stats".takeIf { pillVisible },
                "Crosshair".takeIf { crosshairVisible },
                "HUD".takeIf { hudVisible },
            ).joinToString(", ")
        }

    companion object {
        val OFF = OverlayStatus()
    }
}

/**
 * Which overlay windows *should* be up, and with what content.
 *
 * The counterpart to [OverlayStatus]: this is the request, that is the result, and they are separate
 * types because they genuinely disagree — a request for a crosshair on a device where the user has
 * revoked the overlay permission produces a status with nothing visible, and the dashboard has to be
 * able to say so rather than showing a crosshair that is not there.
 *
 * Held by `OverlayController` and rendered by `GamingOverlayService`. A game profile writes it when a
 * game launches and the settings screens write it when the user toggles something by hand, which is
 * why the ids are here: a profile names a crosshair and a HUD layout by id, and the service resolves
 * them. Null means "the one the user last picked", not "none" — that distinction is [crosshair] and
 * [hud].
 */
data class OverlayRequest(
    val button: Boolean = false,
    val pill: Boolean = false,
    val crosshair: Boolean = false,
    val hud: Boolean = false,
    val crosshairPresetId: Long? = null,
    val hudLayoutId: Long? = null,
    /** The game the request came from, shown in the control panel's header. Empty when manual. */
    val gameLabel: String = "",
    /** True while a game profile is driving this, so clearing it can restore the manual state. */
    val fromProfile: Boolean = false,
) {
    val anythingVisible: Boolean get() = button || pill || crosshair || hud

    companion object {
        val NONE = OverlayRequest()
    }
}
