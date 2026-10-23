package com.gamecore.core.fitness

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.ThermalStatus
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.system.DisplayReader
import com.gamecore.core.system.StandardMetricsReader

/**
 * The device fitness report: what this device needs to run GameCore's features well,
 * and whether it is already there.
 *
 * Every check is a fact — a sensor present, a permission granted, a rate achievable —
 * not a guess from Build.MODEL. The verdict rules are pure functions, testable in isolation
 * and independent of Android. The ViewModel collects inputs, calls [buildReport], and
 * exposes the result; no logic lives in the UI layer.
 */
sealed class FitnessVerdict(val label: String, val rank: Int) : Comparable<FitnessVerdict> {
    /** All checks passed — the feature works as designed. */
    object READY : FitnessVerdict("Ready", 2)

    /** Functional, but limited — a lower rate, a missing sensor, a thermal throttle. */
    object LIMITED : FitnessVerdict("Limited", 1)

    /** Action required — a missing permission, a setting to change. */
    object ACTION_NEEDED : FitnessVerdict("Action needed", 0)

    override fun compareTo(other: FitnessVerdict): Int = rank.compareTo(other.rank)

    /** The overall verdict is the worst of the individual verdicts. */
    companion object {
        fun overall(checks: List<FitnessCheck>): FitnessVerdict =
            checks.map { it.verdict }.minOrNull() ?: READY
    }
}

/** One check, its outcome, and the action that fixes it when it is not READY. */
data class FitnessCheck(
    val id: String,
    val title: String,
    val description: String,
    val verdict: FitnessVerdict,
    /** A short label for the button ("Grant", "Open settings", "Exempt"). Null when READY. */
    val fixAction: String? = null,
    /** The Settings intent route key, understood by [PermissionChecker.settingsIntentFor]. Null when READY. */
    val fixRoute: GamePermission? = null,
)

/** The complete report. */
data class FitnessReport(
    val checks: List<FitnessCheck>,
    val overall: FitnessVerdict,
    val summary: String,
    /** True while a refresh is in progress. */
    val isLoading: Boolean = false,
) {
    companion object {
        /** Empty report shown before the first scan completes. */
        fun empty(): FitnessReport = FitnessReport(
            checks = emptyList(),
            overall = FitnessVerdict.READY,
            summary = "",
            isLoading = true,
        )
    }
}

/**
 * Inputs to the pure verdict engine. Every field is a fact the ViewModel can read
 * from the existing injectable readers/checkers. Nothing here is Android — the
 * types are primitives and Observed wrappers so the function is testable with
 * literals.
 */
data class FitnessInputs(
    /** Gyroscope present on this device. */
    val hasGyro: Boolean,
    /** Accelerometer present on this device. */
    val hasAccel: Boolean,
    /** Overlay permission (SYSTEM_ALERT_WINDOW) granted. */
    val overlayPerm: Boolean,
    /** Usage access (PACKAGE_USAGE_STATS) granted. */
    val usageAccess: Boolean,
    /** Battery optimisation exemption held. */
    val batteryExempt: Boolean,
    /** Notification permission (POST_NOTIFICATIONS on API 33+, granted at install below). */
    val notifications: Boolean,
    /** Current refresh rate (Hz), or null if unreadable. */
    val currentHz: Float?,
    /** Peak refresh rate the panel offers (Hz), or null if unreadable. */
    val peakHz: Float?,
    /** Platform thermal status, or null if unsupported/unreadable. */
    val thermalStatus: Observed<ThermalStatus>?,
    /** Battery saver / power saver active. */
    val powerSaver: Boolean,
    /** GPU renderer string, or null if unreadable. */
    val renderer: String?,
    /** Vulkan support reported by PackageManager. */
    val vulkanSupport: Boolean,
)

/**
 * Builds the fitness report from raw inputs. Pure, no Android, no I/O.
 *
 * The ten checks and their verdict rules:
 *
 * 1. Gyroscope — READY when present, ACTION_NEEDED when absent (no fix, UNSUPPORTED).
 * 2. Accelerometer — READY when present, ACTION_NEEDED when absent.
 * 3. Overlay permission — READY when granted, ACTION_NEEDED when not (fixRoute = OVERLAY).
 * 4. Usage access — READY when granted, ACTION_NEEDED when not (fixRoute = USAGE_ACCESS).
 * 5. Battery optimisation exemption — READY when exempt, ACTION_NEEDED when not (fixRoute = BATTERY_OPTIMISATION_EXEMPTION).
 * 6. Notifications — READY when granted, ACTION_NEEDED when not (fixRoute = POST_NOTIFICATIONS).
 * 7. Refresh rate — READY when currentHz == peakHz, LIMITED when currentHz < peakHz, ACTION_NEEDED when unreadable.
 * 8. Thermal status — READY when NONE/LIGHT, LIMITED when MODERATE/SEVERE, ACTION_NEEDED when CRITICAL/EMERGENCY, LIMITED when unreadable.
 * 9. Power saver — READY when off, LIMITED when on (reduces performance).
 * 10. GPU/Vulkan — READY when renderer readable, LIMITED when not (info only, no fix).
 */
