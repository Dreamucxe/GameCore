package com.gamecore.domain.intelligence

import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.DeviceFinding
import com.gamecore.core.model.DeviceIntelligenceReport
import com.gamecore.core.model.DeviceScan
import com.gamecore.core.model.FindingKind
import com.gamecore.core.model.FrameRateCapability
import com.gamecore.core.model.ThermalClass

/**
 * Turns a [DeviceScan] into the readable report the device screen shows.
 *
 * Pure, deterministic and Android-free: every sentence is derived from a field the scanner
 * actually read. There is deliberately no overall score. A single number would have to decide how
 * much one gigabyte of RAM is worth against one extra refresh mode, and that decision would be an
 * opinion presented as a measurement. The report instead groups facts into what helps, what limits
 * and what GameCore has verified it can change.
 *
 * Thresholds are display thresholds, not claims about what a phone can tolerate. They are constants
 * here so a test can pin each sentence to the input that produced it.
 */
object DeviceIntelligence {

    const val LOW_RAM_BYTES = 6L * 1024 * 1024 * 1024
    const val AMPLE_RAM_BYTES = 8L * 1024 * 1024 * 1024
    const val TIGHT_STORAGE_BYTES = 2L * 1024 * 1024 * 1024
    const val COMFORTABLE_STORAGE_BYTES = 4L * 1024 * 1024 * 1024
    const val LOW_AVAILABLE_RAM_FRACTION = 0.15f
    const val HEALTHY_AVAILABLE_RAM_FRACTION = 0.30f
    const val TIGHT_STORAGE_FRACTION = 0.08f
    const val HEALTHY_STORAGE_FRACTION = 0.15f
    const val MANY_CORES = 8

    fun buildReport(scan: DeviceScan): DeviceIntelligenceReport {
        val findings = buildList {
            addAll(strengths(scan))
            addAll(limitations(scan))
            addAll(opportunities(scan))
        }
        return DeviceIntelligenceReport(scan = scan, findings = findings)
    }

