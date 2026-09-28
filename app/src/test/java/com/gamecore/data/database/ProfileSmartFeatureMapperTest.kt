package com.gamecore.data.database

import com.gamecore.core.model.GameProfile
import com.gamecore.core.model.NetworkTransport
import com.gamecore.core.model.ResolutionScale
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
        // Instant Replay is session-time behaviour with nothing to write on apply and nothing to restore
        // on exit, so a profile whose only setting is the rolling buffer still "changes nothing".
        val onlyReplay = GameProfile.forGame("com.example.game", "Example").copy(instantReplayEnabled = true)
        assertTrue(onlyReplay.changesNothing)
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

    @Test
    fun `a resolution override round-trips and a defaulted pre-v12 profile reads none`() {
        // A pre-v12 row leaves resolution_override at its NULL default — the ALTER TABLE adds none — which
        // the mapper reads as "leave the resolution alone", never a spurious FULL that would reset the panel.
        assertNull(Mappers.toModel(legacyEntity()).resolutionOverride)

        val override = GameProfile.forGame("com.example.game", "Example")
            .copy(resolutionOverride = ResolutionScale.MEDIUM)
        val back = Mappers.toModel(Mappers.toEntity(override, nowMillis = 1L))
        assertEquals(ResolutionScale.MEDIUM, back.resolutionOverride)

        // Stored by name and matched against `entries`, so a name this build has dropped reads as null
        // and the profile stops overriding the resolution rather than failing to load.
        assertNull(Mappers.toModel(legacyEntity().copy(resolutionOverride = "ULTRA_LOW")).resolutionOverride)
    }

    @Test
    fun `an applied resolution round-trips on a session and a defaulted row reads null`() {
        assertNull(Mappers.toModel(baseSessionEntity()).resolutionApplied)

        val recorded = Mappers.toModel(baseSessionEntity()).copy(resolutionApplied = ResolutionScale.HIGH)
        assertEquals(ResolutionScale.HIGH, Mappers.toModel(Mappers.toEntity(recorded)).resolutionApplied)

        // An unknown name is dropped to null — "not touched" — and stays distinct from FULL's reset-to-native.
        assertNull(Mappers.toModel(baseSessionEntity().copy(resolutionApplied = "NONSENSE")).resolutionApplied)
    }

    @Test
    fun `an instant-replay opt-in round-trips and a defaulted pre-feature profile reads off`() {
        // A pre-v14 row leaves the three instant_replay_* columns at the DEFAULTs the ALTER TABLE set —
        // feature off, 30s window, audio off — which is also a new profile's default and the honest
        // reading for a profile written before the feature existed.
        val legacy = Mappers.toModel(legacyEntity())
        assertFalse(legacy.instantReplayEnabled)
        assertEquals(30, legacy.instantReplayBufferSeconds)
        assertFalse(legacy.instantReplayIncludeAudio)

        val opted = GameProfile.forGame("com.example.game", "Example")
            .copy(instantReplayEnabled = true, instantReplayBufferSeconds = 60, instantReplayIncludeAudio = true)
        val back = Mappers.toModel(Mappers.toEntity(opted, nowMillis = 1L))
        assertTrue(back.instantReplayEnabled)
        assertEquals(60, back.instantReplayBufferSeconds)
        assertTrue(back.instantReplayIncludeAudio)
    }

    @Test
    fun `an out-of-range instant-replay window from a hand-edited row snaps back to the default`() {
        // The buffer window is only ever one of the picker's choices {15,30,60,120}; a hand-edited 999
        // is not a size the ring buffer was built for, so the mapper snaps it to the 30s default rather
        // than sizing the buffer to a value the UI could never have produced.
        assertEquals(30, Mappers.toModel(legacyEntity().copy(instantReplayBufferSeconds = 999)).instantReplayBufferSeconds)
        assertEquals(30, Mappers.toModel(legacyEntity().copy(instantReplayBufferSeconds = 0)).instantReplayBufferSeconds)
        // A legitimate choice is preserved untouched.
        assertEquals(120, Mappers.toModel(legacyEntity().copy(instantReplayBufferSeconds = 120)).instantReplayBufferSeconds)
    }

    @Test
    fun `an instant-replay session record round-trips and a defaulted row reads null`() {
        // A session that ran with the feature off, or one recorded before v14, has nothing to report and
        // reads NULL — never false/0, which would read as a real measurement that was never taken.
        val defaulted = Mappers.toModel(baseSessionEntity())
        assertNull(defaulted.instantReplayUsed)
        assertNull(defaulted.clipsSaved)

        val recorded = Mappers.toModel(baseSessionEntity().copy(instantReplayUsed = true, clipsSaved = 2))
        assertEquals(true, recorded.instantReplayUsed)
        assertEquals(2, recorded.clipsSaved)
        // Round-trips back through the entity unchanged.
        assertEquals(2, Mappers.toModel(Mappers.toEntity(recorded)).clipsSaved)

        // A hand-edited negative clip count is clamped non-negative, never read back as a real total.
        assertEquals(0, Mappers.toModel(baseSessionEntity().copy(clipsSaved = -5)).clipsSaved)
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