fun buildReport(inputs: FitnessInputs): FitnessReport {
    val checks = mutableListOf<FitnessCheck>()

    // 1. Gyroscope
    checks.add(FitnessCheck(
        id = "gyroscope",
        title = "Gyroscope",
        description = "Rotation input for aim assist and motion calibration.",
        verdict = if (inputs.hasGyro) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.hasGyro) null else "Not on this device",
        fixRoute = null,
    ))

    // 2. Accelerometer
    checks.add(FitnessCheck(
        id = "accelerometer",
        title = "Accelerometer",
        description = "Linear motion input for shake gestures and orientation.",
        verdict = if (inputs.hasAccel) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.hasAccel) null else "Not on this device",
        fixRoute = null,
    ))

    // 3. Overlay permission
    checks.add(FitnessCheck(
        id = "overlay_permission",
        title = "Display over other apps",
        description = "Required for the floating HUD, crosshair and instant replay overlay.",
        verdict = if (inputs.overlayPerm) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.overlayPerm) null else "Grant",
        fixRoute = if (inputs.overlayPerm) null else GamePermission.OVERLAY,
    ))

    // 4. Usage access
    checks.add(FitnessCheck(
        id = "usage_access",
        title = "Usage access",
        description = "Required to detect the foreground game and apply per-game profiles.",
        verdict = if (inputs.usageAccess) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.usageAccess) null else "Grant",
        fixRoute = if (inputs.usageAccess) null else GamePermission.USAGE_ACCESS,
    ))

    // 5. Battery optimisation exemption
    checks.add(FitnessCheck(
        id = "battery_exemption",
        title = "Battery optimisation exemption",
        description = "Prevents the overlay service and background sampling from being throttled.",
        verdict = if (inputs.batteryExempt) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.batteryExempt) null else "Exempt",
        fixRoute = if (inputs.batteryExempt) null else GamePermission.BATTERY_OPTIMISATION_EXEMPTION,
    ))

    // 6. Notifications
    checks.add(FitnessCheck(
        id = "notifications",
        title = "Notifications",
        description = "Required for session alerts, thermal warnings and profile switches.",
        verdict = if (inputs.notifications) FitnessVerdict.READY else FitnessVerdict.ACTION_NEEDED,
        fixAction = if (inputs.notifications) null else "Allow",
        fixRoute = if (inputs.notifications) null else GamePermission.POST_NOTIFICATIONS,
    ))

    // 7. Refresh rate
    val refreshVerdict: FitnessVerdict = when {
        inputs.currentHz == null || inputs.peakHz == null -> FitnessVerdict.ACTION_NEEDED
        inputs.currentHz == inputs.peakHz -> FitnessVerdict.READY
        else -> FitnessVerdict.LIMITED
    }
    checks.add(FitnessCheck(
        id = "refresh_rate",
        title = "Refresh rate",
        description = "Panel running at its peak rate (${inputs.currentHz?.let { String.format("%.0f", it) }} Hz / ${inputs.peakHz?.let { String.format("%.0f", it) }} Hz).",
        verdict = refreshVerdict,
        fixAction = when (refreshVerdict) {
            FitnessVerdict.READY -> null
            FitnessVerdict.LIMITED -> "Set peak rate"
            FitnessVerdict.ACTION_NEEDED -> "Open display settings"
        },
        fixRoute = null, // No direct permission route; user goes to display settings manually
    ))

    // 8. Thermal status
    val thermalVerdict: FitnessVerdict = when {
        inputs.thermalStatus == null -> FitnessVerdict.LIMITED
        inputs.thermalStatus is Observed.Value -> when (inputs.thermalStatus.value) {
            ThermalStatus.NONE, ThermalStatus.LIGHT -> FitnessVerdict.READY
            ThermalStatus.MODERATE, ThermalStatus.SEVERE -> FitnessVerdict.LIMITED
            ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN ->
                FitnessVerdict.ACTION_NEEDED
        }
        else -> FitnessVerdict.LIMITED // unreadable
    }
    checks.add(FitnessCheck(
        id = "thermal_status",
        title = "Thermal status",
        description = "Device temperature state reported by the platform.",
        verdict = thermalVerdict,
        fixAction = when (thermalVerdict) {
            FitnessVerdict.READY -> null
            FitnessVerdict.LIMITED -> "Cool down"
            FitnessVerdict.ACTION_NEEDED -> "Critical — cool immediately"
        },
        fixRoute = null,
    ))

    // 9. Power saver
    checks.add(FitnessCheck(
        id = "power_saver",
        title = "Power saver",
        description = "Battery saver limits background work and may cap the GPU.",
        verdict = if (inputs.powerSaver) FitnessVerdict.LIMITED else FitnessVerdict.READY,
        fixAction = if (inputs.powerSaver) "Disable" else null,
        fixRoute = null,
    ))

    // 10. GPU renderer / Vulkan (info only)
    val gpuVerdict = if (inputs.renderer != null) FitnessVerdict.READY else FitnessVerdict.LIMITED
    checks.add(FitnessCheck(
        id = "gpu_renderer",
        title = "GPU renderer",
        description = "Graphics stack: ${inputs.renderer ?: "unreadable"} ${if (inputs.vulkanSupport) "• Vulkan" else ""}",
        verdict = gpuVerdict,
        fixAction = null,
        fixRoute = null,
    ))

    val overall = FitnessVerdict.overall(checks)
    val summary = buildSummary(overall, checks)

    return FitnessReport(checks, overall, summary)
}

private fun buildSummary(overall: FitnessVerdict, checks: List<FitnessCheck>): String {
    val actionNeeded = checks.count { it.verdict == FitnessVerdict.ACTION_NEEDED }
    val limited = checks.count { it.verdict == FitnessVerdict.LIMITED }
    val ready = checks.count { it.verdict == FitnessVerdict.READY }

    return when (overall) {
        FitnessVerdict.READY -> "All $ready checks passed. Device is fully fit for GameCore."
        FitnessVerdict.LIMITED -> "$limited limited, $ready ready. Some features may run at reduced capability."
        FitnessVerdict.ACTION_NEEDED -> "$actionNeeded action needed, $limited limited, $ready ready. Grant permissions or change settings to unlock full capability."
    }
}
