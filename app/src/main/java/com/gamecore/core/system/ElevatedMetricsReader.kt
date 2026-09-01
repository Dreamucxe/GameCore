package com.gamecore.core.system

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.map
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand

/**
 * The same facts, read through an elevated shell.
 *
 * This reader exists for one reason: on several vendor kernels SELinux policy denies an
 * ordinary app the read of `/proc/stat` and `/proc/meminfo` that the same kernel's DAC
 * mode permits. Where that happens the standard reader returns
 * [Observed.needsElevation], and `cat` run as uid 2000 succeeds — so the figure is
 * obtainable, just not directly.
 *
 * Three properties are deliberate:
 *
 *  * **It reads only what the standard reader could not.** Battery and storage come
 *    from platform APIs that are never withheld, so routing them through text parsing
 *    would make reliable data less reliable. Those delegate.
 *  * **It has its own sampler.** A jiffy delta between a `/proc` read and a shell read
 *    spans the gap between two different mechanisms, not the sampling interval.
 *    [CpuSamplerFactory] exists for this.
 *  * **It is never the app's only reader.** [CompositeMetricsReader] holds it behind an
 *    availability check, and every caller above `core.system` sees only [MetricsReader].
 *
 * Not a `@Singleton`, and not injected anywhere: the composite constructs it with a
 * shell that has already been proven live. A reader wired directly into a screen would
 * be a reader whose Shizuku dependency was never checked.
 */
