package com.gamecore.ui.fitness

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.capability.GraphicsCapabilityReader
import com.gamecore.core.common.Observed
import com.gamecore.core.fitness.FitnessInputs
import com.gamecore.core.fitness.FitnessReport
import com.gamecore.core.fitness.buildReport
import com.gamecore.core.permissions.GamePermission
import com.gamecore.core.permissions.PermissionChecker
import com.gamecore.core.sensors.MotionSampler
import com.gamecore.core.sensors.MotionSensorKind
import com.gamecore.core.system.DisplayReader
import com.gamecore.domain.monitoring.PerformanceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Device fitness check ViewModel.
 *
 * Collects inputs from the existing injectable readers/checkers, feeds them to the pure
 * [buildReport] function, and exposes a [StateFlow<FitnessReport>]. The report is
 * recomputed when any input changes (permissions, thermal, display) or on explicit
 * [refresh].
 *
 * Mirrors [CapabilityViewModel]'s pattern: one combined state update, cached readers,
 * and a refresh action for user-initiated rescans.
 */
@HiltViewModel
class FitnessViewModel @Inject constructor(
    private val capabilityChecker: com.gamecore.domain.optimization.DeviceCapabilityChecker,
    private val permissions: PermissionChecker,
    private val motionSampler: MotionSampler,
    private val graphics: GraphicsCapabilityReader,
    private val displayReader: DisplayReader,
    private val performanceMonitor: PerformanceMonitor,
    @com.gamecore.core.common.IoDispatcher private val io: CoroutineDispatcher,
    @com.gamecore.core.common.ApplicationScope private val scope: CoroutineScope,
) : ViewModel() {

    private val editing = MutableStateFlow(FitnessReport.empty())

    val report: StateFlow<FitnessReport> = editing.asStateFlow()

    init {
        load()
    }

    /**
     * Re-reads every input and rebuilds the report.
     *
     * Called on init and when the user taps Refresh. Runs on IO so the EGL context
     * creation (graphics) and display reads do not block the main thread.
     */
    fun refresh() = load()

    /**
     * Returns a Settings intent for the given permission, or null if none exists.
     * The caller is responsible for launching it.
     */
    fun settingsIntentFor(permission: GamePermission): Intent? =
        permissions.settingsIntentFor(permission)

    private fun load() {
        viewModelScope.launch {
            editing.value = FitnessReport.empty()
            val report = withContext(io) { collectAndBuild() }
            editing.value = report
        }
    }

    /**
     * Gathers all inputs from the injectable components and builds the pure report.
     *
     * Each input is read once per refresh. The readers either cache (graphics, display)
     * or are cheap (permission checks, motion sampler presence, thermal from the
     * shared PerformanceMonitor snapshot).
     */
    private suspend fun collectAndBuild(): FitnessReport {
        // Sensor presence — MotionSampler describes sensors synchronously
        val gyroDesc = motionSampler.describe(MotionSensorKind.GYROSCOPE)
        val accelDesc = motionSampler.describe(MotionSensorKind.ACCELEROMETER)
        val hasGyro = gyroDesc is Observed.Value
        val hasAccel = accelDesc is Observed.Value

        // Permissions — PermissionChecker queries the platform each call
        val overlayPerm = permissions.hasOverlayPermission()
        val usageAccess = permissions.hasUsageAccess()
        val batteryExempt = permissions.isIgnoringBatteryOptimisations()
        val notifications = permissions.hasNotificationPermission()

        // Display rates — DisplayReader caches the mode table
        val displayReading = displayReader.read()
        val currentHz = (displayReading.currentRefreshRate as? com.gamecore.core.common.Observed.Value)?.value
        val peakHz = (displayReader.supportedRates() as? com.gamecore.core.common.Observed.Value)?.value?.firstOrNull()

        // Thermal + power saver — from the shared monitoring snapshot
        val snapshot = performanceMonitor.snapshots.value
        val thermalStatus = snapshot?.thermal?.status
        val powerSaver = snapshot?.battery?.isPowerSaveMode == true

        // GPU renderer + Vulkan — GraphicsCapabilityReader caches the EGL context read
        val graphicsReport = graphics.report()
        val renderer = (graphicsReport.renderer as? com.gamecore.core.common.Observed.Value)?.value
        val vulkanSupport = graphicsReport.vulkan.supported

        val inputs = FitnessInputs(
            hasGyro = hasGyro,
            hasAccel = hasAccel,
            overlayPerm = overlayPerm,
            usageAccess = usageAccess,
            batteryExempt = batteryExempt,
            notifications = notifications,
            currentHz = currentHz,
            peakHz = peakHz,
            thermalStatus = thermalStatus,
            powerSaver = powerSaver,
            renderer = renderer,
            vulkanSupport = vulkanSupport,
        )

        return buildReport(inputs)
    }
}