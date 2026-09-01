package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §31's profile storage, tested as the invariant restore-on-exit depends on.
 *
 * A profile's nullable fields are the app's record of what it is allowed to touch. If a null ever
 * behaved like a default, GameCore would write a setting the user never chose and then "restore" it
 * to a value the device never had — so [GameProfile.changesNothing] is checked field by field
 * rather than once.
 */
class GameProfileTest {

    private fun blank() = GameProfile.forGame("com.example.game", "Example Game")

    @Test
    fun `a new profile writes nothing and shows the floating button`() {
        val profile = blank()
        assertTrue(profile.changesNothing)
        assertTrue(profile.isEnabled)
        assertTrue(profile.showFloatingButton)
        assertFalse(profile.showPerformancePill)
        assertFalse(profile.showCrosshair)
        assertTrue(profile.trackSession)
        assertEquals(PerformanceMode.BALANCED, profile.performanceMode)
        assertFalse(profile.useShizukuOptimizations)
        assertFalse(profile.enableDoNotDisturb)
    }

    @Test
    fun `every adjustable field of a new profile is null, not a default value`() {
        val profile = blank()
        assertNull(profile.targetRefreshRate)
        assertNull(profile.brightnessPercent)
        assertNull(profile.rotationLock)
        assertNull(profile.screenTimeoutMillis)
        assertNull(profile.mediaVolumePercent)
        assertNull(profile.hudLayoutId)
        assertNull(profile.crosshairPresetId)
    }

    @Test
    fun `each single write on its own is enough to stop being a no-op`() {
        val base = blank()
        assertFalse(base.copy(targetRefreshRate = 120f).changesNothing)
        assertFalse(base.copy(brightnessPercent = 80).changesNothing)
        assertFalse(base.copy(rotationLock = ScreenOrientationLock.LANDSCAPE).changesNothing)
        assertFalse(base.copy(screenTimeoutMillis = 1_800_000L).changesNothing)
        assertFalse(base.copy(mediaVolumePercent = 60).changesNothing)
        assertFalse(base.copy(enableDoNotDisturb = true).changesNothing)
        assertFalse(base.copy(performanceMode = PerformanceMode.PERFORMANCE).changesNothing)
        assertFalse(base.copy(performanceMode = PerformanceMode.BATTERY_SAVER).changesNothing)
        assertFalse(base.copy(performanceMode = PerformanceMode.CUSTOM).changesNothing)
        assertFalse(base.copy(useShizukuOptimizations = true).changesNothing)
    }

    @Test
    fun `an overlay-only profile still changes nothing about the device`() {
        // Raising a HUD writes no setting, so there is nothing to unwind when the game exits and
        // the Games screen is right to say this profile leaves the device alone.
        val overlays = blank().copy(
            showPerformancePill = true,
            showCrosshair = true,
            showFloatingButton = false,
            hudLayoutId = 4L,
            crosshairPresetId = 7L,
            trackSession = false,
            isEnabled = false,
        )
        assertTrue(overlays.changesNothing)
    }

    @Test
    fun `a brightness or volume of zero is a value the profile writes, not an absence`() {
        val dark = blank().copy(brightnessPercent = 0)
        assertFalse(dark.changesNothing)
        assertEquals(0, dark.brightnessPercent)

        val silent = blank().copy(mediaVolumePercent = 0)
        assertFalse(silent.changesNothing)
        assertEquals(0, silent.mediaVolumePercent)

        // Zero is also a legitimate refresh-rate request: it releases the pin rather than
        // meaning "unset". Only null means the profile does not touch the rate.
        assertFalse(blank().copy(targetRefreshRate = 0f).changesNothing)
    }

    @Test
    fun `a profile keeps its configuration when its game is uninstalled`() {
        // The package name is the identity and nothing here resolves it, so a profile whose game
        // is gone is still a well-formed profile the Games screen can show and the user can keep.
        val orphan = GameProfile.forGame("com.uninstalled.game", "Uninstalled Game")
            .copy(targetRefreshRate = 144f, hudLayoutId = 2L)
        assertEquals("com.uninstalled.game", orphan.packageName)
        assertEquals(144f, orphan.targetRefreshRate)
        assertEquals(2L, orphan.hudLayoutId)
    }

    @Test
    fun `two profiles differ if any single stored field differs`() {
        val base = blank()
        assertEquals(base, blank())
        assertEquals(base, base.copy())
        listOf(
            base.copy(label = "Renamed"),
            base.copy(isEnabled = false),
            base.copy(targetRefreshRate = 60f),
            base.copy(brightnessPercent = 50),
            base.copy(hudLayoutId = 1L),
            base.copy(trackSession = false),
        ).forEach { assertFalse(it.toString(), it == base) }
    }

    @Test
    fun `the modes that usually need the elevated shell are the two that pin the panel`() {
        assertFalse(PerformanceMode.BALANCED.needsElevatedShellUsually)
        assertTrue(PerformanceMode.PERFORMANCE.needsElevatedShellUsually)
        assertTrue(PerformanceMode.BATTERY_SAVER.needsElevatedShellUsually)
        assertFalse(PerformanceMode.CUSTOM.needsElevatedShellUsually)
    }

    @Test
    fun `every mode explains what it writes and none of them promises a frame rate`() {
        // §14 and §24: the explanation shown beside each mode is a list of settings, never a
        // claim about performance an app cannot deliver.
        val forbidden = listOf("boost", "free up", "clean", "faster fps", "unlock", "more fps")
        PerformanceMode.entries.forEach { mode ->
            assertTrue(mode.name, mode.label.isNotBlank())
            assertTrue(mode.name, mode.explanation.length > 40)
            forbidden.forEach { claim ->
                assertFalse("$mode: $claim", mode.explanation.lowercase().contains(claim))
            }
        }
    }

    @Test
    fun `each orientation lock names itself for the profile editor`() {
        assertEquals(3, ScreenOrientationLock.entries.size)
        assertEquals("Portrait", ScreenOrientationLock.PORTRAIT.label)
        assertEquals("Landscape", ScreenOrientationLock.LANDSCAPE.label)
        assertTrue(ScreenOrientationLock.CURRENT.label.isNotBlank())
    }
}
