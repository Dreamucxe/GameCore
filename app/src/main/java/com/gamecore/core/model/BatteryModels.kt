package com.gamecore.core.model

import com.gamecore.core.common.Observed

/**
 * One battery observation.
 *
 * [levelPercent] and the charging state come from the sticky
 * `ACTION_BATTERY_CHANGED` broadcast and are always present. Everything else is
 * optional in the platform's own contract — a device that does not implement a
 * property returns 0, -1 or `Integer.MIN_VALUE` rather than omitting the extra — so
 * each field is validated against a physically plausible range before it is reported
 * and is otherwise absent.
 *
 * There is no "time remaining" field, deliberately. Deriving one from an
 * instantaneous current reading would be invention: the figure would swing wildly
 * with whatever the game happened to be doing in that second, and a user reading
 * "47 minutes left" would reasonably believe GameCore had measured something.
 * [BatteryDrain] is the honest alternative — a rate observed over a real interval.
 */
data class BatteryReading(
    val levelPercent: Int,
    val isCharging: Boolean,
    val chargingSource: ChargingSource,
    val status: BatteryStatus,
    val health: Observed<BatteryHealth>,
    /** Tenths of a degree Celsius. The most widely available temperature on Android. */
    val temperatureDeciCelsius: Observed<Int>,
    val voltageMilliVolts: Observed<Int>,
    /**
     * Microamps, sign convention unspecified across OEMs. Reported as a magnitude with
     * [isCharging] giving the direction, and omitted entirely when the magnitude is
     * outside what a phone can physically draw.
     */
    val currentMicroAmps: Observed<Int>,
    val isPowerSaveMode: Boolean,
) {
    companion object {
        /** Nothing readable — no battery service, or a device with no battery. */
        val EMPTY = BatteryReading(
            levelPercent = 0,
            isCharging = false,
            chargingSource = ChargingSource.UNKNOWN,
            status = BatteryStatus.UNKNOWN,
            health = Observed.Failed("Battery state could not be read"),
            temperatureDeciCelsius = Observed.Failed("Battery state could not be read"),
            voltageMilliVolts = Observed.Failed("Battery state could not be read"),
            currentMicroAmps = Observed.Failed("Battery state could not be read"),
            isPowerSaveMode = false,
        )
    }
}

enum class BatteryStatus(val label: String) {
    CHARGING("Charging"),
    DISCHARGING("Discharging"),
    FULL("Full"),
    NOT_CHARGING("Not charging"),
    UNKNOWN("Unknown"),
}

enum class ChargingSource(val label: String) {
    NONE("Not plugged in"),
    AC("AC charger"),
    USB("USB"),
    WIRELESS("Wireless"),
    DOCK("Dock"),
    UNKNOWN("Unknown"),
}

enum class BatteryHealth(val label: String) {
    GOOD("Good"),
    OVERHEAT("Overheating"),
    DEAD("Dead"),
    OVER_VOLTAGE("Over voltage"),
    COLD("Cold"),
    UNSPECIFIED_FAILURE("Failure"),
}

/**
 * Battery consumption measured across a session, expressed as a rate.
 *
 * Percentage per hour is the only form in which a twenty-minute session can honestly
 * be compared with a two-hour one. It is also the form that goes wrong most easily:
 * Android reports whole percentage points, so a short session sees one point of
 * quantisation noise and extrapolating it produces an absurd figure. [isReliable]
 * exists so the UI can show the measurement and withhold the extrapolation, rather
 * than the app quietly choosing between them.
 */
data class BatteryDrain(
    val startPercent: Int,
    val endPercent: Int,
    val elapsedMillis: Long,
    /** True while the device was plugged in at any point, which invalidates the rate. */
    val wasCharging: Boolean,
) {
    val pointsLost: Int get() = (startPercent - endPercent).coerceAtLeast(0)

    /**
     * Percent per hour, or null when it cannot be stated honestly: too short an
     * interval, or a charger was connected during it.
     */
    val percentPerHour: Float?
        get() {
            if (!isReliable) return null
            val hours = elapsedMillis / 3_600_000.0
            if (hours <= 0.0) return null
            return (pointsLost / hours).toFloat()
        }

    /**
     * Whether a rate may be quoted at all.
     *
     * Five minutes is the floor, because below it a single percentage point of
     * quantisation dominates: one point lost over three minutes extrapolates to 20%
     * per hour, and the same session losing that point one second later reads as 0%.
     */
    val isReliable: Boolean
        get() = !wasCharging && elapsedMillis >= MIN_RELIABLE_MILLIS

    companion object {
        const val MIN_RELIABLE_MILLIS = 5 * 60 * 1000L
    }
}
