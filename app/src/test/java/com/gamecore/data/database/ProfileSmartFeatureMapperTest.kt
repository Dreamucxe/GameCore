package com.gamecore.data.database

import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.ThermalClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3.5 smart-feature columns survive the profile/session round trip, and — the part the migration rule
 * is really about — an entity built the way [MIGRATION_10_11] leaves a pre-3.5 row (every new column at its
 * default) reads back as the features switched off.
 *
 * The migration itself needs an instrumented `MigrationTestHelper` and a device, so it lives in the
 * androidTest set and is listed in the manual checklist. What is unit-testable, and what actually protects
 * the user's data, is the mapper: if it read a defaulted row as anything other than "off", the upgrade
 * would silently turn features on for every existing profile. That is what this pins.
 */
class ProfileSmartFeatureMapperTest {

    // A profile entity the way the migration leaves an upgraded pre-3.5 row: the old columns carry real
    // values, every 3.5 column is at the DEFAULT the ALTER TABLE set (Kotlin defaults mirror those exactly).
    private fun legacyEntity() = GameProfileEntity(
        packageName = "com.example.game",
        label = "Example",
        isEnabled = true,
        targetRefreshRate = 120f,
        brightnessPercent = null,
        rotationLock = null,
        screenTimeoutMillis = null,
        mediaVolumePercent = null,
        enableDoNotDisturb = false,
        showFloatingButton = true,
        showPerformancePill = false,
        showCrosshair = false,
        hudLayoutId = null,
        crosshairPresetId = null,
        colorPresetId = null,
        displaySize = null,
        performanceMode = "BALANCED",
        useShizukuOptimizations = false,
        freeRamOnLaunch = false,
        trackSession = true,
        cpuAffinity = null,
        updatedAtMillis = 0L,
    )

    @Test
    fun `a defaulted pre-3-5 profile reads back with every smart feature off`() {
        val profile = Mappers.toModel(legacyEntity())
        assertFalse(profile.thermalDownshiftEnabled)
        assertNull(profile.thermalLimitDeciCelsius)
        assertNull(profile.thermalStatusFloor)
        assertNull(profile.thermalFloorRateHz)
        assertFalse(profile.networkCheckEnabled)
        assertTrue(profile.networkPreLaunchWarn) // the one 3.5 column that defaults on
        assertFalse(profile.networkAlertsEnabled)
        assertFalse(profile.fullPerformanceEnabled)
    }

    @Test
    fun `smart-feature settings round-trip through the entity`() {
        val original = GameProfile.forGame("com.example.game", "Example").copy(
            thermalDownshiftEnabled = true,
            thermalLimitDeciCelsius = 620,
            thermalStatusFloor = ThermalClass.HOT,
            thermalFloorRateHz = 60f,
            thermalHysteresisDeciCelsius = 50,
            thermalSustainHotMillis = 30_000L,
            networkCheckEnabled = true,
            networkPreLaunchWarn = false,
            networkAlertsEnabled = true,
            fullPerformanceEnabled = true,
        )
        val back = Mappers.toModel(Mappers.toEntity(original, nowMillis = 1L))
        assertTrue(back.thermalDownshiftEnabled)
        assertEquals(620, back.thermalLimitDeciCelsius)
        assertEquals(ThermalClass.HOT, back.thermalStatusFloor)
        assertEquals(60f, back.thermalFloorRateHz)
        assertEquals(50, back.thermalHysteresisDeciCelsius)
        assertEquals(30_000L, back.thermalSustainHotMillis)
        assertTrue(back.networkCheckEnabled)
        assertFalse(back.networkPreLaunchWarn)
        assertTrue(back.networkAlertsEnabled)
        assertTrue(back.fullPerformanceEnabled)
    }

    @Test
    fun `an out-of-range or unknown value from a hand-edited row is clamped or dropped, never trusted`() {
        val junk = legacyEntity().copy(
            thermalLimitDeciCelsius = 99_999,   // absurd temperature
            thermalStatusFloor = "NOT_A_CLASS", // an enum name this build does not know
            thermalFloorRateHz = -5f,           // impossible rate
            thermalMinIntervalMillis = -1L,     // negative interval
        )
        val profile = Mappers.toModel(junk)
        assertEquals(1_500, profile.thermalLimitDeciCelsius) // clamped to the ceiling
        assertNull(profile.thermalStatusFloor)               // unknown enum → dropped
        assertNull(profile.thermalFloorRateHz)               // outside 1..480 → dropped
        assertEquals(0L, profile.thermalMinIntervalMillis)   // clamped to zero
    }

    @Test
    fun `smart features do not count toward changesNothing`() {
        // A profile whose only setting is auto-cooling writes nothing when applied — it only acts if the
        // game gets hot — so it must still read as "changes nothing", the honest thing for the editor to say.
        val onlyThermal = GameProfile.forGame("com.example.game", "Example").copy(thermalDownshiftEnabled = true)
        assertTrue(onlyThermal.changesNothing)
        val onlyNetwork = GameProfile.forGame("com.example.game", "Example").copy(networkCheckEnabled = true)
        assertTrue(onlyNetwork.changesNothing)
    }

    @Test
    fun `session summary fields round-trip and a defaulted row reads null`() {
        val defaulted = Mappers.toModel(baseSessionEntity())
        assertNull(defaulted.downshiftCount)
        assertNull(defaulted.lowestRateHz)
        assertNull(defaulted.transport)
        assertNull(defaulted.fullPerformanceOverridden)

        val withData = Mappers.toModel(
            baseSessionEntity().copy(
                downshiftCount = 3,
                lowestRateHz = 60f,
                transport = "WIFI",
                fullPerformanceOverridden = true,
                systemReenabledSaver = false,
            ),
        )
        assertEquals(3, withData.downshiftCount)
        assertEquals(60f, withData.lowestRateHz)
        assertEquals(NetworkTransport.WIFI, withData.transport)
        assertEquals(true, withData.fullPerformanceOverridden)
        assertEquals(false, withData.systemReenabledSaver)
    }

    @Test
    fun `an unknown transport name in a session row is dropped rather than crashing`() {
        val session = Mappers.toModel(baseSessionEntity().copy(transport = "TELEPATHY"))
        assertNull(session.transport)
    }

    private fun baseSessionEntity() = SessionEntity(
        id = 1L,
        packageName = "com.example.game",
        gameLabel = "Example",
        startedAtMillis = 0L,
        endedAtMillis = 1_000L,
        batteryStartPercent = 80,
        batteryEndPercent = 78,
        wasCharging = false,
        averageCpuPercent = null,
        peakCpuPercent = null,
        averageMemoryPercent = null,
        peakMemoryPercent = null,
        averageTemperatureDeciCelsius = null,
        peakTemperatureDeciCelsius = null,
        averageRefreshRate = null,
        averageFrameRate = null,
        averageLatencyMillis = null,
        profileApplied = true,
        sampleCount = 0,
    )
}
