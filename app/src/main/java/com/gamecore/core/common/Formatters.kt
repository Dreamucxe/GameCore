package com.gamecore.core.common

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Formatting. Pure functions, no Android dependency, so the whole of it is covered
 * by fast JVM tests — which matters because these strings are what the user
 * actually reads, and an off-by-a-factor-of-1024 in a HUD is invisible until
 * someone compares it against another app.
 */
object Formatters {

    /**
     * Binary byte sizes. Android's memory APIs are all powers of two
     * (`MemoryInfo`, `Debug.MemoryInfo`, `/proc/meminfo`), so the divisor is 1024
     * and the unit labels say KB/MB/GB in the way every Android tool does.
     *
     * One decimal below 100 and none above, so a value changing from 9.9 to 10.0 to
     * 100 does not change the column width in a HUD that is redrawn every second.
     */
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB", "PB")
        var v = value.toDouble() / 1024.0
        var idx = 0
        while (v >= 1024.0 && idx < units.lastIndex) {
            v /= 1024.0
            idx++
        }
        val pattern = if (v >= 100.0) "%.0f %s" else "%.1f %s"
        return String.format(Locale.US, pattern, v, units[idx])
    }

    /** Kibibytes, as `/proc/meminfo` reports them. */
    fun kibibytes(kb: Long): String = bytes(kb * 1024L)

    /**
     * Gigabytes with one decimal, for the "4.2 / 8 GB" form the dashboard and pill
     * use. Deliberately not [bytes]: that would switch a 980 MB reading to "980 MB"
     * mid-session and break the alignment of a fixed-width readout.
     */
    fun gigabytes(value: Long): String =
        String.format(Locale.US, "%.1f", value.toDouble() / (1024.0 * 1024.0 * 1024.0))

    /** "4.2 / 8 GB" — used/total, with the unit stated once. */
    fun memoryPair(usedBytes: Long, totalBytes: Long): String {
        val total = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        // Total RAM is a whole number of GB in the user's mind ("8 GB phone") even
        // though the platform reports slightly less, so it is rounded and the used
        // figure carries the precision.
        return String.format(Locale.US, "%.1f / %.0f GB", usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0), total)
    }

    /** A 0..1 fraction as a percentage. */
    fun percent(fraction: Float, decimals: Int = 0): String {
        if (fraction.isNaN()) return "—"
        val clamped = fraction.coerceIn(0f, 1f) * 100f
        return String.format(Locale.US, "%.${decimals}f%%", clamped)
    }

    /** An already-scaled 0..100 percentage. */
    fun percentValue(value: Float, decimals: Int = 0): String {
        if (value.isNaN()) return "—"
        return String.format(Locale.US, "%.${decimals}f%%", value.coerceAtLeast(0f))
    }

    /** `H:MM:SS`, or `MM:SS` under an hour. Session timers and uptime. */
    fun duration(millis: Long): String {
        if (millis < 0) return "—"
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** "1h 24m", "45m 12s", "9s" — the form session reports and history use. */
    fun durationCoarse(millis: Long): String {
        if (millis < 0) return "—"
        val d = TimeUnit.MILLISECONDS.toDays(millis)
        val h = TimeUnit.MILLISECONDS.toHours(millis) % 24
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    /** Total playtime across many sessions: "14h 30m", "48m". */
    fun durationTotal(millis: Long): String {
        if (millis <= 0) return "0m"
        val h = TimeUnit.MILLISECONDS.toHours(millis)
        val m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** Throughput. */
    fun rate(bytesPerSecond: Double): String {
        if (bytesPerSecond.isNaN() || bytesPerSecond < 0) return "—"
        if (bytesPerSecond < 1.0) return "0 B/s"
        return "${bytes(bytesPerSecond.toLong())}/s"
    }

    /** Compact throughput for the pill, where "1.2 MB/s" must fit beside four other stats. */
    fun rateCompact(bytesPerSecond: Double): String {
        if (bytesPerSecond.isNaN() || bytesPerSecond < 0) return "—"
        val kb = bytesPerSecond / 1024.0
        return when {
            kb < 1.0 -> "0K"
            kb < 1024.0 -> String.format(Locale.US, "%.0fK", kb)
            else -> String.format(Locale.US, "%.1fM", kb / 1024.0)
        }
    }

    fun frequencyKHz(khz: Long): String = when {
        khz <= 0 -> "—"
        khz >= 1_000_000 -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        else -> String.format(Locale.US, "%d MHz", khz / 1000)
    }

    /**
     * Temperature. Held internally in tenths of a degree because that is the unit
     * `BatteryManager` broadcasts, and rounding it to whole degrees at the source
     * would throw away the resolution a thermal graph needs.
     */
    fun temperature(deciCelsius: Int): String =
        String.format(Locale.US, "%.1f°C", deciCelsius / 10.0)

    /** Whole degrees, for the dashboard tile and the pill. */
    fun temperatureShort(deciCelsius: Int): String =
        String.format(Locale.US, "%d°C", Math.round(deciCelsius / 10.0f))

    fun voltage(milliVolts: Int): String =
        String.format(Locale.US, "%.3f V", milliVolts / 1000.0)

    /**
     * Refresh rate. Panels advertise 60.000004 Hz and similar, so the fraction is
     * dropped unless it is genuinely meaningful (48.5 Hz modes exist).
     */
    fun hertz(hz: Float): String {
        if (hz <= 0f || hz.isNaN()) return "—"
        val rounded = Math.round(hz)
        return if (kotlin.math.abs(hz - rounded) < 0.2f) {
            "$rounded Hz"
        } else {
            String.format(Locale.US, "%.1f Hz", hz)
        }
    }

    /** Just the number, for a HUD element that has its own "Hz" label. */
    fun hertzValue(hz: Float): String {
        if (hz <= 0f || hz.isNaN()) return "—"
        val rounded = Math.round(hz)
        return if (kotlin.math.abs(hz - rounded) < 0.2f) "$rounded" else String.format(Locale.US, "%.1f", hz)
    }

    fun resolution(width: Int, height: Int): String = "$width × $height"

    fun millis(ms: Int): String = if (ms < 0) "—" else "$ms ms"

    /**
     * Battery drain as a rate.
     *
     * A percentage per hour, which is the only honest way to compare a 20-minute
     * session against a two-hour one. Extrapolating from a very short session would
     * turn one percentage point of noise into a wild figure, so anything under the
     * caller's minimum returns null and the UI says the session was too short.
     */
    fun drainPerHour(percentLost: Float, millis: Long): String? {
        if (millis <= 0L) return null
        val hours = millis / 3_600_000.0
        if (hours <= 0.0) return null
        return String.format(Locale.US, "%.1f%% / hour", percentLost / hours)
    }

    fun clockTime(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    fun dateTime(epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = epochMillis
        return String.format(
            Locale.US, "%04d-%02d-%02d %02d:%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    /** "Today", "Yesterday", or a date — for grouping the session history. */
    fun relativeDay(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        val sameYear = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
        val dayDelta = if (sameYear) {
            now.get(java.util.Calendar.DAY_OF_YEAR) - cal.get(java.util.Calendar.DAY_OF_YEAR)
        } else {
            Int.MAX_VALUE
        }
        return when (dayDelta) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> String.format(
                Locale.US, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }
    }

    /** ISO-8601 UTC, for an export that may be opened on another machine. */
    fun iso8601(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(epochMillis))
    }

    /** A filename-safe timestamp: `20260829-184233`. */
    fun fileTimestamp(epochMillis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        return fmt.format(java.util.Date(epochMillis))
    }

    fun count(value: Int, singular: String, plural: String = singular + "s"): String =
        "$value ${if (value == 1) singular else plural}"

    /** Signed percentage, for a battery delta that could in principle be positive. */
    fun signedPercent(delta: Int): String = if (delta > 0) "+$delta%" else "$delta%"
}
