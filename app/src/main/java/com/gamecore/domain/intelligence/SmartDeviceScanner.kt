package com.gamecore.domain.intelligence

import android.os.Build
import com.gamecore.core.common.IoDispatcher
import com.gamecore.core.common.valueOrNull
import com.gamecore.core.model.CpuCluster
import com.gamecore.core.model.CpuClusterLayout
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.GpuClockSample
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.GpuClockReader
import com.gamecore.core.system.MetricsReader
import com.gamecore.core.system.ProcFsReader
import com.gamecore.domain.optimization.DeviceCapabilityChecker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One pass over the facts Smart Intelligence reasons from.
 *
 * The scanner adds no heuristics of its own. It reads what the existing readers already expose —
 * `Build`, `ActivityManager`, `Display`, `/sys/class/thermal`, the battery broadcast and the
 * capability checker — and puts it in one immutable snapshot. Anything that did not read stays null
 * and is named in [DeviceScan.unavailableReadings]; nothing is filled in from a device-name table or
 * a chipset family.
 *
 * The GPU model is deliberately not guessed. Android's public API exposes live GPU load on some
 * devices (through `/sys`), but no reliable general API for the renderer string. Reporting a name
 * derived from `Build.BOARD` or a SoC lookup would look authoritative and be wrong on exactly the
 * devices with unusual drivers, so [DeviceScan.gpuRenderer] and [DeviceScan.gpuVendor] remain null
 * with an explanation. The benchmark still records the GPU load the platform actually reports.
 */
@Singleton
class SmartDeviceScanner @Inject constructor(
    private val metrics: MetricsReader,
    private val procFs: ProcFsReader,
    private val displayReader: DisplayReader,
    private val capabilities: DeviceCapabilityChecker,
    private val gpuClockReader: GpuClockReader,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun scan(): DeviceScan = withContext(io) {
        val caps = capabilities.current()
        val display = displayReader.read()
        val memory = metrics.readMemory()
        val battery = metrics.readBattery()
        val thermal = metrics.readThermal()
        val storage = metrics.readStorage()
        val gpuClock = gpuClockReader.read()

        val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val frequencies = procFs.readCoreFrequencies(coreCount).valueOrNull.orEmpty()
        val clusters = cluster(frequencies)
        val sensors = thermal.sensors.valueOrNull.orEmpty()
        val cpuTemperature = thermal.cpuTemperatureDeciCelsius.valueOrNull
        val batteryTemperature = battery.temperatureDeciCelsius.valueOrNull
        val supportedRates = display.supportedRates.valueOrNull.orEmpty()
            .filter { it > 0f }
            .distinct()
            .sortedDescending()
        val unavailable = buildList {
            if (memory.totalBytes <= 0L) add("Total and available RAM were not reported.")
            if (supportedRates.isEmpty()) add("The display did not return a supported-mode list.")
            if (display.currentRefreshRate.valueOrNull == null) {
                add("The display did not report its current refresh rate.")
            }
            if (clusters.isEmpty()) add("Per-core CPU clock bounds were not readable.")
            if (cpuTemperature == null) add("No CPU-class thermal zone reported a temperature.")
            if (sensors.isEmpty()) add("No thermal zone was readable.")
            if (batteryTemperature == null) add("The battery did not report a temperature.")
            if (gpuClock.valueOrNull == null) {
                add("The kernel did not expose a GPU clock frequency on this device.")
            }
            if (storage.totalBytes <= 0L) add("Internal storage size was not readable.")
            if (Build.VERSION.SDK_INT < 31) {
                add("SoC manufacturer and model names need Android 12 or newer.")
            }
            add(
                "The GPU model is not exposed by the public Android API; GameCore reports live " +
                    "GPU load where the kernel exposes it and does not guess a name.",
            )
        }

        DeviceScan(
            scannedAtMillis = System.currentTimeMillis(),
            manufacturer = Build.MANUFACTURER.orEmpty().trim().ifBlank { "Unknown" },
            model = Build.MODEL.orEmpty().trim().ifBlank { "Unknown" },
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            },
            cpuArchitecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown",
            coreCount = coreCount,
            cpuClusters = clusters,
            gpuRenderer = null,
            gpuVendor = null,
            gpuClockMhz = gpuClock.valueOrNull?.currentMhz,
            gpuMaxClockMhz = gpuClock.valueOrNull?.maxMhz,
            gpuClockFrequenciesMhz = gpuClock.valueOrNull?.availableMhz.orEmpty(),
            gpuClockSource = gpuClock.valueOrNull?.nodeLabel,
            totalRamBytes = memory.totalBytes,
            availableRamBytes = memory.availableBytes,
            lowMemory = memory.isLowMemory,
            storageTotalBytes = storage.totalBytes,
            storageAvailableBytes = storage.availableBytes,
            displayWidthPixels = display.widthPixels,
            displayHeightPixels = display.heightPixels,
            displayRefreshRateHz = display.currentRefreshRate.valueOrNull,
            supportedRefreshRates = supportedRates,
            androidRelease = Build.VERSION.RELEASE.orEmpty().ifBlank { "Unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            kernelVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() },
            batteryLevelPercent = battery.levelPercent,
            batteryTemperatureDeciCelsius = batteryTemperature,
            batteryCharging = battery.isCharging,
            batteryPowerSave = battery.isPowerSaveMode,
            thermalSensors = sensors,
            cpuTemperatureDeciCelsius = cpuTemperature,
            platformThermalStatus = thermal.status.valueOrNull,
            capabilities = caps,
            unavailableReadings = unavailable,
        )
    }

    /** Reuses the affinity subsystem's strict all-cores cluster inference. */
    private fun cluster(frequencies: List<com.gamecore.core.model.CoreFrequency>): List<CpuCluster> =
        CpuClusterLayout.from(frequencies)?.clusters.orEmpty()
}
