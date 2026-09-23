package com.gamecore.core.model

import com.gamecore.core.common.valueOrNull

/**
 * The ONE place a temperature becomes a word and a severity.
 *
 * The redesign's §2/§8 requirement, and the fix for a real bug: the dashboard used to pick a tile's
 * COLOUR from one function ([com.gamecore.ui.components.temperatureTone], driven by the raw sensor
 * threshold) and its WORD from another (the platform [ThermalStatus.label]). When a sensor read 85.3 °C
 * while `PowerManager.getCurrentThermalStatus()` still reported `NONE`, the tile showed 85.3 °C in red
 * with the word "Normal" beside it — the colour said one thing, the label the opposite.
 *
 * Here the label and the severity come from a single computation, so they can never disagree again.
 * A UI layer maps [ThermalClass] to a colour; it does not get to choose a different word.
 *
 * Pure Kotlin, no `android.*`: a unit test drives it directly with a number and an optional platform
 * status and asserts the label and the class always agree.
 */

/** The severity a temperature reading maps to. Ordered least-to-most severe; [UNAVAILABLE] is not on that scale. */
enum class ThermalClass(val label: String) {
    /** A missing, out-of-range or implausible reading. Never coloured as an alarm. */
    UNAVAILABLE("Unavailable"),

    /** Within the normal operating band for this sensor. */
    OK("Normal"),

    /** Warm, but nothing is being throttled. */
    WARM("Warm"),

    /** Hot enough that throttling is likely or has begun. */
    HOT("Hot"),

    /** The device is at or beyond the point it protects itself. */
    CRITICAL("Critical"),
    ;

    /** Severity rank for comparison; [UNAVAILABLE] sorts below [OK] so a real reading always wins. */
    val severity: Int
        get() = when (this) {
            UNAVAILABLE -> -1
            OK -> 0
            WARM -> 1
            HOT -> 2
            CRITICAL -> 3
        }
}

/** Which sensor produced a reading — the thresholds differ because the sensors sit in different places. */
enum class ThermalSensorType {
    /** A CPU/SoC-class zone from `/sys/class/thermal`. Runs hottest under load. */
    CPU,

    /** The battery pack sensor. Lags the SoC and alarms at lower absolute temperatures. */
    BATTERY,
}

/** The result: a [level] and the [label] that goes with it, guaranteed to agree. */
data class ThermalClassification(
    val level: ThermalClass,
    val label: String,
)

/**
 * Turns a temperature (and, when known, the platform's own throttling verdict) into one severity + word.
 *
 * Thresholds are display hints, not claims about what a phone can tolerate, and they live here so they can
 * be argued about in one place — the same discipline the old `Readings.kt` constants had, now shared by
 * every surface (dashboard, performance screen, session report, and the in-game overlay).
 */
object ThermalClassifier {

    // CPU/SoC-class thresholds, in tenths of a degree (§2 defaults: OK<45, WARM 45-60, HOT 60-80, CRIT>80).
    const val CPU_WARM_DECI = 450
    const val CPU_HOT_DECI = 600
    const val CPU_CRITICAL_DECI = 800

    // Battery thresholds are lower: a battery at 50 °C is a problem where a SoC at 50 °C is idle-warm.
    // Chosen against common OEM battery guidance (warm ~40, hot ~45, protective cut-offs from ~50).
    const val BATTERY_WARM_DECI = 400
    const val BATTERY_HOT_DECI = 450
    const val BATTERY_CRITICAL_DECI = 500

    /** Plausible physical range for any of these sensors, in tenths of a degree. Outside → UNAVAILABLE. */
    const val PLAUSIBLE_MIN_DECI = -300 // -30 °C, matching the battery reader's floor
    const val PLAUSIBLE_MAX_DECI = 1500 // 150 °C, matching the CPU-zone reader's ceiling