    private fun strengths(scan: DeviceScan): List<DeviceFinding> = buildList {
        if (scan.totalRamBytes >= AMPLE_RAM_BYTES) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Plenty of memory",
                    detail = "${gib(scan.totalRamBytes)} GB of RAM is enough headroom for demanding " +
                        "games and the system around them.",
                    evidence = "ActivityManager.MemoryInfo.totalMem",
                ),
            )
        }
        val availableFraction = availableRamFraction(scan)
        if (availableFraction != null && availableFraction >= HEALTHY_AVAILABLE_RAM_FRACTION) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Memory headroom right now",
                    detail = "${percent(availableFraction)} of RAM was free at the scan. That is " +
                        "measured now, not a promise about the next game.",
                    evidence = "ActivityManager.MemoryInfo.availMem",
                ),
            )
        }
        if (scan.coreCount >= MANY_CORES) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "${scan.coreCount} CPU threads reported",
                    detail = "The kernel reports ${scan.coreCount} logical cores. Cluster clocks are " +
                        "listed below when this device exposes them.",
                    evidence = "Runtime.availableProcessors()",
                ),
            )
        }
        if (scan.cpuClusters.size >= 2) {
            val clocks = scan.cpuClusters.map { it.maxKHz }.filter { it > 0L }.distinct()
            if (clocks.size >= 2) {
                add(
                    DeviceFinding(
                        kind = FindingKind.STRENGTH,
                        title = "CPU clusters are readable",
                        detail = "${scan.cpuClusters.size} cluster(s) with different clock bounds were " +
                            "read from sysfs, so affinity and clock behaviour can be described precisely.",
                        evidence = "/sys/devices/system/cpu/*/cpufreq",
                    ),
                )
            }
        }
        if (scan.hasVariableRefreshRate) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Variable refresh panel",
                    detail = "The display advertises ${scan.supportedRefreshRates.size} rates: " +
                        scan.supportedRefreshRates.joinToString(", ") { formatHz(it) } + ".",
                    evidence = "Display.getSupportedModes()",
                ),
            )
        }
        val storageFraction = storageAvailableFraction(scan)
        if (storageFraction != null &&
            storageFraction >= HEALTHY_STORAGE_FRACTION &&
            scan.storageAvailableBytes >= COMFORTABLE_STORAGE_BYTES
        ) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Storage headroom",
                    detail = "${gib(scan.storageAvailableBytes)} GB free on the data partition.",
                    evidence = "StatFs on the app data directory",
                ),
            )
        }
        if (scan.cpuTemperatureDeciCelsius != null || scan.thermalSensors.isNotEmpty()) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Thermal sensing is readable",
                    detail = if (scan.cpuTemperatureDeciCelsius != null) {
                        "A CPU-class zone reported ${formatTemperature(scan.cpuTemperatureDeciCelsius)}."
                    } else {
                        "${scan.thermalSensors.size} thermal zone(s) were readable, but none was " +
                            "identified as a CPU zone."
                    },
                    evidence = "/sys/class/thermal and the platform thermal service",
                ),
            )
        }
        if (scan.capabilities.hasElevatedAccess) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Privileged controls are available",
                    detail = "Shizuku is connected, so the operations that need it can be checked and " +
                        "attempted rather than reported as unavailable.",
                    evidence = "Shizuku state",
                ),
            )
        }
        if (scan.batteryCharging && scan.batteryLevelPercent >= 0) {
            add(
                DeviceFinding(
                    kind = FindingKind.STRENGTH,
                    title = "Charging during the scan",
                    detail = "Battery drain cannot be quoted from a charging session, but the reading is " +
                        "recorded so a later benchmark is not confused with this one.",
                    evidence = "ACTION_BATTERY_CHANGED",
                ),
            )
        }
    }

    private fun limitations(scan: DeviceScan): List<DeviceFinding> = buildList {
        if (scan.totalRamBytes in 1 until LOW_RAM_BYTES) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Limited memory",
                    detail = "${gib(scan.totalRamBytes)} GB total RAM. Heavy games may be killed in the " +
                        "background, and GameCore cannot add memory the device does not have.",
                    evidence = "ActivityManager.MemoryInfo.totalMem",
                ),
            )
        }
        val availableFraction = availableRamFraction(scan)
        if (availableFraction != null && availableFraction < LOW_AVAILABLE_RAM_FRACTION) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Memory is nearly full",
                    detail = "Only ${percent(availableFraction)} of RAM was free at the scan. GameCore " +
                        "will not promise that closing apps fixes this; Android's own memory manager " +
                        "makes that decision.",
                    evidence = "ActivityManager.MemoryInfo.availMem",
                ),
            )
        }
        val storageFraction = storageAvailableFraction(scan)
        if (scan.storageAvailableBytes in 1 until TIGHT_STORAGE_BYTES ||
            (storageFraction != null && storageFraction < TIGHT_STORAGE_FRACTION)
        ) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Storage is nearly full",
                    detail = "${gib(scan.storageAvailableBytes)} GB free. Recording, clips and updates " +
                        "need room, and a full filesystem also slows ordinary writes.",
                    evidence = "StatFs on the app data directory",
                ),
            )
        }
        if (scan.supportedRefreshRates.isEmpty()) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Display modes are unreadable",
                    detail = "This Android build did not return a supported-mode list, so no refresh-rate " +
                        "recommendation can be justified on this device.",
                    evidence = "Display.getSupportedModes()",
                ),
            )
        } else if (scan.supportedRefreshRates.size == 1) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "One refresh rate only",
                    detail = "The panel reports only ${formatHz(scan.supportedRefreshRates.first())}. " +
                        "There is no rate to pin or trade away.",
                    evidence = "Display.getSupportedModes()",
                ),
            )
        }
        if (!scan.capabilities.perCoreFrequenciesReadable) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "CPU clock bounds are hidden",
                    detail = "The kernel did not expose cpufreq to this app. CPU topology is known from " +
                        "the runtime, but per-core clock bounds are not.",
                    evidence = "/sys/devices/system/cpu/*/cpufreq",
                ),
            )
        }
        if (scan.cpuTemperatureDeciCelsius == null) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "No CPU temperature reading",
                    detail = if (scan.batteryTemperatureDeciCelsius != null) {
                        "Only the battery sensor was readable. Thermal advice will be based on the " +
                            "battery and the platform's thermal status, and the UI says so."
                    } else {
                        "Neither a CPU zone nor the battery sensor produced a temperature. Thermal " +
                            "recommendations will say they have no measurement rather than invent one."
                    },
                    evidence = "/sys/class/thermal; BatteryManager",
                ),
            )
        }
        if (scan.sdkInt >= 29 && !scan.capabilities.thermalStatusSupported) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Platform thermal status unavailable",
                    detail = "This build did not expose PowerManager's thermal status, so GameCore " +
                        "cannot tell when Android itself is throttling.",
                    evidence = "PowerManager.getCurrentThermalStatus()",
                ),
            )
        }
        if (scan.thermalSensors.isEmpty() && scan.cpuTemperatureDeciCelsius == null) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Thermal zones are unreadable",
                    detail = "No thermal zone could be read. Sustained-performance advice will stay " +
                        "conservative because there is no temperature evidence.",
                    evidence = "/sys/class/thermal",
                ),
            )
        }
        val frameRate = scan.capabilities.frameRate
        if (frameRate is FrameRateCapability.Unavailable && frameRate.reason != FrameRateCapability.NO_GAME_SELECTED) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Game frame rate is not measurable yet",
                    detail = frameRate.reason,
                    evidence = "Frame timing capability probe",
                ),
            )
        }
        if (scan.lowMemory) {
            add(
                DeviceFinding(
                    kind = FindingKind.LIMITATION,
                    title = "Android reports low memory",
                    detail = "The platform's low-memory flag was set during the scan. Background apps are " +
                        "being reclaimed by the system, not by GameCore.",
                    evidence = "ActivityManager.MemoryInfo.lowMemory",
                ),
            )
        }
    }

    private fun opportunities(scan: DeviceScan): List<DeviceFinding> = buildList {
        if (scan.capabilities.refreshRateMechanism != com.gamecore.core.model.RefreshRateMechanism.NONE &&
            scan.capabilities.refreshRateMechanism !=
            com.gamecore.core.model.RefreshRateMechanism.WINDOW_PREFERENCE &&
            scan.hasVariableRefreshRate
        ) {
            add(
                DeviceFinding(
                    kind = FindingKind.OPPORTUNITY,
                    title = "Refresh-rate control is verified",
                    detail = "GameCore established a working mechanism for pinning the panel. The pin " +
                        "changes the display mode; it does not create frames the game is not drawing.",
                    evidence = "RefreshRateController.mechanism()",
                ),
            )
        }
        if (scan.capabilities.batterySaverControl == CapabilityStatus.AVAILABLE) {
            add(
                DeviceFinding(
                    kind = FindingKind.OPPORTUNITY,
                    title = "Battery saver can be controlled",
                    detail = "The standard Android battery-saver toggle is writable through the verified " +
                        "mechanism. It is the same switch as Settings, not a GameCore-only mode.",
                    evidence = "OptimizationManager.statusFor(ENABLE_BATTERY_SAVER)",
                ),
            )
        }
        if (scan.capabilities.animationScaleControl == CapabilityStatus.AVAILABLE) {
            add(
                DeviceFinding(
                    kind = FindingKind.OPPORTUNITY,
                    title = "Window animations can be switched off",
                    detail = "The three system animation scales can be set to zero, removing window " +
                        "transition frames. This does not change rendering inside a game.",
                    evidence = "OptimizationManager.statusFor(DISABLE_ANIMATIONS)",
                ),
            )
        }
        if (scan.cpuTemperatureDeciCelsius != null || scan.capabilities.thermalStatusSupported) {
            add(
                DeviceFinding(
                    kind = FindingKind.OPPORTUNITY,
                    title = "Thermal behaviour can be watched",
                    detail = "A temperature source is available, so a benchmark can show whether " +
                        "performance falls as the device heats.",
                    evidence = "Thermal reading capability",
                ),
            )
        }
        if (!scan.capabilities.hasElevatedAccess) {
            val blocked = listOf(
                scan.capabilities.batterySaverControl,
                scan.capabilities.animationScaleControl,
                scan.capabilities.doNotDisturbControl,
            ).count { it == CapabilityStatus.REQUIRES_SHIZUKU }
            if (blocked > 0) {
                add(
                    DeviceFinding(
                        kind = FindingKind.OPPORTUNITY,
                        title = "Connect Shizuku to unlock $blocked control(s)",
                        detail = "These operations reported that they need elevated access. GameCore " +
                            "does not count them as available until the mechanism is actually usable.",
                        evidence = "Capability status",
                    ),
                )
            }
        }
        if (scan.capabilities.gameDetection == CapabilityStatus.AVAILABLE) {
            add(
                DeviceFinding(
                    kind = FindingKind.OPPORTUNITY,
                    title = "Game detection is ready",
                    detail = "GameCore can see the foreground game, which is what lets a benchmark " +
                        "capture the title that is actually running.",
                    evidence = "ForegroundAppWatcher.availability()",
                ),
            )
        }
    }

    private fun availableRamFraction(scan: DeviceScan): Float? =
        if (scan.totalRamBytes > 0L) {
            (scan.availableRamBytes.toFloat() / scan.totalRamBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    private fun storageAvailableFraction(scan: DeviceScan): Float? =
        if (scan.storageTotalBytes > 0L) {
            (scan.storageAvailableBytes.toFloat() / scan.storageTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    private fun gib(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))

    private fun percent(fraction: Float): String =
        "${Math.round(fraction * 100f)}%"

    private fun formatHz(hz: Float): String =
        if (hz % 1f == 0f) "${hz.toInt()} Hz" else String.format(java.util.Locale.US, "%.1f Hz", hz)

    private fun formatTemperature(deciCelsius: Int): String =
        String.format(java.util.Locale.US, "%.1f °C", deciCelsius / 10f)
}
