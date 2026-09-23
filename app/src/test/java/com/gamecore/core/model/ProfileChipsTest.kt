package com.gamecore.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [profileChips] is a claim about what a Games card will show, so it is proved here rather than on a
 * device. The invariants that matter: a profile that does nothing at all produces no chips (never a
 * "default" chip), only set fields appear, the order is stable, and the three process-level effects are
 * tagged [ProfileChipKind.PROCESS] so the card can tone them apart from plain device settings.
 *
 * Every test builds from [bare] rather than from [GameProfile.Companion.forGame], because `forGame`
 * deliberately starts a new profile with the floating button **on**. Testing a single field against a
 * base that already raises an overlay would measure two things at once, and the first version of this
 * file did exactly that — eleven tests failed because each was silently inheriting a "Floating button"
 * chip. [bare] is the genuine zero: writes nothing, raises nothing, records nothing.
 *
 * The `forGame` default is not swept under the rug; it is pinned by its own test below, together with
 * the distinction between these chips and [GameProfile.changesNothing].
 */
class ProfileChipsTest {

    /**
     * A profile that does nothing whatsoever — the true zero for a single-field test.
     *
     * `forGame` turns the floating button on, and `trackSession` defaults on too, so both are cleared
     * here explicitly. Written out field by field rather than inferred, so that a future change to a
     * default makes this helper's intent visibly wrong rather than quietly shifting every expectation.
     */
    private fun bare(): GameProfile = GameProfile.forGame("com.example.game", "Example").copy(
        showFloatingButton = false,
        showPerformancePill = false,
        showCrosshair = false,
        trackSession = false,
    )

    private fun profile(block: GameProfile.() -> GameProfile): GameProfile = bare().block()

    @Test
    fun `a profile that does nothing at all produces no chips`() {
        assertTrue("a profile with nothing set must yield no chips", profileChips(bare()).isEmpty())
    }

    /**
     * The `forGame` default, pinned: a brand-new profile raises the floating button, so its card shows
     * exactly that one chip. This is the behaviour the eleven original failures were really reporting.
     */
    @Test
    fun `a fresh forGame profile raises the floating button and gets that one overlay chip`() {
        val chips = profileChips(
            GameProfile.forGame("com.example.game", "Example").copy(trackSession = false),
        )
        assertEquals(listOf(ProfileChipKind.OVERLAY), chips.map { it.kind })
        assertEquals("Floating button", chips.single().text)
    }

    /**
     * Chips and [GameProfile.changesNothing] answer different questions and are allowed to disagree.
     *
     * `changesNothing` asks whether applying the profile would *write* anything; raising an overlay
     * writes nothing and leaves nothing to restore, so it does not count there. A chip asks what the
     * user will see happen, and an overlay appearing plainly counts. A fresh profile is both "writes
     * nothing" and "shows a floating button", and this test exists so that nobody later "fixes" one of
     * the two to match the other.
     */
    @Test
    fun `changesNothing is about device writes, so an overlay-only profile still has a chip`() {
        val overlayOnly = bare().copy(showFloatingButton = true)
        assertTrue("raising an overlay is not a device write", overlayOnly.changesNothing)
        assertEquals(listOf(ProfileChipKind.OVERLAY), profileChips(overlayOnly).map { it.kind })
    }

    @Test
    fun `only set fields become chips — a null field is absent, not a default`() {
        val chips = profileChips(profile { copy(targetRefreshRate = 120f, brightnessPercent = null) })
        assertEquals(1, chips.size)
        assertEquals(ProfileChipKind.DEVICE, chips.single().kind)
        assertTrue(chips.single().text.contains("120"))
    }

    @Test
    fun `refresh rate is a device chip`() {
        val chips = profileChips(profile { copy(targetRefreshRate = 90f) })
        assertEquals(listOf(ProfileChipKind.DEVICE), chips.map { it.kind })
    }

    @Test
    fun `do not disturb produces a device chip`() {
        val chips = profileChips(profile { copy(enableDoNotDisturb = true) })
        assertEquals("Do not disturb", chips.single().text)
        assertEquals(ProfileChipKind.DEVICE, chips.single().kind)
    }

    @Test
    fun `orientation CURRENT is leave-it-alone and produces no chip`() {
        val chips = profileChips(profile { copy(rotationLock = ScreenOrientationLock.CURRENT) })
        assertTrue(chips.isEmpty())
    }

    @Test
    fun `orientation LANDSCAPE produces a device chip`() {
        val chips = profileChips(profile { copy(rotationLock = ScreenOrientationLock.LANDSCAPE) })
        assertEquals(ScreenOrientationLock.LANDSCAPE.label, chips.single().text)
        assertEquals(ProfileChipKind.DEVICE, chips.single().kind)
    }

    @Test
    fun `overlay effects are tagged OVERLAY`() {
        val chips = profileChips(
            profile {
                copy(showPerformancePill = true, showFloatingButton = true, showCrosshair = true)
            },
        )
        assertEquals(3, chips.size)
        assertTrue(chips.all { it.kind == ProfileChipKind.OVERLAY })
    }

    @Test
    fun `freeRamOnLaunch and cpuAffinity and shizuku are process chips`() {
        val chips = profileChips(
            profile {
                copy(
                    freeRamOnLaunch = true,
                    cpuAffinity = CpuAffinityPreset.PERFORMANCE_ONLY,
                    useShizukuOptimizations = true,
                )
            },
        )
        assertEquals(3, chips.size)
        assertTrue(chips.all { it.kind == ProfileChipKind.PROCESS })
        assertTrue(chips.any { it.text == CpuAffinityPreset.PERFORMANCE_ONLY.label })
    }

    @Test
    fun `trackSession produces a tracking chip`() {
        val chips = profileChips(profile { copy(trackSession = true) })
        assertEquals("Records a session", chips.single().text)
        assertEquals(ProfileChipKind.TRACKING, chips.single().kind)
    }

    @Test
    fun `chip order is device then overlay then process then tracking`() {
        val chips = profileChips(
            profile {
                copy(
                    targetRefreshRate = 120f,
                    showPerformancePill = true,
                    freeRamOnLaunch = true,
                    trackSession = true,
                )
            },
        )
        assertEquals(
            listOf(
                ProfileChipKind.DEVICE,
                ProfileChipKind.OVERLAY,
                ProfileChipKind.PROCESS,
                ProfileChipKind.TRACKING,
            ),
            chips.map { it.kind },
        )
    }

    @Test
    fun `balanced performance mode is the leave-it-alone default and produces no chip`() {
        val chips = profileChips(profile { copy(performanceMode = PerformanceMode.BALANCED) })
        assertTrue(chips.isEmpty())
    }

    @Test
    fun `a non-balanced performance mode produces a device chip`() {
        val nonBalanced = PerformanceMode.entries.first { it != PerformanceMode.BALANCED }
        val chips = profileChips(profile { copy(performanceMode = nonBalanced) })
        assertEquals(nonBalanced.label, chips.single().text)
        assertEquals(ProfileChipKind.DEVICE, chips.single().kind)
    }
}