    /**
     * Classify a reading.
     *
     * @param deciCelsius tenths of a degree, or null when nothing could be read.
     * @param sensor which sensor it came from, selecting the threshold set.
     * @param platformStatus `PowerManager` thermal status when available. When it is more severe than the
     *   raw temperature suggests, it wins — the platform knows its own limits — but the returned label is
     *   still derived from the final class, so word and colour stay in step. `NONE`/`LIGHT`/`MODERATE` never
     *   escalate: nothing is being clamped yet.
     */
    fun classify(
        deciCelsius: Int?,
        sensor: ThermalSensorType,
        platformStatus: ThermalStatus? = null,
    ): ThermalClassification {
        val fromTemp = classFromTemperature(deciCelsius, sensor)
        val fromPlatform = classFromPlatform(platformStatus)

        // The more severe of the two wins. If the sensor gave nothing but the platform is throttling, the
        // platform verdict still surfaces (HOT/CRITICAL) rather than showing "Unavailable" over a hot device.
        val level = when {
            fromPlatform == null -> fromTemp
            fromTemp == ThermalClass.UNAVAILABLE -> fromPlatform
            fromPlatform.severity > fromTemp.severity -> fromPlatform
            else -> fromTemp
        }
        return ThermalClassification(level = level, label = level.label)
    }

    /**
     * Classify a whole [PerformanceSnapshot] — the one entry point every live surface should use.
     *
     * The temperature is chosen the same way [PerformanceSnapshot.primaryTemperatureDeciCelsius] chooses
     * it: the CPU zone when it read, the battery when it did not. That choice also selects the threshold
     * set, because the two sensors alarm at different absolute temperatures — so a battery-only reading is
     * judged against the battery thresholds, not the CPU ones, which the plain [classify] overload cannot
     * know to do from a bare number. The platform's own throttling verdict is folded in either way, so a
     * device that is clamping at [ThermalStatus.SEVERE] surfaces as [ThermalClass.HOT] even when no sensor
     * reads a number — the same "the platform knows its own limits" rule the number overload applies.
     *
     * This is the single seam the in-game button's dot (§2) and the `THERMAL_STATUS` stat (§8) both read,
     * which is what stops a fourth ad-hoc thermal path — the exact fragmentation the class KDoc warns about.
     */
    fun classify(snapshot: PerformanceSnapshot): ThermalClassification {
        val cpu = snapshot.thermal.cpuTemperatureDeciCelsius.valueOrNull
        val platform = snapshot.thermal.status.valueOrNull
        return if (cpu != null) {
            classify(cpu, ThermalSensorType.CPU, platform)
        } else {
            classify(snapshot.battery.temperatureDeciCelsius.valueOrNull, ThermalSensorType.BATTERY, platform)
        }
    }

    private fun classFromTemperature(deciCelsius: Int?, sensor: ThermalSensorType): ThermalClass {
        if (deciCelsius == null || deciCelsius < PLAUSIBLE_MIN_DECI || deciCelsius > PLAUSIBLE_MAX_DECI) {
            return ThermalClass.UNAVAILABLE
        }
        val warm: Int
        val hot: Int
        val critical: Int
        when (sensor) {
            ThermalSensorType.CPU -> {
                warm = CPU_WARM_DECI; hot = CPU_HOT_DECI; critical = CPU_CRITICAL_DECI
            }
            ThermalSensorType.BATTERY -> {
                warm = BATTERY_WARM_DECI; hot = BATTERY_HOT_DECI; critical = BATTERY_CRITICAL_DECI
            }
        }
        return when {
            deciCelsius >= critical -> ThermalClass.CRITICAL
            deciCelsius >= hot -> ThermalClass.HOT
            deciCelsius >= warm -> ThermalClass.WARM
            else -> ThermalClass.OK
        }
    }

    /**
     * Only the levels at which the platform is actually clamping performance escalate the reading.
     * `NONE`/`LIGHT`/`MODERATE` return null (no escalation); `SEVERE` → HOT; `CRITICAL` and above → CRITICAL.
     */
    private fun classFromPlatform(status: ThermalStatus?): ThermalClass? = when (status) {
        null, ThermalStatus.NONE, ThermalStatus.LIGHT, ThermalStatus.MODERATE -> null
        ThermalStatus.SEVERE -> ThermalClass.HOT
        ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> ThermalClass.CRITICAL
    }
}
