package com.gamecore.core.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.gamecore.core.common.AccessLevel
import com.gamecore.core.common.DataSource
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import com.gamecore.core.model.BatteryHealth
import com.gamecore.core.model.BatteryReading
import com.gamecore.core.model.BatteryStatus
import com.gamecore.core.model.ChargingSource
import com.gamecore.core.model.CpuReading
import com.gamecore.core.model.MemoryReading
import com.gamecore.core.model.StorageReading
import com.gamecore.core.model.ThermalReading
import com.gamecore.core.model.ThermalStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything GameCore can read with no special access at all.
 *
 * This is the reader that always works, and the one the app is built around: a user who
 * never installs Shizuku and grants nothing gets a real performance monitor from this
 * class alone. The elevated reader adds fields, it does not replace this one.
 *
 * The pattern throughout is platform-API-first, `/proc`-second. `ActivityManager
 * .getMemoryInfo()` cannot be withheld from an app and `/proc/meminfo` can, so total and
 * available memory come from the former and the detail from the latter. Each `/proc`
 * field carries its own absence, so a device that exposes utilisation but not swap shows
 * the first and explains the second rather than dropping both.
 */
@Singleton
class StandardMetricsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val procFs: ProcFsReader,
    private val cpuSampler: CpuSampler,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MetricsReader {

    override val accessLevel: AccessLevel = AccessLevel.NORMAL

    /** Always. This reader has no dependency that can be absent. */
    override suspend fun isAvailable(): Boolean = true

    override fun resetSampling() = cpuSampler.reset()

    private val coreCount: Int =
        Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    // ------------------------------------------------------------------------ cpu

    override suspend fun readCpu(): CpuReading = withContext(io) {
        val systemTimes = procFs.readSystemCpuTimes()
        val perCoreTimes = procFs.readPerCoreCpuTimes()
        CpuReading(
            coreCount = coreCount,
            overallPercent = cpuSampler.sampleSystem(systemTimes),
            perCorePercent = cpuSampler.samplePerCore(perCoreTimes),
            frequenciesKHz = procFs.readCoreFrequencies(coreCount),
            loadAverage = procFs.readLoadAverage(),
            temperatureDeciCelsius = procFs.readCpuTemperature(),
        )
    }

    // --------------------------------------------------------------------- memory

    /**
     * Memory, from the two sources in order of reliability.
     *
     * `MemoryInfo.availMem` is the platform's own figure for what an app can allocate,
     * and it is the one every other tool on the phone shows. `/proc/meminfo` supplies
     * cached, free and swap, which the platform does not expose through any API — and
     * which is why those four fields are individually absent on a kernel that denies
     * the read.
     */
    override suspend fun readMemory(): MemoryReading = withContext(io) {
        val activityManager = systemService<ActivityManager>(Context.ACTIVITY_SERVICE)
        val info = ActivityManager.MemoryInfo()
        val platformOk = try {
            activityManager?.getMemoryInfo(info)
            activityManager != null && info.totalMem > 0L
        } catch (error: Throwable) {
            false
        }

        val meminfo = procFs.readMemInfo()
        fun kb(key: String): Observed<Long> = when (meminfo) {
            is Observed.Value -> meminfo.value[key]
                ?.let { Observed.of(it * 1024L, DataSource.PROC_FS) }
                ?: Observed.notPresent("This kernel does not report $key")
            is Observed.Restricted -> meminfo
            is Observed.Failed -> meminfo
        }

        // The fallback matters on the handful of devices where the service is present
        // but returns zeroes: without it the whole screen would read 0 GB of 0 GB.
        val totalFromProc = (meminfo as? Observed.Value)?.value?.get("MemTotal")?.times(1024L)
        val availableFromProc = (meminfo as? Observed.Value)?.value?.get("MemAvailable")?.times(1024L)

        val total = if (platformOk) info.totalMem else totalFromProc ?: 0L
        val available = if (platformOk) info.availMem else availableFromProc ?: 0L

        if (total <= 0L) {
            return@withContext MemoryReading.EMPTY
        }

        MemoryReading(
            totalBytes = total,
            availableBytes = available,
            cachedBytes = kb("Cached"),
            freeBytes = kb("MemFree"),
            swapTotalBytes = kb("SwapTotal"),
            swapFreeBytes = kb("SwapFree"),
            lowMemoryThresholdBytes = if (platformOk && info.threshold > 0L) {
                Observed.of(info.threshold, DataSource.ACTIVITY_MANAGER)
            } else {
                Observed.notPresent("This device does not report a low-memory threshold")
            },
            isLowMemory = platformOk && info.lowMemory,
        )
    }

    // -------------------------------------------------------------------- battery

    /**
     * Battery, from the sticky `ACTION_BATTERY_CHANGED` broadcast.
     *
     * `registerReceiver(null, filter)` returns the last broadcast without registering
     * anything, which is the documented way to read the current state and costs no
     * receiver lifecycle. Level and charging state are always present in it.
     *
     * Everything else is optional in the platform's own contract, and a device that does
     * not implement a property returns 0, -1 or `Integer.MIN_VALUE` rather than omitting
     * the extra — so every one is checked against a physically plausible range before it
     * is reported, and is otherwise absent. A phone battery is not at -273 °C and does
     * not hold 40 volts.
     */
    override suspend fun readBattery(): BatteryReading = withContext(io) {
        val intent = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (error: Throwable) {
            null
        } ?: return@withContext BatteryReading.EMPTY

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            (level * 100f / scale).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val statusRaw = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pluggedRaw = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = when (statusRaw) {
            BatteryManager.BATTERY_STATUS_CHARGING -> BatteryStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> BatteryStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryStatus.NOT_CHARGING
            else -> BatteryStatus.UNKNOWN
        }

        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        val healthRaw = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)

        BatteryReading(
            levelPercent = percent,
            // Both signals are checked: some builds report CHARGING with nothing
            // plugged in while a wireless pad is negotiating, and others leave the
            // status at UNKNOWN while EXTRA_PLUGGED is set.
            isCharging = status == BatteryStatus.CHARGING ||
                status == BatteryStatus.FULL ||
                pluggedRaw != 0,
            chargingSource = when {
                pluggedRaw == 0 -> ChargingSource.NONE
                pluggedRaw and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargingSource.AC
                pluggedRaw and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargingSource.USB
                pluggedRaw and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargingSource.WIRELESS
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    pluggedRaw and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> ChargingSource.DOCK
                else -> ChargingSource.UNKNOWN
            },
            status = status,
            health = when (healthRaw) {
                BatteryManager.BATTERY_HEALTH_GOOD -> Observed.of(BatteryHealth.GOOD, SOURCE)
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> Observed.of(BatteryHealth.OVERHEAT, SOURCE)
                BatteryManager.BATTERY_HEALTH_DEAD -> Observed.of(BatteryHealth.DEAD, SOURCE)
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                    Observed.of(BatteryHealth.OVER_VOLTAGE, SOURCE)
                BatteryManager.BATTERY_HEALTH_COLD -> Observed.of(BatteryHealth.COLD, SOURCE)
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE ->
                    Observed.of(BatteryHealth.UNSPECIFIED_FAILURE, SOURCE)
                else -> Observed.notPresent("This device does not report battery health")
            },
            // -30 °C to 90 °C. Outside that the extra is a sentinel, not a reading.
            temperatureDeciCelsius = temperature.takeIf { it in -300..900 }
                ?.let { Observed.of(it, SOURCE) }
                ?: Observed.notPresent("This device does not report battery temperature"),
            // 1 V to 20 V, which covers single cells through to a 4S pack.
            voltageMilliVolts = voltage.takeIf { it in 1_000..20_000 }
                ?.let { Observed.of(it, SOURCE) }
                ?: Observed.notPresent("This device does not report battery voltage"),
            currentMicroAmps = readCurrentMicroAmps(),
            isPowerSaveMode = isPowerSaveMode(),
        )
    }

    /**
     * Instantaneous current, as a magnitude.
     *
     * `BATTERY_PROPERTY_CURRENT_NOW` is microamps on most devices and milliamps on a
     * few, with no way to tell which, and the sign convention is unspecified — some
     * OEMs report discharge as negative, others as positive. So the magnitude is
     * reported and `isCharging` gives the direction.
     *
     * Zero and `Int.MIN_VALUE` are both "not implemented" in practice; a phone drawing
     * genuinely 0 µA is off. Anything above 20 A is not a phone, so it is a
     * misinterpreted unit and is rejected rather than shown as 12,000 mA.
     */
    private fun readCurrentMicroAmps(): Observed<Int> {
        val manager = systemService<BatteryManager>(Context.BATTERY_SERVICE)
            ?: return Observed.notPresent("This device has no battery service")
        return try {
            val raw = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val magnitude = if (raw == Int.MIN_VALUE) 0 else kotlin.math.abs(raw)
            if (magnitude == 0 || magnitude > 20_000_000) {
                Observed.notPresent("This device does not report battery current")
            } else {
                Observed.of(magnitude, DataSource.BATTERY_MANAGER, Precision.EXACT)
            }
        } catch (error: Throwable) {
            Observed.notPresent("This device does not report battery current")
        }
    }

    private fun isPowerSaveMode(): Boolean = try {
        systemService<PowerManager>(Context.POWER_SERVICE)?.isPowerSaveMode == true
    } catch (error: Throwable) {
        false
    }

    // -------------------------------------------------------------------- thermal

    /**
     * Thermal state, from the platform's verdict and the raw zones separately.
     *
     * `getCurrentThermalStatus()` (API 29+) is coarse — six steps — and authoritative:
     * it is the only signal that reflects what the system is actually *doing* about the
     * heat. GameCore's alerts key off it rather than off a temperature threshold,
     * because 71 °C is alarming on one phone and normal on the next, and only the
     * platform knows which.
     *
     * The zone list is precise and vendor-defined, so it is shown as detail beside the
     * status rather than being used to derive one.
     */
    override suspend fun readThermal(): ThermalReading = withContext(io) {
        val statusSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val status = if (!statusSupported) {
            Observed.needsNewerApi("Android 10 introduced the thermal status API")
        } else {
            try {
                val pm = systemService<PowerManager>(Context.POWER_SERVICE)
                if (pm == null) {
                    Observed.notPresent("This device has no power service")
                } else {
                    ThermalStatus.fromPlatform(pm.currentThermalStatus)
                        ?.let { Observed.of(it, DataSource.POWER_MANAGER) }
                        ?: Observed.Failed("The platform reported an unrecognised thermal status")
                }
            } catch (error: Throwable) {
                Observed.Failed("The thermal status could not be read", error.message)
            }
        }

        ThermalReading(
            status = status,
            cpuTemperatureDeciCelsius = procFs.readCpuTemperature(),
            sensors = procFs.readThermalSensors(),
            statusSupported = statusSupported,
        )
    }

    // -------------------------------------------------------------------- storage

    /**
     * Internal storage, from `StatFs` on GameCore's own data directory.
     *
     * An app can always stat its own filesystem, so this is not [Observed]-wrapped.
     * What it cannot see is the physical device size: the figure here is the
     * user-visible partition, which is what every other app on the phone shows and is
     * smaller than the number on the box.
     */
    override suspend fun readStorage(): StorageReading = withContext(io) {
        try {
            val stat = StatFs(context.filesDir.absolutePath)
            StorageReading(
                totalBytes = stat.blockCountLong * stat.blockSizeLong,
                availableBytes = stat.availableBlocksLong * stat.blockSizeLong,
            )
        } catch (error: Throwable) {
            StorageReading.EMPTY
        }
    }

    // ---------------------------------------------------------------------- utils

    @Suppress("UNCHECKED_CAST")
    private fun <T> systemService(name: String): T? = try {
        context.getSystemService(name) as? T
    } catch (error: Throwable) {
        null
    }

    private companion object {
        val SOURCE = DataSource.BATTERY_BROADCAST
    }
}
