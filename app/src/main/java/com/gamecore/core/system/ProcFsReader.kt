package com.gamecore.core.system

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.CoreFrequency
import com.gamecore.core.model.CpuTimes
import com.gamecore.core.model.LoadAverage
import com.gamecore.core.model.ThermalSensor
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `/proc` and `/sys` reader.
 *
 * Everything here is best-effort, and the reasons are worth stating because they are
 * the reasons half of this app's readings can be absent on a given phone:
 *
 *  * `/proc/stat` is world-readable by DAC mode on nearly every device and denied by
 *    SELinux policy on several vendor kernels regardless. The denial arrives as a
 *    plain `IOException` whose message is the only signal that it was a refusal
 *    rather than an absence, which is why [readFile] inspects it.
 *  * `/proc` is mounted with `hidepid=2` from API 29, so only GameCore's own PID is
 *    visible. Nothing here reads another process's files — the app has no reason to.
 *  * `cpufreq` nodes moved out of app reach around Android 10 on most builds.
 *  * `/sys/class/thermal` zone names are entirely OEM-defined. There is no standard.
 *
 * So every method returns [Observed] and a failure is reported rather than replaced
 * with a zero. This class is a pure adapter with injectable roots, which is what makes
 * the parsing — where the arithmetic that must not be wrong lives — testable on the
 * JVM against fixture directories instead of only on a device.
 *
 * All calls are blocking file I/O. Confining them to an IO dispatcher is the caller's
 * responsibility, and every caller in `core.system` does it.
 */
