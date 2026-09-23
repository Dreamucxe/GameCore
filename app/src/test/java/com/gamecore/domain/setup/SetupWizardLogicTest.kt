package com.gamecore.domain.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The setup wizard's decisions (spec §A1/§A2) as pure logic: the entry branch for every stored state, the
 * feature→permission mapping, which steps a set of choices makes visible, navigation and resume over that
 * visible list, and the preset summary's will-set / not-available split. No Activity, no navigation
 * framework — every case is an assertion on plain values.
 */
class SetupWizardLogicTest {

    // ---------------------------------------------------------------- entry decision

    @Test
    fun `a fresh install runs the full wizard`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = null, currentVersion = 5, dismissed = false, hasProfiles = false, hasSessions = false))
        assertEquals(WizardEntry.FullWizard, e)
    }

    @Test
    fun `existing profiles with no completed flag is an upgrade home card`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = null, currentVersion = 5, dismissed = false, hasProfiles = true, hasSessions = false))
        assertEquals(WizardEntry.HomeCard, e)
    }

    @Test
    fun `existing sessions with no completed flag is an upgrade home card`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = null, currentVersion = 5, dismissed = false, hasProfiles = false, hasSessions = true))
        assertEquals(WizardEntry.HomeCard, e)
    }

    @Test
    fun `completed at an older version offers a home card`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = 4, currentVersion = 5, dismissed = false, hasProfiles = true, hasSessions = true))
        assertEquals(WizardEntry.HomeCard, e)
    }

    @Test
    fun `completed at the current version shows nothing`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = 5, currentVersion = 5, dismissed = false, hasProfiles = true, hasSessions = true))
        assertEquals(WizardEntry.Nothing, e)
    }

    @Test
    fun `completed at a newer version shows nothing`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = 6, currentVersion = 5, dismissed = false, hasProfiles = false, hasSessions = false))
        assertEquals(WizardEntry.Nothing, e)
    }

    @Test
    fun `dismissed wins over everything else`() {
        val e = decideEntry(SetupSignals(hasCompletedVersion = null, currentVersion = 5, dismissed = true, hasProfiles = false, hasSessions = false))
        assertEquals(WizardEntry.Nothing, e)
    }

    // ---------------------------------------------------------------- required permissions

    @Test
    fun `no choices need no permissions`() {
        assertTrue(requiredPermissionSteps(WizardFeatureChoices()).isEmpty())
    }

    @Test
    fun `overlay needs the alert window permission`() {
        assertEquals(listOf(PermissionNeed.SYSTEM_ALERT_WINDOW), requiredPermissionSteps(WizardFeatureChoices(overlay = true)))
    }

    @Test
    fun `auto profiles need usage stats`() {
        assertEquals(listOf(PermissionNeed.PACKAGE_USAGE_STATS), requiredPermissionSteps(WizardFeatureChoices(autoProfiles = true)))
    }

    @Test
    fun `dnd needs notification policy access`() {
        assertEquals(listOf(PermissionNeed.ACCESS_NOTIFICATION_POLICY), requiredPermissionSteps(WizardFeatureChoices(dnd = true)))
    }

    @Test
    fun `thermal and full performance are flagged as needing shizuku`() {
        assertEquals(listOf(PermissionNeed.SHIZUKU), requiredPermissionSteps(WizardFeatureChoices(thermalDownshift = true)))
        assertEquals(listOf(PermissionNeed.SHIZUKU), requiredPermissionSteps(WizardFeatureChoices(fullPerformance = true)))
    }

    @Test
    fun `shizuku is added once when several shizuku features are picked`() {
        val needs = requiredPermissionSteps(WizardFeatureChoices(refreshRateControl = true, thermalDownshift = true, fullPerformance = true))
        assertEquals(listOf(PermissionNeed.SHIZUKU), needs)
    }

    @Test
    fun `post notifications is added only when wanted and on api 33 or higher`() {
        assertFalse(requiredPermissionSteps(WizardFeatureChoices(), wantsNotifications = true, isApi33OrHigher = false).contains(PermissionNeed.POST_NOTIFICATIONS))
        assertTrue(requiredPermissionSteps(WizardFeatureChoices(), wantsNotifications = true, isApi33OrHigher = true).contains(PermissionNeed.POST_NOTIFICATIONS))
    }

    // ---------------------------------------------------------------- visible steps

    @Test
    fun `minimal choices show the always-on steps only`() {
        assertEquals(
            listOf(WizardStep.WELCOME, WizardStep.FEATURES, WizardStep.PERMISSIONS, WizardStep.FIRST_GAME, WizardStep.DONE),
            visibleSteps(WizardFeatureChoices()),
        )
    }

    @Test
    fun `a shizuku feature reveals the shizuku step`() {
        val steps = visibleSteps(WizardFeatureChoices(refreshRateControl = true))
        assertTrue(steps.contains(WizardStep.SHIZUKU))
    }

    @Test
    fun `a network check reveals smart features but not shizuku`() {
        val steps = visibleSteps(WizardFeatureChoices(networkCheck = true))
        assertTrue(steps.contains(WizardStep.SMART_FEATURES))
        assertFalse(steps.contains(WizardStep.SHIZUKU))
    }

    @Test
    fun `full performance reveals both shizuku and smart features`() {
        val steps = visibleSteps(WizardFeatureChoices(fullPerformance = true))
        assertEquals(
            listOf(
                WizardStep.WELCOME, WizardStep.FEATURES, WizardStep.PERMISSIONS,
                WizardStep.SHIZUKU, WizardStep.FIRST_GAME, WizardStep.SMART_FEATURES, WizardStep.DONE,
            ),
            steps,
        )
    }

    // ---------------------------------------------------------------- navigation and resume

    @Test
    fun `next and previous walk the visible list and clamp at the ends`() {
        val steps = visibleSteps(WizardFeatureChoices())
        assertEquals(WizardStep.FEATURES, nextStep(WizardStep.WELCOME, steps))
        assertEquals(WizardStep.WELCOME, previousStep(WizardStep.FEATURES, steps))
        // Clamp: no step before the first or after the last.
        assertEquals(WizardStep.WELCOME, previousStep(WizardStep.WELCOME, steps))
        assertEquals(WizardStep.DONE, nextStep(WizardStep.DONE, steps))
    }

    @Test
    fun `navigation skips hidden steps`() {
        // With no shizuku feature, PERMISSIONS is followed by FIRST_GAME, not the hidden SHIZUKU.
        val steps = visibleSteps(WizardFeatureChoices())
        assertEquals(WizardStep.FIRST_GAME, nextStep(WizardStep.PERMISSIONS, steps))
    }

    @Test
    fun `resume returns a saved step that is still visible`() {
        val steps = visibleSteps(WizardFeatureChoices(fullPerformance = true))
        assertEquals(WizardStep.SHIZUKU, resumeAt(WizardStep.SHIZUKU, steps))
    }

    @Test
    fun `resume falls back to the first step for a null or vanished step`() {
        val steps = visibleSteps(WizardFeatureChoices()) // no SHIZUKU here
        assertEquals(WizardStep.WELCOME, resumeAt(null, steps))
        assertEquals(WizardStep.WELCOME, resumeAt(WizardStep.SHIZUKU, steps))
    }

    @Test
    fun `resume after rotation returns the same place`() {
        // Rotation is just re-calling with the same choices and saved step.
        val steps = visibleSteps(WizardFeatureChoices(networkCheck = true))
        assertEquals(WizardStep.SMART_FEATURES, resumeAt(WizardStep.SMART_FEATURES, steps))
    }

    // ---------------------------------------------------------------- preset summary

    @Test
    fun `a fully supported preset is all will-set lines`() {
        val lines = presetSummary(SetupPreset.MAX_PERFORMANCE, PresetSupport())
        assertTrue(lines.all { it is PresetLine.WillSet })
        assertEquals(3, lines.size)
    }

    @Test
    fun `an unsupported field becomes a not-available line and never a will-set`() {
        val lines = presetSummary(SetupPreset.MAX_PERFORMANCE, PresetSupport(refreshRate = false))
        val refreshLine = lines.first { it.settingName() == "Refresh rate" }
        assertTrue(refreshLine is PresetLine.NotAvailable)
        // The rest are still will-set.
        assertTrue(lines.any { it is PresetLine.WillSet && it.setting == "Thermal downshift" })
    }

    @Test
    fun `balanced preset splits supported and unsupported fields`() {
        val lines = presetSummary(SetupPreset.BALANCED, PresetSupport(dnd = false))
        assertTrue(lines.any { it is PresetLine.WillSet && it.setting == "Refresh rate" })
        assertTrue(lines.any { it is PresetLine.NotAvailable && it.setting == "Do Not Disturb" })
    }

    private fun PresetLine.settingName(): String = when (this) {
        is PresetLine.WillSet -> setting
        is PresetLine.NotAvailable -> setting
    }
}
