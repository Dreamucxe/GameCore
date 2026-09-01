package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * The device's thermal state.
 *
 * Two independent sources, kept apart because they mean different things:
 *
 *  * [status] is `PowerManager.getCurrentThermalStatus()` — the platform's own
 *    verdict, available from API 29, and the *only* signal that reflects what the
 *    system is actually doing about the heat. It is coarse (six steps) but it is
 *    authoritative.
 *  * [sensors] are raw readings from `/sys/class/thermal`. Precise, and entirely
 *    OEM-defined: zone names differ per chipset and a "cpu" zone on one device is
 *    a "tsens_tz_sensor12" on another.
 *
 * GameCore warns on [status], not on a sensor threshold, because a temperature that
 * is alarming on one phone is normal on the next and only the platform knows which is
 * which. Nothing here can be written: the app has no path to
 * `cmd thermalservice override-status`, which would switch off the protection keeping
 * the device from damaging itself.
 */
data class ThermalReading(
    val status: Observed<ThermalStatus>,
    /** Tenths of a degree Celsius: the hottest CPU-ish zone found, for the graph. */
    val cpuTemperatureDeciCelsius: Observed<Int>,
    val sensors: Observed<List<ThermalSensor>>,
    /** True from API 29, where a throttling status exists to be read at all. */
    val statusSupported: Boolean,
) {
    companion object {
        fun unavailable(detail: String, statusSupported: Boolean) = ThermalReading(
            status = Observed.notPresent(detail),
            cpuTemperatureDeciCelsius = Observed.notPresent(detail),
            sensors = Observed.notPresent(detail),
            statusSupported = statusSupported,
        )
    }
}

/**
 * `PowerManager.THERMAL_STATUS_*`, with what each step means for a game.
 *
 * The wording matters: the platform's own documentation describes these in terms of
 * what the *system* will do, and that is what the user needs told. At
 * [MODERATE] nothing has been taken away yet; from [SEVERE] the system is already
 * clamping clocks, which is the point at which a frame-rate drop is the device
 * protecting itself rather than anything GameCore or the game did.
 */
enum class ThermalStatus(
    val level: Int,
    val label: String,
    val explanation: String,
    /** Whether GameCore raises an alert notification at this level. */
    val warrantsAlert: Boolean,
) {
    NONE(
        0, "Normal",
        "The device is not thermally constrained.",
        false,
    ),
    LIGHT(
        1, "Light",
        "Slight warming. Nothing has been throttled.",
        false,
    ),
    MODERATE(
        2, "Moderate",
        "The device is warm. Background work may be deferred, but foreground " +
            "performance is not being clamped yet.",
        false,
    ),
    SEVERE(
        3, "Severe",
        "The system is now limiting performance to shed heat. A frame-rate drop " +
            "from here is the device protecting itself.",
        true,
    ),
    CRITICAL(
        4, "Critical",
        "Performance is heavily limited. Consider taking a break — the device will " +
            "keep throttling until it cools.",
        true,
    ),
    EMERGENCY(
        5, "Emergency",
        "The device is close to shutting down to protect itself.",
        true,
    ),
    SHUTDOWN(
        6, "Shutdown imminent",
        "A thermal shutdown is imminent.",
        true,
    ),
    ;

    companion object {
        fun fromPlatform(value: Int): ThermalStatus? = entries.firstOrNull { it.level == value }
    }
}

/**
 * One thermal zone. [label] is the zone's own `type` string cleaned up for display —
 * never renamed to something friendlier, because a guess about which physical
 * component a vendor's zone measures would be exactly the kind of invention this app
 * refuses to make.
 */
data class ThermalSensor(
    val label: String,
    val deciCelsius: Int,
    /** True when the zone name suggests a CPU/SoC sensor, which is a heuristic and marked as one. */
    val isCpuZone: Boolean,
)
