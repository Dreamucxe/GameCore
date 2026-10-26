package com.gamecore.core.backup

import com.gamecore.core.model.DisplaySize
import com.gamecore.core.model.ResolutionScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-migration rules, as inputs -> checks.
 *
 * Pure, so the comparisons run without a device or a display. The invariants that matter: two
 * identical devices produce nothing; a *missing* snapshot produces nothing rather than a guess; and
 * every warning names the field and says "may not be available here" instead of promising a result.
 */
class DeviceMigrationTest {

    private val pixel7 = DeviceSnapshot(
        manufacturer = "Google",
        model = "Pixel 7",
        sdkInt = 34,
        panelWidthPixels = 1080,
        panelHeightPixels = 2400,
        peakRefreshRateHz = 120f,
    )

    private val pixel6 = DeviceSnapshot(
        manufacturer = "Google",
        model = "Pixel 6",
        sdkInt = 34,
        panelWidthPixels = 1080,
        panelHeightPixels = 2400,
        peakRefreshRateHz = 90f,
    )

    private val olderSdk = DeviceSnapshot(
        manufacturer = "Google",
        model = "Pixel 7",
        sdkInt = 30,
        panelWidthPixels = 1080,
        panelHeightPixels = 2400,
        peakRefreshRateHz = 120f,
    )

    @Test
    fun `the same device produces no checks`() {
        val checks = DeviceMigration.report(backup = pixel7, current = pixel7)

        assertTrue(checks.isEmpty())
    }

    @Test
    fun `a missing backup snapshot produces no checks`() {
        val checks = DeviceMigration.report(backup = null, current = pixel7)

        assertTrue(checks.isEmpty())
    }

    @Test
    fun `a missing current snapshot produces no checks`() {
        val checks = DeviceMigration.report(backup = pixel7, current = null)

        assertTrue(checks.isEmpty())
    }

    @Test
    fun `another model is reported as information`() {
        val checks = DeviceMigration.report(backup = pixel7, current = pixel6)

        val device = checks.single { it.title == "Coming from another device" }
        assertEquals(MigrationSeverity.INFO, device.severity)
        assertTrue(device.detail.contains("Pixel 7"))
    }

    @Test
    fun `a backup from a newer Android version is a warning naming both`() {
        val checks = DeviceMigration.report(backup = pixel7, current = olderSdk)

        val sdk = checks.single { it.title == "Newer Android version" }
        assertEquals(MigrationSeverity.WARNING, sdk.severity)
        assertTrue(sdk.detail.contains("34"))
        assertTrue(sdk.detail.contains("30"))
    }

    @Test
    fun `a backup from an older Android version is not a warning`() {
        val checks = DeviceMigration.report(backup = olderSdk, current = pixel7)

        assertTrue(checks.none { it.title == "Newer Android version" })
    }

    @Test
    fun `a backup whose panel was faster warns and names both rates`() {
        val checks = DeviceMigration.report(backup = pixel7, current = pixel6)

        val refresh = checks.single { it.title == "Refresh rate" }
        assertEquals(MigrationSeverity.WARNING, refresh.severity)
        assertTrue(refresh.detail.contains("90 Hz"))
        assertTrue(refresh.detail.contains("120 Hz"))
    }

    @Test
    fun `a backup whose panel was slower produces no refresh warning`() {
        val checks = DeviceMigration.report(backup = pixel6, current = pixel7)

        assertTrue(checks.none { it.title == "Refresh rate" })
    }

    @Test
    fun `a profile pinning above this device's own rate is flagged`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel6,
            profileRefreshHz = listOf(120f),
        )

        val pinned = checks.single { it.title == "Refresh rate pinned" }
        assertTrue(pinned.detail.contains("120 Hz"))
        assertTrue(pinned.detail.contains("90 Hz"))
    }

    @Test
    fun `a profile pinning at or below this device's rate is not flagged`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel7,
            profileRefreshHz = listOf(60f),
        )

        assertTrue(checks.none { it.title == "Refresh rate pinned" })
    }

    @Test
    fun `a different panel shape warns`() {
        val tablet = pixel7.copy(panelWidthPixels = 1600, panelHeightPixels = 2560)
        val checks = DeviceMigration.report(backup = tablet, current = pixel7)

        val size = checks.single { it.title == "Display size" }
        assertTrue(size.detail.contains("1600 × 2560"))
        assertTrue(size.detail.contains("1080 × 2400"))
    }

    @Test
    fun `the same shape with a rounding difference is not a different panel`() {
        val near = pixel7.copy(panelWidthPixels = 1085, panelHeightPixels = 2400)
        val checks = DeviceMigration.report(backup = near, current = pixel7)

        assertTrue(checks.none { it.title == "Display size" })
    }

    @Test
    fun `a resolution scale that would land below the usable size is flagged`() {
        // 60% of a 720 × 1280 panel is 432 × 768 — a short side below the smallest size GameCore sets.
        val small = DeviceSnapshot(
            manufacturer = "Google",
            model = "Pixel 4a",
            sdkInt = 34,
            panelWidthPixels = 720,
            panelHeightPixels = 1280,
            peakRefreshRateHz = 60f,
        )
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = small,
            profileResolution = listOf(ResolutionScale.MEDIUM),
        )

        val res = checks.single { it.title == "Resolution override" }
        assertTrue(res.detail.contains("This device's"))
    }

    @Test
    fun `a resolution scale that fits is not flagged`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel7,
            profileResolution = listOf(ResolutionScale.MEDIUM),
        )

        assertTrue(checks.none { it.title == "Resolution override" })
    }

    @Test
    fun `a custom size larger than this panel is flagged`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel6,
            profileDisplaySizes = listOf(DisplaySize(1200, 2666)),
        )

        val custom = checks.single { it.title == "Custom display size" }
        assertTrue(custom.detail.contains("1200 × 2666"))
    }

    @Test
    fun `a custom size that fits is not flagged`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel7,
            profileDisplaySizes = listOf(DisplaySize(720, 1600)),
        )

        assertTrue(checks.none { it.title == "Custom display size" })
    }

    @Test
    fun `a file with no profiles says so rather than implying compatibility`() {
        val checks = DeviceMigration.report(backup = pixel7, current = pixel6, profileCount = 0)

        val none = checks.single { it.title == "No profiles in this file" }
        assertEquals(MigrationSeverity.INFO, none.severity)
    }

    @Test
    fun `unreadable panel sizes are treated as absent`() {
        val noPanel = pixel7.copy(panelWidthPixels = 0, panelHeightPixels = 0)
        val checks = DeviceMigration.report(backup = noPanel, current = pixel6)

        assertTrue(checks.none { it.title == "Display size" })
        assertTrue(checks.none { it.title == "Resolution override" })
    }

    @Test
    fun `every check has a title and a non-empty sentence`() {
        val checks = DeviceMigration.report(
            backup = pixel7,
            current = pixel6,
            profileCount = 2,
            profileRefreshHz = listOf(120f),
            profileResolution = listOf(ResolutionScale.MEDIUM),
            profileDisplaySizes = listOf(DisplaySize(1200, 2666)),
        )

        assertTrue(checks.isNotEmpty())
        checks.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.detail.isNotBlank())
        }
    }
}