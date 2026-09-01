package com.gamecore.core.model

/**
 * A HUD layout: a name and the widgets on it.
 *
 * Layouts are per-user data, selectable per game, and edited in a visual builder — so
 * everything here is expressed in terms the builder can manipulate directly and the renderer
 * can draw without interpretation.
 *
 * Positions are **fractions of the screen, not pixels**. A layout built in portrait on a
 * 1080×2400 panel has to land somewhere sensible when the game runs in landscape, and a
 * pixel offset would put a widget off-screen. Fractions rotate correctly and survive the
 * layout being exported from one device and imported on another.
 */
data class HudLayout(
    val id: Long = 0L,
    /** Sanitised before storage: this is user text that gets drawn into an overlay window. */
    val name: String,
    val widgets: List<HudWidget> = emptyList(),
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
) {
    val widgetCount: Int get() = widgets.size

    val isEmpty: Boolean get() = widgets.isEmpty()

    fun withWidget(widget: HudWidget): HudLayout =
        copy(widgets = widgets.filterNot { it.id == widget.id } + widget)

    fun withoutWidget(widgetId: String): HudLayout =
        copy(widgets = widgets.filterNot { it.id == widgetId })

    companion object {
        const val MAX_WIDGETS = 12
        const val MAX_NAME_LENGTH = 40
    }
}

/**
 * One thing drawn on the HUD.
 *
 * [id] is a client-generated string rather than a database id, because the builder creates,
 * drags and deletes widgets before anything is saved and a layout that had to round-trip
 * through Room to give a new widget an identity would not be editable offline in one screen.
 */
data class HudWidget(
    val id: String,
    val stat: HudStat,
    /** 0..1 from the left edge of the screen. */
    val xFraction: Float = 0.05f,
    /** 0..1 from the top edge. */
    val yFraction: Float = 0.05f,
    val textSizeSp: Int = DEFAULT_TEXT_SIZE_SP,
    /** 0..100. Applied to the whole widget, background included. */
    val opacityPercent: Int = 85,
    val showLabel: Boolean = true,
    val showBackground: Boolean = true,
    val colorArgb: Int = DEFAULT_COLOR,
) {
    /** Clamped on the way in, so a bad import cannot place a widget off-screen. */
    fun normalised(): HudWidget = copy(
        xFraction = xFraction.coerceIn(0f, 1f),
        yFraction = yFraction.coerceIn(0f, 1f),
        textSizeSp = textSizeSp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP),
        opacityPercent = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, 100),
    )

    companion object {
        const val DEFAULT_TEXT_SIZE_SP = 12
        const val MIN_TEXT_SIZE_SP = 8
        const val MAX_TEXT_SIZE_SP = 28

        /**
         * A floor, not zero. A fully transparent widget is invisible and undeletable from
         * the overlay, and a user who set it that way by accident has no way back.
         */
        const val MIN_OPACITY_PERCENT = 15

        /** Opaque white; the renderer applies [opacityPercent] on top. */
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    }
}

/**
 * The stats a HUD widget or the performance pill can show.
 *
 * [isAlwaysAvailable] is the honest part. FPS is in this list because the spec asks for it,
 * and it is marked as conditional because on most devices and most games it genuinely cannot
 * be measured — the widget then renders "n/a" with the reason on tap rather than a number.
 * Ping and the network rates are conditional for a different reason: they need a network, and
 * a widget showing "0 ms" on a flight-mode device would be a measurement of nothing.
 */
enum class HudStat(
    val label: String,
    val shortLabel: String,
    val unit: String,
    val isAlwaysAvailable: Boolean,
) {
    CPU_USAGE("CPU usage", "CPU", "%", true),
    CPU_TEMPERATURE("CPU temperature", "CPU°", "°C", false),
    RAM_USAGE("RAM used", "RAM", "%", true),
    RAM_FREE("RAM free", "Free", "MB", true),
    BATTERY_LEVEL("Battery", "BAT", "%", true),
    BATTERY_TEMPERATURE("Battery temperature", "BAT°", "°C", true),
    BATTERY_CURRENT("Battery current", "mA", "mA", false),
    REFRESH_RATE("Refresh rate", "Hz", "Hz", true),
    FRAME_RATE("Frame rate", "FPS", "fps", false),
    NETWORK_LATENCY("Latency", "Ping", "ms", false),
    NETWORK_DOWN("Download", "↓", "KB/s", false),
    NETWORK_UP("Upload", "↑", "KB/s", false),
    STORAGE_FREE("Storage free", "Disk", "GB", true),
    SESSION_DURATION("Session time", "Time", "", true),
    THERMAL_STATUS("Thermal state", "Therm", "", false),
    CLOCK("Clock", "", "", true),
    ;

    /** The default set on a new layout: the four that need nothing and matter most. */
    companion object {
        val DEFAULT_SET = listOf(CPU_USAGE, RAM_USAGE, BATTERY_LEVEL, REFRESH_RATE)
    }
}
