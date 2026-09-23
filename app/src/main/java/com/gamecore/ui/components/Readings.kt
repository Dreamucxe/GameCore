package com.gamecore.ui.components

import com.gamecore.core.common.Formatters
import com.gamecore.core.common.isAvailable
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DisplayReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.model.ThermalClass
import com.gamecore.core.model.ThermalClassifier
import com.gamecore.core.model.ThermalSensorType
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
 * The tile's colour, from the one shared [ThermalClassifier] — the platform's throttling status where it
 * exists, the reading itself where it does not, reconciled in a single computation.
 *
 * This used to be its own `when` over raw thresholds while the *word* beside the figure came from
 * [ThermalStatus.label]; that split is exactly how a tile once showed 85.3 °C in red with the word
 * "Normal". Now the colour comes from the same [ThermalClass] [temperatureDetail] takes its word from, so
 * the two cannot disagree. [WARM] and [HOT] map to the one warning tone and [CRITICAL] to danger, matching
 * the semantic-colour set in `StatusColors.kt` (a device that is hot is cautioned in amber; red is reserved
 * for the point the platform is protecting itself).
 *
 * [sensor] selects the threshold set — a battery at 50 °C is a problem where a SoC at 50 °C is idle-warm —
 * and defaults to [ThermalSensorType.CPU] because most call sites read a SoC-class zone; the battery
 * readout passes [ThermalSensorType.BATTERY].
 */
internal fun temperatureTone(
    deciCelsius: Int?,
    thermal: ThermalStatus?,
    sensor: ThermalSensorType = ThermalSensorType.CPU,
): Tone = when (ThermalClassifier.classify(deciCelsius, sensor, thermal).level) {
    ThermalClass.UNAVAILABLE, ThermalClass.OK -> Tone.Neutral
    ThermalClass.WARM, ThermalClass.HOT -> Tone.Warning
    ThermalClass.CRITICAL -> Tone.Danger
}

/**
 * Which sensor the figure came from, and the shared classifier's word for it.
 *
 * Named rather than implied. A CPU zone reading and a battery reading differ by several degrees on the
 * same device at the same moment, and a tile that says "41 °C" without saying what is 41 °C invites the
 * user to compare it against another app that measured the other thing.
 *
 * The severity word comes from [ThermalClassifier] — the same classification [temperatureTone] colours
 * from — rather than from [ThermalStatus.label], so the word and the colour are the same verdict. It is
 * appended only when the reading is warm or worse: "CPU sensor · Critical" earns its place, but "CPU
 * sensor · Normal" on every idle tile is noise, and §2 asks the word to accompany the colour where the
 * colour means something.
 */
internal fun temperatureDetail(snapshot: PerformanceSnapshot, thermal: ThermalStatus?): String {
    val cpu = snapshot.thermal.cpuTemperatureDeciCelsius.isAvailable
    val sensorLabel = if (cpu) "CPU sensor" else "Battery sensor"
    val sensorType = if (cpu) ThermalSensorType.CPU else ThermalSensorType.BATTERY
    val classification = ThermalClassifier.classify(
        snapshot.primaryTemperatureDeciCelsius.let { (it as? com.gamecore.core.common.Observed.Value)?.value },
        sensorType,
        thermal,
    )
    // Only surface the word once it carries meaning; below WARM the colour is neutral and the word would
    // add nothing but a "Normal" the user learns to ignore.
    val word = classification.label.takeIf {
        classification.level.severity >= ThermalClass.WARM.severity
    }
    return listOfNotNull(sensorLabel, word).joinToString(" · ")
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

// Temperature thresholds now live in one place, com.gamecore.core.model.ThermalClassifier, shared by the
// dashboard, the performance screen, the session report and the overlay. The old Readings-local
// WARM/HOT_DECI_CELSIUS constants (420/460) are gone with the split classification that produced the
// "85.3 °C · Normal" bug; the session view-models keep their own peak-temperature warning threshold for a
// different job (a recorded peak, not a live reading) and are untouched here.

internal const val LOW_BATTERY_PERCENT = 15
internal const val WARNING_BATTERY_PERCENT = 30
internal const val HIGH_MEMORY_FRACTION = 0.9f
internal const val TIGHT_STORAGE_FRACTION = 0.92f
