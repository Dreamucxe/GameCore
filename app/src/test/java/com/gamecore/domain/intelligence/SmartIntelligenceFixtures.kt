package com.gamecore.domain.intelligence

import com.gamecore.core.model.BenchmarkProfile
import com.gamecore.core.model.BenchmarkRun
import com.gamecore.core.model.BenchmarkSummary
import com.gamecore.core.model.DeviceCapabilities
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.OptimizationGoal
import com.gamecore.core.model.RefreshRateMechanism

internal fun deviceScan(
    totalRamBytes: Long = 8L * 1024 * 1024 * 1024,
    availableRamBytes: Long = 4L * 1024 * 1024 * 1024,
    storageAvailableBytes: Long = 32L * 1024 * 1024 * 1024,
    currentRefreshRate: Float? = 60f,
    supportedRefreshRates: List<Float> = listOf(60f, 120f),
    cpuTemperatureDeciCelsius: Int? = 400,
    batteryPowerSave: Boolean = false,
    capabilities: DeviceCapabilities = DeviceCapabilities.UNKNOWN.copy(
        refreshRateMechanism = RefreshRateMechanism.SYSTEM_SETTINGS,
        supportedRefreshRates = supportedRefreshRates,
        hasVariableRefreshRate = supportedRefreshRates.size > 1,
        perCoreFrequenciesReadable = true,
        temperatureReadable = cpuTemperatureDeciCelsius != null,
    ),
) = DeviceScan(
    scannedAtMillis = 1_000L,
    manufacturer = "Test",
    model = "Device",
    socManufacturer = "Test",
    socModel = "SoC",
    cpuArchitecture = "arm64-v8a",
    coreCount = 8,
    cpuClusters = emptyList(),
    gpuRenderer = null,
    gpuVendor = null,
    totalRamBytes = totalRamBytes,
    availableRamBytes = availableRamBytes,
    lowMemory = false,
    storageTotalBytes = 128L * 1024 * 1024 * 1024,
    storageAvailableBytes = storageAvailableBytes,
    displayWidthPixels = 1080,
    displayHeightPixels = 2400,
    displayRefreshRateHz = currentRefreshRate,
    supportedRefreshRates = supportedRefreshRates,
    androidRelease = "15",
    sdkInt = 35,
    kernelVersion = "test",
    batteryLevelPercent = 75,
    batteryTemperatureDeciCelsius = 320,
    batteryCharging = false,
    batteryPowerSave = batteryPowerSave,
    thermalSensors = emptyList(),
    cpuTemperatureDeciCelsius = cpuTemperatureDeciCelsius,
    platformThermalStatus = null,
    capabilities = capabilities,
    unavailableReadings = emptyList(),
)

internal fun benchmarkRun(
    id: Long,
    fps: Float?,
    temperatureDeci: Int? = 500,
    gamePackage: String? = "game.pkg",
    profile: BenchmarkProfile = BenchmarkProfile.OPTIMIZATION_COMPARISON,
) = BenchmarkRun(
    id = id,
    profile = profile,
    gamePackage = gamePackage,
    gameLabel = "Game",
    goal = OptimizationGoal.STABLE_FPS,
    startedAtMillis = id * 1_000L,
    endedAtMillis = id * 1_000L + 300_000L,
    summary = BenchmarkSummary.EMPTY.copy(
        sampleCount = 150,
        durationMillis = 300_000L,
        averageFps = fps,
        lowestWindowFps = fps?.minus(2f),
        onePercentLowFps = fps?.minus(3f),
        averageTemperatureDeciCelsius = temperatureDeci,
        peakTemperatureDeciCelsius = temperatureDeci?.plus(20),
    ),
)