@Singleton
class ProcFsReader(
    private val procRoot: File,
    private val sysRoot: File,
) {

    @Inject
    constructor() : this(File("/proc"), File("/sys"))

    // ------------------------------------------------------------------- cpu time

    /** Aggregate jiffy counters from the first line of `/proc/stat`. */
    fun readSystemCpuTimes(): Observed<CpuTimes> =
        when (val line = readFirstLine(File(procRoot, "stat"))) {
            is Observed.Value -> parseCpuLine(line.value)
                ?.let { Observed.of(it, DataSource.PROC_FS) }
                ?: Observed.Failed("The first line of /proc/stat could not be parsed")
            is Observed.Restricted -> line
            is Observed.Failed -> line
        }

    /**
     * The per-core lines (`cpu0`, `cpu1`, …).
     *
     * The `it[3].isDigit()` test is what separates them from the aggregate `cpu ` line
     * without matching it: both start with "cpu", and a prefix test alone would count
     * the total as a core and report a device with twice as many cores as it has.
     */
    fun readPerCoreCpuTimes(): Observed<List<CpuTimes>> =
        when (val text = readFile(File(procRoot, "stat"))) {
            is Observed.Value -> {
                val cores = text.value.lineSequence()
                    .filter { it.startsWith("cpu") && it.length > 3 && it[3].isDigit() }
                    .mapNotNull { parseCpuLine(it) }
                    .toList()
                if (cores.isEmpty()) {
                    Observed.Failed("/proc/stat exposed no per-core lines")
                } else {
                    Observed.of(cores, DataSource.PROC_FS)
                }
            }
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }

    /**
     * `cpu user nice system idle iowait irq softirq steal guest guest_nice`.
     *
     * Fields after `steal` are absent on older kernels and the two `guest` fields are
     * already counted inside `user` and `nice`, so a short line is tolerated rather
     * than rejected — unlike a short `/proc/<pid>/stat`, where a missing field would
     * put a fabricated zero in front of the user.
     */
    private fun parseCpuLine(line: String): CpuTimes? {
        val parts = line.trim().split(WHITESPACE)
        if (parts.size < 5) return null
        val numbers = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (numbers.size < 4) return null
        return CpuTimes(
            name = parts[0],
            user = numbers.getOrElse(0) { 0L },
            nice = numbers.getOrElse(1) { 0L },
            system = numbers.getOrElse(2) { 0L },
            idle = numbers.getOrElse(3) { 0L },
            iowait = numbers.getOrElse(4) { 0L },
            irq = numbers.getOrElse(5) { 0L },
            softirq = numbers.getOrElse(6) { 0L },
            steal = numbers.getOrElse(7) { 0L },
        )
    }

    /**
     * Parses `/proc/stat` text obtained elsewhere — through the elevated shell, when
     * SELinux denied the direct read.
     *
     * Exposed so there is exactly one parser for this format. Two copies would be two
     * chances to get the aggregate-versus-core distinction wrong in different ways.
     */
    fun parseStatText(text: String): Observed<Pair<CpuTimes, List<CpuTimes>>> {
        val lines = text.lineSequence().filter { it.startsWith("cpu") }.toList()
        val aggregate = lines.firstOrNull { it.length > 3 && !it[3].isDigit() }
            ?.let { parseCpuLine(it) }
            ?: return Observed.Failed("No aggregate CPU line in the output")
        val cores = lines
            .filter { it.length > 3 && it[3].isDigit() }
            .mapNotNull { parseCpuLine(it) }
        return Observed.of(aggregate to cores, DataSource.SHELL_SHIZUKU)
    }

    fun readLoadAverage(): Observed<LoadAverage> =
        when (val line = readFirstLine(File(procRoot, "loadavg"))) {
            is Observed.Value -> {
                val parts = line.value.trim().split(WHITESPACE)
                val one = parts.getOrNull(0)?.toFloatOrNull()
                val five = parts.getOrNull(1)?.toFloatOrNull()
                val fifteen = parts.getOrNull(2)?.toFloatOrNull()
                if (one == null || five == null || fifteen == null) {
                    Observed.Failed("/proc/loadavg could not be parsed")
                } else {
                    Observed.of(LoadAverage(one, five, fifteen), DataSource.PROC_FS)
                }
            }
            is Observed.Restricted -> line
            is Observed.Failed -> line
        }

    // --------------------------------------------------------------------- memory

    /** `/proc/meminfo` as its KiB key/value pairs. */
    fun readMemInfo(): Observed<Map<String, Long>> =
        when (val text = readFile(File(procRoot, "meminfo"))) {
            is Observed.Value -> parseMemInfoText(text.value, DataSource.PROC_FS)
            is Observed.Restricted -> text
            is Observed.Failed -> text
        }

    /** Same format, read through the shell. One parser, as with `/proc/stat`. */
    fun parseMemInfoText(text: String, source: DataSource = DataSource.SHELL_SHIZUKU):
        Observed<Map<String, Long>> {
        val map = HashMap<String, Long>(64)
        text.lineSequence().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val value = line.substring(colon + 1)
                    .trim()
                    .removeSuffix(" kB")
                    .trim()
                    .toLongOrNull()
                if (value != null) map[line.substring(0, colon)] = value
            }
        }
        return if (map.isEmpty()) {
            Observed.Failed("/proc/meminfo held no readable entries")
        } else {
            Observed.of(map, source)
        }
    }

    // -------------------------------------------------------------------- cpufreq

    /**
     * Per-core scaling frequencies from `/sys/devices/system/cpu/cpuN/cpufreq`.
     *
     * A core the kernel has parked has no readable `scaling_cur_freq`, which is
     * reported as `isOnline = false` rather than 0 kHz — those are different claims,
     * and the second one would read as "this core is idle".
     *
     * `cpuinfo_min_freq`/`cpuinfo_max_freq` are the hardware bounds; the `scaling_*`
     * spellings are the governor's current policy and would make a thermally throttled
     * core look like a slower core. The hardware bounds are what the UI shows.
     */
    fun readCoreFrequencies(coreCount: Int): Observed<List<CoreFrequency>> {
        val out = ArrayList<CoreFrequency>(coreCount)
        var anyReadable = false
        for (index in 0 until coreCount) {
            val base = File(sysRoot, "devices/system/cpu/cpu$index/cpufreq")
            val current = readLongOrNull(File(base, "scaling_cur_freq"))
            val min = readLongOrNull(File(base, "cpuinfo_min_freq"))
            val max = readLongOrNull(File(base, "cpuinfo_max_freq"))
            if (current != null || min != null || max != null) anyReadable = true
            out += CoreFrequency(
                coreIndex = index,
                currentKHz = current ?: 0L,
                minKHz = min ?: 0L,
                maxKHz = max ?: 0L,
                isOnline = current != null,
            )
        }
        return if (anyReadable) {
            Observed.of(out, DataSource.SYS_FS)
        } else {
            Observed.platform("cpufreq is not readable by apps on this device")
        }
    }

    // -------------------------------------------------------------------- thermal

    /**
     * The hottest plausible CPU zone, in tenths of a degree.
     *
     * Zone selection is a heuristic and is documented as one everywhere it surfaces:
     * the `type` string is vendor-chosen, so a match on cpu/soc/tsens/apc/big/little
     * or MediaTek's `mtktscpu` is the best an app can do. The *hottest* matching zone
     * is taken rather than the first, because vendors order them arbitrarily and a
     * little-core zone reporting 38 °C while the big cluster is at 71 °C would
     * otherwise be shown as the device's CPU temperature.
     */
    fun readCpuTemperature(): Observed<Int> {
        val zones = thermalZoneDirs() ?: return Observed.notPresent("No thermal zones exposed")
        var hottest: Int? = null
        for (zone in zones) {
            val type = zoneType(zone) ?: continue
            if (!isCpuZoneName(type)) continue
            val deci = readZoneTemperature(zone) ?: continue
            if (hottest == null || deci > hottest) hottest = deci
        }
        return hottest
            ?.let { Observed.of(it, DataSource.SYS_FS, Precision.EXACT) }
            ?: Observed.notPresent("No CPU thermal zone on this device could be read")
    }

    /**
     * Every readable thermal zone.
     *
     * [ThermalSensor.label] is the zone's own `type` string, tidied for display and
     * never renamed to something friendlier: guessing which physical component a
     * vendor's `tsens_tz_sensor12` measures would be an invention, and a user comparing
     * GameCore's reading against another tool needs the vendor's own name to do it.
     */
    fun readThermalSensors(): Observed<List<ThermalSensor>> {
        val zones = thermalZoneDirs() ?: return Observed.notPresent("No thermal zones exposed")
        val sensors = ArrayList<ThermalSensor>(zones.size)
        for (zone in zones) {
            val type = zoneType(zone) ?: continue
            val deci = readZoneTemperature(zone) ?: continue
            sensors += ThermalSensor(
                label = tidyZoneName(type),
                deciCelsius = deci,
                isCpuZone = isCpuZoneName(type),
            )
        }
        return if (sensors.isEmpty()) {
            Observed.notPresent("No thermal zone on this device could be read")
        } else {
            Observed.of(sensors.sortedByDescending { it.deciCelsius }, DataSource.SYS_FS)
        }
    }

    private fun thermalZoneDirs(): List<File>? = try {
        File(sysRoot, "class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name }
            ?.takeIf { it.isNotEmpty() }
    } catch (error: Throwable) {
        null
    }

    private fun zoneType(zone: File): String? =
        (readFirstLine(File(zone, "type")) as? Observed.Value)?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun isCpuZoneName(type: String): Boolean {
        val lower = type.lowercase()
        return CPU_ZONE_HINTS.any { lower.contains(it) }
    }

    /**
     * Kernels report either milli-degrees (48000), deci-degrees (480) or whole degrees
     * (48), with no field telling which. The magnitude is the only signal, and the
     * result is accepted only inside a range a silicon die can actually be in: 0 to
     * 150 °C. A zone outside it is a vendor sentinel value, not a temperature.
     */
    private fun readZoneTemperature(zone: File): Int? {
        val raw = readLongOrNull(File(zone, "temp")) ?: return null
        val deci = when {
            raw > 10_000 -> (raw / 100).toInt()
            raw > 1_000 -> (raw / 10).toInt()
            else -> raw.toInt()
        }
        return if (deci in 0..1500) deci else null
    }

    /** `mtktscpu-sysrst` → "Mtktscpu Sysrst". The vendor's word, capitalised. */
    private fun tidyZoneName(type: String): String = type
        .replace('_', ' ')
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.length <= 1) word.uppercase() else word[0].uppercase() + word.substring(1)
        }
        .take(32)

    // ---------------------------------------------------------------------- utils

    /**
     * Reads a whole file, keeping the two failure modes apart.
     *
     * A refusal is a fact about Android's sandbox that Shizuku may be able to lift, so
     * it is [Observed.Restricted]; a missing file is a fact about this kernel, so it is
     * "not present". Android surfaces `EACCES` from a sandboxed read as a plain
     * `IOException` — `FileNotFoundException: /proc/stat: open failed: EACCES
     * (Permission denied)` — so the message text is the only available signal, and
     * treating it as absence would tell the user their kernel lacks a file it has.
     */
    private fun readFile(file: File): Observed<String> = try {
        if (!file.exists()) {
            Observed.notPresent("${file.path} does not exist on this device")
        } else {
            val text = file.readText()
            if (text.isBlank()) {
                Observed.Failed("${file.path} was empty")
            } else {
                Observed.of(text, DataSource.PROC_FS)
            }
        }
    } catch (error: SecurityException) {
        Observed.needsElevation("${file.path} is not readable by this app")
    } catch (error: java.io.IOException) {
        val message = error.message.orEmpty()
        if (message.contains("Permission denied", ignoreCase = true) ||
            message.contains("EACCES", ignoreCase = true)
        ) {
            Observed.needsElevation("${file.path} is not readable by this app")
        } else {
            Observed.Failed("Could not read ${file.path}", message.ifBlank { null })
        }
    } catch (error: Throwable) {
        Observed.Failed("Could not read ${file.path}", error.message)
    }

    private fun readFirstLine(file: File): Observed<String> = when (val text = readFile(file)) {
        is Observed.Value -> text.value.lineSequence().firstOrNull()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Observed.of(it, text.source) }
            ?: Observed.Failed("${file.path} had no first line")
        is Observed.Restricted -> text
        is Observed.Failed -> text
    }

    private fun readLongOrNull(file: File): Long? =
        (readFirstLine(file) as? Observed.Value)?.value?.trim()?.toLongOrNull()

    /** Jiffies → milliseconds, using the fixed USER_HZ documented in [CLOCK_TICKS]. */
    fun jiffiesToMillis(jiffies: Long): Long = jiffies * 1000L / CLOCK_TICKS

    companion object {
        /**
         * USER_HZ. Linux fixes it at 100 on every Android ABI, and it is the divisor
         * for every jiffy count in `/proc`. `sysconf(_SC_CLK_TCK)` would confirm it at
         * runtime but needs JNI, so the constant is used with the assumption stated
         * here rather than buried.
         */
        const val CLOCK_TICKS = 100L

        private val WHITESPACE = Regex("\\s+")

        /**
         * Substrings that suggest a CPU or SoC thermal zone. A heuristic — there is no
         * standard for zone naming, and this is the closest thing to one that exists
         * across Qualcomm (`tsens`, `apc`), MediaTek (`mtktscpu`), Exynos (`big`,
         * `little`) and AOSP reference (`cpu`, `soc`) kernels.
         */
        private val CPU_ZONE_HINTS = listOf(
            "cpu", "soc", "tsens", "apc", "big", "little", "mtktscpu", "gpu-cluster",
        )
    }
}