class ElevatedMetricsReader(
    private val shell: ElevatedShell,
    private val delegate: MetricsReader,
    private val procFs: ProcFsReader,
    private val cpuSampler: CpuSampler,
) : MetricsReader {

    override val accessLevel: AccessLevel = shell.accessLevel

    override suspend fun isAvailable(): Boolean = shell.isAvailable()

    override fun resetSampling() {
        cpuSampler.reset()
        delegate.resetSampling()
    }

    private val coreCount: Int =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ------------------------------------------------------------------------ cpu

    /**
     * CPU counters from `cat /proc/stat` through the shell.
     *
     * One shell round trip yields both the aggregate and the per-core lines, so
     * [ProcFsReader.parseStatText] returns them together — the same parser the direct
     * read uses, because two copies would be two chances to get the
     * aggregate-versus-core distinction wrong in different ways.
     *
     * Frequencies and temperature are read directly rather than through the shell.
     * `cpufreq` and `/sys/class/thermal` are world-readable where they exist at all, so
     * a shell adds nothing; where they do not exist, no shell conjures them.
     */
    override suspend fun readCpu(): CpuReading {
        val result = shell.execute(ShellCommand.CpuStat)
        if (!result.isSuccess) {
            // A failed shell is not a reason to show nothing. The standard reader still
            // knows what it knows, including whether the direct read was denied.
            return delegate.readCpu()
        }

        val parsed = procFs.parseStatText(result.stdout)
        val aggregate = parsed.map { it.first }
        val perCore = parsed.map { it.second }

        return CpuReading(
            coreCount = coreCount,
            overallPercent = cpuSampler.sampleSystem(aggregate),
            perCorePercent = cpuSampler.samplePerCore(perCore),
            frequenciesKHz = procFs.readCoreFrequencies(coreCount),
            loadAverage = procFs.readLoadAverage(),
            temperatureDeciCelsius = procFs.readCpuTemperature(),
        )
    }

    // --------------------------------------------------------------------- memory

    /**
     * Memory, with the platform's totals and `/proc/meminfo`'s detail.
     *
     * `ActivityManager.getMemoryInfo()` is not withheld from anyone, so the totals still
     * come from the standard reader — this only substitutes the four `/proc` fields when
     * the direct read was refused. The standard reading is returned unchanged when it
     * already has them, so a device where nothing was denied does not pay for a shell
     * round trip it does not need.
     */
    override suspend fun readMemory(): MemoryReading {
        val standard = delegate.readMemory()
        if (standard.cachedBytes.valueOrNull != null) return standard

        val result = shell.execute(ShellCommand.MemInfo)
        if (!result.isSuccess) return standard

        val meminfo = procFs.parseMemInfoText(result.stdout, DataSource.SHELL_SHIZUKU)
        val map = meminfo.valueOrNull ?: return standard

        fun kb(key: String): Observed<Long> = map[key]
            ?.let { Observed.of(it * 1024L, DataSource.SHELL_SHIZUKU) }
            ?: Observed.notPresent("This kernel does not report $key")

        // The platform figures win where they exist; /proc fills in only what it must.
        val total = if (standard.totalBytes > 0L) {
            standard.totalBytes
        } else {
            map["MemTotal"]?.times(1024L) ?: 0L
        }
        if (total <= 0L) return standard

        return standard.copy(
            totalBytes = total,
            availableBytes = if (standard.availableBytes > 0L) {
                standard.availableBytes
            } else {
                map["MemAvailable"]?.times(1024L) ?: 0L
            },
            cachedBytes = kb("Cached"),
            freeBytes = kb("MemFree"),
            swapTotalBytes = kb("SwapTotal"),
            swapFreeBytes = kb("SwapFree"),
        )
    }

    // -------------------------------------------------------------------- thermal

    /**
     * Thermal detail from `dumpsys thermalservice`.
     *
     * What the shell adds here is names, not numbers. `/sys/class/thermal` gives
     * `thermal_zone7`; the HAL gives `cpu-0-0` with a type constant, so a sensor list
     * can be labelled with something a user recognises rather than a zone index. The
     * platform status is kept from the standard reader — `PowerManager` already answers
     * that without any special access, and it is the authoritative signal.
     *
     * The sysfs sensor list stands where the dump cannot be parsed. Losing the better
     * labels is not a reason to lose the readings.
     */
    override suspend fun readThermal(): ThermalReading {
        val standard = delegate.readThermal()
        val result = shell.execute(ShellCommand.ThermalService)
        if (!result.isSuccess) return standard

        val sensors = DumpsysParsers.parseThermalSensors(result.stdout)
        val status = if (standard.status.valueOrNull != null) {
            standard.status
        } else {
            DumpsysParsers.parseThermalStatus(result.stdout)
        }

        return standard.copy(
            status = status,
            sensors = if (sensors.valueOrNull != null) sensors else standard.sensors,
            cpuTemperatureDeciCelsius = standard.cpuTemperatureDeciCelsius.valueOrNull
                ?.let { standard.cpuTemperatureDeciCelsius }
                ?: hottestCpuSensor(sensors)
                ?: standard.cpuTemperatureDeciCelsius,
        )
    }

    /**
     * The hottest CPU-ish sensor from the HAL, for a device whose sysfs zones are
     * unreadable but whose thermal service answers. The hottest rather than the first,
     * for the same reason the sysfs reader takes the hottest: a little-core sensor at
     * 38 °C beside a big cluster at 71 °C would otherwise be shown as the CPU
     * temperature.
     */
    private fun hottestCpuSensor(sensors: Observed<List<com.gamecore.core.model.ThermalSensor>>):
        Observed<Int>? {
        val list = sensors.valueOrNull ?: return null
        val hottest = list.filter { it.isCpuZone }.maxByOrNull { it.deciCelsius } ?: return null
        return Observed.of(hottest.deciCelsius, DataSource.DUMPSYS_SHIZUKU)
    }

    // ------------------------------------------------------- delegated unchanged

    /**
     * Battery and storage come from platform APIs that cannot be withheld from an app,
     * so there is nothing for a shell to add. `dumpsys battery` would supply the same
     * numbers as parsed text, which is strictly worse.
     */
    override suspend fun readBattery(): BatteryReading = delegate.readBattery()

    override suspend fun readStorage(): StorageReading = delegate.readStorage()
}
