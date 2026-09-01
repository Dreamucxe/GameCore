package com.gamecore.ui.components

import com.gamecore.core.common.Formatters
import com.gamecore.core.common.isAvailable
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalStatus

/**
 * The rules that turn a device reading into a colour and a sentence.
 *
 * Here rather than on one screen because three screens show the same figures — the dashboard's tiles,
 * the performance screen's rows, and a session report's summary — and a temperature that is amber on one
 * of them and neutral on another is a bug the user notices before any test does. The thresholds are
 * display hints, not claims about what a phone can tolerate, and they exist in one place so they can be
 * argued about in one place.
 */

/**
 * The tile's colour: the platform's throttling status where it exists, the reading itself where it does
 * not.
 *
 * In that order deliberately. A device reporting [ThermalStatus.SEVERE] at 39 °C knows something about
 * its own limits that a threshold does not, and the thresholds below are for the devices that report no
 * status at all — every build before API 29, and a few vendor builds after it.
 */
internal fun temperatureTone(deciCelsius: Int?, thermal: ThermalStatus?): Tone = when {
    thermal != null && thermal.level >= ThermalStatus.CRITICAL.level -> Tone.Danger
    thermal != null && thermal.level >= ThermalStatus.SEVERE.level -> Tone.Warning
    deciCelsius == null -> Tone.Neutral
    deciCelsius >= HOT_DECI_CELSIUS -> Tone.Danger
    deciCelsius >= WARM_DECI_CELSIUS -> Tone.Warning
    else -> Tone.Neutral
}

/**
 * Which sensor the figure came from, and what the platform makes of it.
 *
 * Named rather than implied. A CPU zone reading and a battery reading differ by several degrees on the
 * same device at the same moment, and a tile that says "41 °C" without saying what is 41 °C invites the
 * user to compare it against another app that measured the other thing.
 */
internal fun temperatureDetail(snapshot: PerformanceSnapshot, thermal: ThermalStatus?): String {
    val sensor = if (snapshot.thermal.cpuTemperatureDeciCelsius.isAvailable) {
        "CPU sensor"
    } else {
        "Battery sensor"
    }
    return listOfNotNull(sensor, thermal?.label).joinToString(" · ")
}

internal fun batteryDetail(battery: BatteryReading): String = when {
    battery.isCharging -> "Charging · ${battery.chargingSource.label}"
    battery.isPowerSaveMode -> "Battery saver on"
    else -> battery.status.label
}

internal fun batteryTone(battery: BatteryReading): Tone = when {
    battery.isCharging -> Tone.Good
    battery.levelPercent <= LOW_BATTERY_PERCENT -> Tone.Danger
    battery.levelPercent <= WARNING_BATTERY_PERCENT -> Tone.Warning
    else -> Tone.Neutral
}

/**
 * Amber for a device that is nearly full, red only for one the platform has declared low.
 *
 * `isLowMemory` is the platform's own flag, raised when it has started killing background processes —
 * which is the point at which high memory use is actually costing the user something.
 */
internal fun memoryTone(memory: MemoryReading): Tone = when {
    memory.isLowMemory -> Tone.Danger
    memory.usedFraction >= HIGH_MEMORY_FRACTION -> Tone.Warning
    else -> Tone.Neutral
}

/** What the panel can do, next to what it is doing. The two are never conflated. */
internal fun refreshDetail(display: DisplayReading): String {
    val peak = display.peakRefreshRate
    return when {
        !display.hasMultipleRates -> "Single-rate panel"
        peak != null -> "Panel goes to ${Formatters.hertz(peak)}"
        else -> "Variable rate"
    }
}

/**
 * A capability's status as a colour: green for yes, amber for "not yet, and there is a button", grey for
 * a device that cannot.
 *
 * Grey rather than red for [CapabilityStatus.UNSUPPORTED], because a single-mode panel or a phone without
 * a flash is not a fault and not the user's doing. Colouring it as an error turns a fact about the
 * hardware into something the user will go looking for a fix for.
 */
internal fun capabilityTone(status: CapabilityStatus): Tone = when (status) {
    CapabilityStatus.AVAILABLE -> Tone.Good
    CapabilityStatus.REQUIRES_SHIZUKU, CapabilityStatus.REQUIRES_PERMISSION -> Tone.Warning
    CapabilityStatus.UNSUPPORTED -> Tone.Muted
}

/**
 * Where the temperature reading changes colour, in tenths of a degree.
 *
 * Used only for devices that report no thermal status of their own. Deliberately unalarming: 42 °C is a
 * warm phone under a normal gaming load, not a fault, and a dashboard that shows red at 40 teaches the
 * user to ignore the colour.
 */
internal const val WARM_DECI_CELSIUS = 420
internal const val HOT_DECI_CELSIUS = 460

internal const val LOW_BATTERY_PERCENT = 15
internal const val WARNING_BATTERY_PERCENT = 30
internal const val HIGH_MEMORY_FRACTION = 0.9f
internal const val TIGHT_STORAGE_FRACTION = 0.92f
