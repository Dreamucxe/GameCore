package com.gamecore.domain.intelligence

import com.gamecore.core.model.FindingKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIntelligenceTest {

    @Test
    fun `report names strengths only from supplied readings`() {
        val report = DeviceIntelligence.buildReport(deviceScan())

        assertTrue(report.strengths.any { it.title == "Plenty of memory" })
        assertTrue(report.strengths.any { it.title == "Variable refresh panel" })
        assertTrue(report.findings.all { it.evidence.isNotBlank() })
        assertFalse(report.findings.any { it.detail.contains("score", ignoreCase = true) })
    }

    @Test
    fun `missing modes and tight memory become limits rather than invented values`() {
        val scan = deviceScan(
            totalRamBytes = 4L * 1024 * 1024 * 1024,
            availableRamBytes = 200L * 1024 * 1024,
            supportedRefreshRates = emptyList(),
            currentRefreshRate = null,
        )
        val report = DeviceIntelligence.buildReport(scan)

        assertTrue(report.findings.any {
            it.kind == FindingKind.LIMITATION && it.title == "Limited memory"
        })
        assertTrue(report.findings.any {
            it.kind == FindingKind.LIMITATION && it.title == "Display modes are unreadable"
        })
        assertFalse(report.opportunities.any { it.title.contains("Refresh-rate control") })
    }
}
