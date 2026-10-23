package com.gamecore.core.fitness

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.model.ThermalStatus
import com.gamecore.core.permissions.GamePermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure [buildReport] verdict rules. */
class DeviceFitnessTest {

    private val baseInputs = FitnessInputs(
        hasGyro = true,
        hasAccel = true,
        overlayPerm = true,
        usageAccess = true,
        batteryExempt = true,
        notifications = true,
        currentHz = 120f,
        peakHz = 120f,
        thermalStatus = Observed.of(ThermalStatus.NONE, DataSource.POWER_MANAGER),
        powerSaver = false,
        renderer = "Adreno 730",
        vulkanSupport = true,
    )

    @Test
    fun `gyroscope READY when present, ACTION_NEEDED when absent`() {
        var report = buildReport(baseInputs.copy(hasGyro = true))
        var check = report.checks.first { it.id == "gyroscope" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(hasGyro = false))
        check = report.checks.first { it.id == "gyroscope" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Not on this device", check.fixAction)
        assertEquals(null, check.fixRoute)
    }

    @Test
    fun `accelerometer READY when present, ACTION_NEEDED when absent`() {
        var report = buildReport(baseInputs.copy(hasAccel = true))
        var check = report.checks.first { it.id == "accelerometer" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(hasAccel = false))
        check = report.checks.first { it.id == "accelerometer" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Not on this device", check.fixAction)
    }

    @Test
    fun `overlay permission READY when granted, ACTION_NEEDED with fixRoute when not`() {
        var report = buildReport(baseInputs.copy(overlayPerm = true))
        var check = report.checks.first { it.id == "overlay_permission" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(overlayPerm = false))
        check = report.checks.first { it.id == "overlay_permission" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Grant", check.fixAction)
        assertEquals(GamePermission.OVERLAY, check.fixRoute)
    }

    @Test
    fun `usage access READY when granted, ACTION_NEEDED with fixRoute when not`() {
        var report = buildReport(baseInputs.copy(usageAccess = true))
        var check = report.checks.first { it.id == "usage_access" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(usageAccess = false))
        check = report.checks.first { it.id == "usage_access" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Grant", check.fixAction)
        assertEquals(GamePermission.USAGE_ACCESS, check.fixRoute)
    }

    @Test
    fun `battery exemption READY when exempt, ACTION_NEEDED with fixRoute when not`() {
        var report = buildReport(baseInputs.copy(batteryExempt = true))
        var check = report.checks.first { it.id == "battery_exemption" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(batteryExempt = false))
        check = report.checks.first { it.id == "battery_exemption" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Exempt", check.fixAction)
        assertEquals(GamePermission.BATTERY_OPTIMISATION_EXEMPTION, check.fixRoute)
    }

    @Test
    fun `notifications READY when granted, ACTION_NEEDED with fixRoute when not`() {
        var report = buildReport(baseInputs.copy(notifications = true))
        var check = report.checks.first { it.id == "notifications" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(notifications = false))
        check = report.checks.first { it.id == "notifications" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Allow", check.fixAction)
        assertEquals(GamePermission.POST_NOTIFICATIONS, check.fixRoute)
    }

    @Test
    fun `refresh rate READY when current == peak, LIMITED when lower, ACTION_NEEDED when unreadable`() {
        // READY: current == peak
        var report = buildReport(baseInputs.copy(currentHz = 120f, peakHz = 120f))
        var check = report.checks.first { it.id == "refresh_rate" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        // LIMITED: current < peak
        report = buildReport(baseInputs.copy(currentHz = 60f, peakHz = 120f))
        check = report.checks.first { it.id == "refresh_rate" }
        assertEquals(FitnessVerdict.LIMITED, check.verdict)
        assertEquals("Set peak rate", check.fixAction)

        // ACTION_NEEDED: unreadable
        report = buildReport(baseInputs.copy(currentHz = null, peakHz = 120f))
        check = report.checks.first { it.id == "refresh_rate" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
        assertEquals("Open display settings", check.fixAction)

        report = buildReport(baseInputs.copy(currentHz = 120f, peakHz = null))
        check = report.checks.first { it.id == "refresh_rate" }
        assertEquals(FitnessVerdict.ACTION_NEEDED, check.verdict)
    }

    @Test
    fun `thermal status READY for NONE and LIGHT, LIMITED for MODERATE and SEVERE, ACTION_NEEDED for CRITICAL and EMERGENCY, LIMITED when unreadable`() {
        val statusesReady = listOf(ThermalStatus.NONE, ThermalStatus.LIGHT)
        val statusesLimited = listOf(ThermalStatus.MODERATE, ThermalStatus.SEVERE)
        val statusesAction = listOf(ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY)

        statusesReady.forEach { status ->
            val report = buildReport(baseInputs.copy(thermalStatus = Observed.of(status, DataSource.POWER_MANAGER)))
            val check = report.checks.first { it.id == "thermal_status" }
            assertEquals("Thermal $status should be READY", FitnessVerdict.READY, check.verdict)
        }

        statusesLimited.forEach { status ->
            val report = buildReport(baseInputs.copy(thermalStatus = Observed.of(status, DataSource.POWER_MANAGER)))
            val check = report.checks.first { it.id == "thermal_status" }
            assertEquals("Thermal $status should be LIMITED", FitnessVerdict.LIMITED, check.verdict)
            assertEquals("Cool down", check.fixAction)
        }

        statusesAction.forEach { status ->
            val report = buildReport(baseInputs.copy(thermalStatus = Observed.of(status, DataSource.POWER_MANAGER)))
            val check = report.checks.first { it.id == "thermal_status" }
            assertEquals("Thermal $status should be ACTION_NEEDED", FitnessVerdict.ACTION_NEEDED, check.verdict)
            assertEquals("Critical — cool immediately", check.fixAction)
        }

        // Unreadable (null or Restricted/Failed)
        val reportNull = buildReport(baseInputs.copy(thermalStatus = null))
        val checkNull = reportNull.checks.first { it.id == "thermal_status" }
        assertEquals(FitnessVerdict.LIMITED, checkNull.verdict)

        val reportRestricted = buildReport(baseInputs.copy(
            thermalStatus = Observed.notPresent("No thermal service")
        ))
        val checkRestricted = reportRestricted.checks.first { it.id == "thermal_status" }
        assertEquals(FitnessVerdict.LIMITED, checkRestricted.verdict)
    }

    @Test
    fun `power saver READY when off, LIMITED when on`() {
        var report = buildReport(baseInputs.copy(powerSaver = false))
        var check = report.checks.first { it.id == "power_saver" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(powerSaver = true))
        check = report.checks.first { it.id == "power_saver" }
        assertEquals(FitnessVerdict.LIMITED, check.verdict)
        assertEquals("Disable", check.fixAction)
        assertEquals(null, check.fixRoute)
    }

    @Test
    fun `GPU renderer READY when readable, LIMITED when not`() {
        var report = buildReport(baseInputs.copy(renderer = "Adreno 730"))
        var check = report.checks.first { it.id == "gpu_renderer" }
        assertEquals(FitnessVerdict.READY, check.verdict)

        report = buildReport(baseInputs.copy(renderer = null))
        check = report.checks.first { it.id == "gpu_renderer" }
        assertEquals(FitnessVerdict.LIMITED, check.verdict)
        assertEquals(null, check.fixAction)
        assertEquals(null, check.fixRoute)
    }

    @Test
    fun `overall verdict is worst of individual verdicts`() {
        // All READY
        var report = buildReport(baseInputs)
        assertEquals(FitnessVerdict.READY, report.overall)

        // One LIMITED
        report = buildReport(baseInputs.copy(powerSaver = true))
        assertEquals(FitnessVerdict.LIMITED, report.overall)

        // One ACTION_NEEDED
        report = buildReport(baseInputs.copy(overlayPerm = false))
        assertEquals(FitnessVerdict.ACTION_NEEDED, report.overall)

        // Multiple ACTION_NEEDED
        report = buildReport(baseInputs.copy(overlayPerm = false, usageAccess = false))
        assertEquals(FitnessVerdict.ACTION_NEEDED, report.overall)
    }

    @Test
    fun `summary reflects overall verdict and counts`() {
        // All READY
        var report = buildReport(baseInputs)
        assertTrue(report.summary.contains("All 10 checks passed"))

        // One LIMITED
        report = buildReport(baseInputs.copy(powerSaver = true))
        assertTrue(report.summary.contains("1 limited"))
        assertTrue(report.summary.contains("9 ready"))

        // One ACTION_NEEDED
        report = buildReport(baseInputs.copy(overlayPerm = false))
        assertTrue(report.summary.contains("1 action needed"))
        assertTrue(report.summary.contains("9 ready"))

        // Mixed
        report = buildReport(baseInputs.copy(overlayPerm = false, powerSaver = true))
        assertTrue(report.summary.contains("1 action needed"))
        assertTrue(report.summary.contains("1 limited"))
        assertTrue(report.summary.contains("8 ready"))
    }

    @Test
    fun `exactly ten checks are produced`() {
        val report = buildReport(baseInputs)
        assertEquals(10, report.checks.size)

        val expectedIds = setOf(
            "gyroscope", "accelerometer", "overlay_permission", "usage_access",
            "battery_exemption", "notifications", "refresh_rate", "thermal_status",
            "power_saver", "gpu_renderer"
        )
        val actualIds = report.checks.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)
    }

    @Test
    fun `FitnessVerdict ordering is ACTION_NEEDED before LIMITED before READY`() {
        val sorted = listOf(
            FitnessVerdict.READY,
            FitnessVerdict.LIMITED,
            FitnessVerdict.ACTION_NEEDED,
        ).sorted()

        assertEquals(listOf(
            FitnessVerdict.ACTION_NEEDED,
            FitnessVerdict.LIMITED,
            FitnessVerdict.READY,
        ), sorted)
    }
}