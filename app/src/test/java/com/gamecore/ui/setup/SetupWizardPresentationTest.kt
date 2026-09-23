package com.gamecore.ui.setup

import com.gamecore.core.model.ShizukuState
import com.gamecore.core.permissions.GamePermission
import com.gamecore.domain.setup.PermissionNeed
import com.gamecore.domain.setup.SetupPreset
import com.gamecore.domain.setup.WizardFeatureChoices
import com.gamecore.domain.setup.WizardStep
import com.gamecore.domain.setup.visibleSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's *presentation* decisions, which are the half of the flow that
 * [com.gamecore.domain.setup.SetupWizardLogic] deliberately does not own: the progress fraction and its
 * spoken label, the per-step wording, the two button labels, the feature-tick mapping, the bridge from the
 * engine's permission enum to the app's permission catalogue, and the sentences the Done step reports.
 *
 * The engine itself is not re-tested here — it has its own suite, and duplicating it would produce two
 * places to update when a step is added. What this file guards is everything that would otherwise only be
 * checkable by launching an Activity and reading the screen, which is why `SetupWizardState.kt` has no
 * `android.*` import: every assertion below is a plain value comparison.
 *
 * Three of these tests exist because of a specific way this screen could go wrong rather than for coverage:
 *  - the progress fraction, because an off-by-one there produces a bar that is never full on the last step
 *    or is already full on the first, and neither is visible in code review.
 *  - [WizardFeature.applyTo] round-tripping, because the enum carries both halves of a mapping to eight
 *    separate booleans and a copy-paste slip would silently tick the wrong feature.
 *  - the privacy note, because ads were removed in 3.4 and the one thing that still connects out is the
 *    optional latency probe. That note is a factual claim about the app, so it is asserted like one.
 */
class SetupWizardPresentationTest {

    // ------------------------------------------------------------------------ progress

    @Test
    fun `the first step reports an empty bar`() {
        val steps = visibleSteps(WizardFeatureChoices())
        val progress = progressOf(WizardStep.WELCOME, steps)
        assertEquals(1, progress.index)
        assertEquals(0f, progress.fraction, 0f)
    }

    @Test
    fun `the last step reports a full bar`() {
        val steps = visibleSteps(WizardFeatureChoices())
        val progress = progressOf(steps.last(), steps)
        assertEquals(steps.size, progress.index)
        assertEquals(1f, progress.fraction, 0f)
    }

    @Test
    fun `the label reads Step N of M`() {
        val steps = visibleSteps(WizardFeatureChoices())
        assertEquals("Step 1 of ${steps.size}", progressOf(WizardStep.WELCOME, steps).label)
        assertEquals("Step ${steps.size} of ${steps.size}", progressOf(steps.last(), steps).label)
    }

    @Test
    fun `the total follows the steps this run actually shows`() {
        // Picking a Shizuku feature adds two steps, and the indicator has to count the run in front of the
        // user rather than the enum. A wizard that said "of 7" while showing 5 would be lying about how much
        // is left, which is the one thing a progress indicator is for.
        val lean = visibleSteps(WizardFeatureChoices())
        val full = visibleSteps(WizardFeatureChoices(thermalDownshift = true))
        assertTrue(full.size > lean.size)
        assertEquals(lean.size, progressOf(WizardStep.WELCOME, lean).total)
        assertEquals(full.size, progressOf(WizardStep.WELCOME, full).total)
    }

    @Test
    fun `a step that is not in this run does not produce a negative index`() {
        // SHIZUKU is absent unless a Shizuku feature was picked. It can still be the current step for one
        // frame: unticking the feature that added it re-derives the visible list before the ViewModel has
        // moved off it, and `indexOf` returns -1 there.
        val steps = visibleSteps(WizardFeatureChoices())
        assertFalse(steps.contains(WizardStep.SHIZUKU))
        val progress = progressOf(WizardStep.SHIZUKU, steps)
        assertEquals(1, progress.index)
        assertEquals(0f, progress.fraction, 0f)
    }

    @Test
    fun `a single step run does not divide by zero`() {
        val progress = progressOf(WizardStep.DONE, listOf(WizardStep.DONE))
        assertEquals(1, progress.index)
        assertEquals(1, progress.total)
        assertEquals(0f, progress.fraction, 0f)
    }

    @Test
    fun `an empty step list still describes itself`() {
        val progress = progressOf(WizardStep.WELCOME, emptyList())
        assertEquals("Step 1 of 1", progress.label)
        assertTrue(progress.fraction in 0f..1f)
    }

    @Test
    fun `the fraction never leaves its range and never goes backwards`() {
        val steps = visibleSteps(
            WizardFeatureChoices(overlay = true, thermalDownshift = true, networkCheck = true),
        )
        val fractions = steps.map { progressOf(it, steps).fraction }
        fractions.forEach { assertTrue("$it was out of range", it in 0f..1f) }
        assertEquals(fractions.sorted(), fractions)
    }

    // ---------------------------------------------------------------------- the wording

    @Test
    fun `every step has a title and a lead`() {
        // The `when` in each of these is exhaustive over the enum, so this cannot fail by omission — it can
        // fail by someone adding a step with an empty placeholder string, which is the case worth catching.
        WizardStep.entries.forEach { step ->
            assertTrue("$step had no title", stepTitle(step).isNotBlank())
            assertTrue("$step had no lead", stepLead(step).isNotBlank())
        }
    }

    @Test
    fun `no two steps share a title`() {
        val titles = WizardStep.entries.map { stepTitle(it) }
        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `every feature has a title and an explanation`() {
        WizardFeature.entries.forEach { feature ->
            assertTrue("${feature.name} had no title", feature.title.isNotBlank())
            assertTrue("${feature.name} had no description", feature.description.isNotBlank())
        }
    }

    @Test
    fun `the feature picker offers the eight items the spec names`() {
        assertEquals(8, WizardFeature.entries.size)
    }

    // --------------------------------------------------------------------- the buttons

    @Test
    fun `the last step finishes rather than advancing`() {
        assertEquals("Finish", forwardLabel(WizardStep.DONE))
        WizardStep.entries.filter { it != WizardStep.DONE }.forEach {
            assertEquals("Next", forwardLabel(it))
        }
    }

    @Test
    fun `every step but the last can be skipped`() {
        // §A2's "each step is skippable" has one sensible exception: there is nothing to skip past on the
        // summary, since skipping it would mean the same thing as finishing.
        assertFalse(canSkip(WizardStep.DONE))
        WizardStep.entries.filter { it != WizardStep.DONE }.forEach {
            assertTrue("$it was not skippable", canSkip(it))
        }
    }

    @Test
    fun `no step blocks the forward button`() {
        val state = SetupWizardUiState()
        WizardStep.entries.forEach { assertTrue("$it blocked Next", canAdvance(it, state)) }
    }

    // --------------------------------------------------------------- the feature mapping

    @Test
    fun `a feature reads back the value it was given`() {
        WizardFeature.entries.forEach { feature ->
            val on = feature.applyTo(WizardFeatureChoices(), picked = true)
            val off = feature.applyTo(on, picked = false)
            assertTrue("${feature.name} did not read back as picked", feature.isPicked(on))
            assertFalse("${feature.name} did not read back as unpicked", feature.isPicked(off))
        }
    }

    @Test
    fun `ticking one feature leaves the other seven alone`() {
        // The guard against a copy-paste slip in the eight-branch `when`: if two entries wrote the same
        // field, one of them would move a second feature's tick here.
        WizardFeature.entries.forEach { feature ->
            val after = feature.applyTo(WizardFeatureChoices(), picked = true)
            val moved = WizardFeature.entries.filter { it.isPicked(after) }
            assertEquals("${feature.name} changed more than itself", listOf(feature), moved)
        }
    }

    @Test
    fun `unticking a feature leaves the rest of the picks in place`() {
        val all = WizardFeature.entries.fold(WizardFeatureChoices()) { acc, f -> f.applyTo(acc, true) }
        val without = WizardFeature.OVERLAY.applyTo(all, picked = false)
        assertEquals(
            WizardFeature.entries.filter { it != WizardFeature.OVERLAY },
            WizardFeature.entries.filter { it.isPicked(without) },
        )
    }

    @Test
    fun `the feature ticks drive the steps the engine shows`() {
        // The one place the two halves meet: the picker writes into the engine's own type, so a tick here
        // has to be the same fact the engine reads. Asserted through `visibleSteps` rather than by
        // inspecting the fields, because that is the consequence the user sees.
        val choices = WizardFeature.THERMAL_DOWNSHIFT.applyTo(WizardFeatureChoices(), picked = true)
        assertTrue(visibleSteps(choices).contains(WizardStep.SHIZUKU))
        assertTrue(visibleSteps(choices).contains(WizardStep.SMART_FEATURES))
    }

    // ------------------------------------------------------------ the permission bridge

    @Test
    fun `every permission need maps to the catalogue entry that explains it`() {
        assertEquals(GamePermission.OVERLAY, catalogueEntryFor(PermissionNeed.SYSTEM_ALERT_WINDOW))
        assertEquals(GamePermission.USAGE_ACCESS, catalogueEntryFor(PermissionNeed.PACKAGE_USAGE_STATS))
        assertEquals(
            GamePermission.NOTIFICATION_POLICY,
            catalogueEntryFor(PermissionNeed.ACCESS_NOTIFICATION_POLICY),
        )
        assertEquals(GamePermission.POST_NOTIFICATIONS, catalogueEntryFor(PermissionNeed.POST_NOTIFICATIONS))
    }

    @Test
    fun `Shizuku is the only need with no catalogue entry`() {
        assertNull(catalogueEntryFor(PermissionNeed.SHIZUKU))
        PermissionNeed.entries.filter { it != PermissionNeed.SHIZUKU }.forEach {
            assertNotNull("$it had no catalogue entry", catalogueEntryFor(it))
        }
    }

    @Test
    fun `no two needs share a catalogue entry`() {
        val mapped = PermissionNeed.entries.mapNotNull { catalogueEntryFor(it) }
        assertEquals(mapped.size, mapped.toSet().size)
    }

    @Test
    fun `a permission row states its grant in words`() {
        // §A5: state is never carried by the tint alone, so the row has to say it.
        val granted = rowFor(PermissionNeed.SYSTEM_ALERT_WINDOW, isGranted = true)
        val missing = rowFor(PermissionNeed.SYSTEM_ALERT_WINDOW, isGranted = false)
        assertEquals("Granted", granted.stateLabel)
        assertEquals("Not granted", missing.stateLabel)
        assertTrue(granted.title.isNotBlank())
    }

    @Test
    fun `the Shizuku row titles itself without a catalogue entry`() {
        val row = rowFor(PermissionNeed.SHIZUKU, isGranted = false)
        assertNull(row.catalogue)
        assertEquals("Shizuku", row.title)
        assertFalse(row.isRuntimeRequestable)
    }

    // ------------------------------------------------------------------ the Shizuku step

    @Test
    fun `the control summary is a fraction and not a yes`() {
        // §A2 step 4 asks for the real N-of-M, because "Shizuku connected" over-promises on a device where
        // six of the eight controls are unsupported whatever the shell can do.
        val summary = ShizukuSummary(
            state = ShizukuState.RUNNING_PERMISSION_GRANTED,
            isInstalled = true,
            usableControls = 3,
            totalControls = 8,
        )
        assertEquals("3 of 8 controls available on this device", summary.controlSummary)
        assertTrue(summary.isConnected)
    }

    @Test
    fun `only a granted Shizuku permission counts as connected`() {
        // Installed is not running, and running is not granted. Four of the six states are easy to mistake
        // for success while the shell is unreachable, and the Done step's claim hangs on this one property.
        assertEquals(
            listOf(ShizukuState.RUNNING_PERMISSION_GRANTED),
            ShizukuState.entries.filter { ShizukuSummary(state = it).isConnected },
        )
    }

    @Test
    fun `an installed Shizuku is not treated as a working one`() {
        val installed = ShizukuSummary(state = ShizukuState.INSTALLED_NOT_RUNNING, isInstalled = true)
        assertFalse(installed.isConnected)
        assertEquals(0, installed.usableControls)
    }

    // ---------------------------------------------------------------------- the Done step

    @Test
    fun `the summary splits granted from outstanding`() {
        val summary = doneSummary(
            choices = WizardFeatureChoices(overlay = true, autoProfiles = true),
            permissionRows = listOf(
                rowFor(PermissionNeed.SYSTEM_ALERT_WINDOW, isGranted = true),
                rowFor(PermissionNeed.PACKAGE_USAGE_STATS, isGranted = false),
            ),
            shizuku = ShizukuSummary(),
            profileName = "Genshin Impact",
        )
        assertTrue(summary.ready.any { it.contains(GamePermission.OVERLAY.title) })
        assertTrue(summary.skipped.any { it.contains(GamePermission.USAGE_ACCESS.title) })
    }

    @Test
    fun `an unpicked feature is never reported as skipped`() {
        // The rule that keeps the last screen from reading as a list of failures: a user who never asked for
        // the overlay is not told they skipped it, because they were never offered it.
        val summary = doneSummary(
            choices = WizardFeatureChoices(),
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(state = ShizukuState.NOT_INSTALLED),
            profileName = "Genshin Impact",
        )
        assertTrue(summary.skipped.isEmpty())
        assertTrue(summary.ready.none { it.contains("Shizuku") })
    }

    @Test
    fun `a picked Shizuku feature reports the connection either way`() {
        val choices = WizardFeatureChoices(refreshRateControl = true)
        val connected = doneSummary(
            choices = choices,
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(
                state = ShizukuState.RUNNING_PERMISSION_GRANTED,
                isInstalled = true,
                usableControls = 5,
                totalControls = 8,
            ),
            profileName = "Genshin Impact",
        )
        assertTrue(connected.ready.any { it.contains("Shizuku") && it.contains("5 of 8") })

        val absent = doneSummary(
            choices = choices,
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(state = ShizukuState.NOT_INSTALLED),
            profileName = "Genshin Impact",
        )
        assertTrue(absent.skipped.any { it.contains("Shizuku") })
        assertTrue(absent.ready.none { it.contains("Shizuku") })
    }

    @Test
    fun `skipping the first game is stated as a choice with a way back`() {
        val summary = doneSummary(
            choices = WizardFeatureChoices(),
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(),
            profileName = null,
        )
        val line = summary.skipped.single()
        assertTrue(line.contains("No game profile yet"))
        assertTrue("the line left the user nowhere to go", line.contains("Games tab"))
    }

    @Test
    fun `a saved profile is named`() {
        val summary = doneSummary(
            choices = WizardFeatureChoices(),
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(),
            profileName = "Genshin Impact",
        )
        assertTrue(summary.ready.any { it.contains("Genshin Impact") })
    }

    @Test
    fun `the permission-free features are reported as on`() {
        val summary = doneSummary(
            choices = WizardFeatureChoices(aimLab = true, networkCheck = true),
            permissionRows = emptyList(),
            shizuku = ShizukuSummary(),
            profileName = "Genshin Impact",
        )
        assertTrue(summary.ready.contains("Aim Lab — on"))
        assertTrue(summary.ready.contains("Network check — on"))
    }

    @Test
    fun `every summary line is a sentence and not a bare word`() {
        val summary = doneSummary(
            choices = WizardFeatureChoices(overlay = true, refreshRateControl = true, aimLab = true),
            permissionRows = listOf(
                rowFor(PermissionNeed.SYSTEM_ALERT_WINDOW, isGranted = true),
                rowFor(PermissionNeed.SHIZUKU, isGranted = false),
            ),
            shizuku = ShizukuSummary(state = ShizukuState.INSTALLED_NOT_RUNNING, isInstalled = true),
            profileName = null,
        )
        (summary.ready + summary.skipped).forEach {
            assertTrue("\"$it\" was not a sentence", it.isNotBlank() && it.length > 3)
        }
    }

    // ------------------------------------------------------------------- the screen state

    @Test
    fun `the state knows where it is in its own run`() {
        val state = SetupWizardUiState(step = WizardStep.WELCOME)
        assertFalse(state.canGoBack)
        assertFalse(state.isLastStep)
        assertEquals(visibleSteps(state.choices), state.steps)

        val last = state.copy(step = state.steps.last())
        assertTrue(last.canGoBack)
        assertTrue(last.isLastStep)
    }

    @Test
    fun `the state lists only the permissions still outstanding`() {
        val state = SetupWizardUiState(
            permissionRows = listOf(
                rowFor(PermissionNeed.SYSTEM_ALERT_WINDOW, isGranted = true),
                rowFor(PermissionNeed.PACKAGE_USAGE_STATS, isGranted = false),
            ),
        )
        assertEquals(
            listOf(PermissionNeed.PACKAGE_USAGE_STATS),
            state.outstandingPermissions.map { it.need },
        )
    }

    @Test
    fun `the selected preset resolves to the view that carries its lines`() {
        val views = SetupPreset.entries.map { PresetChoiceView(preset = it, lines = emptyList()) }
        val state = SetupWizardUiState(presetViews = views, preset = SetupPreset.BATTERY_SAVER)
        assertEquals(SetupPreset.BATTERY_SAVER, state.selectedPresetView?.preset)
        assertEquals(SetupPreset.BATTERY_SAVER.label, state.selectedPresetView?.label)
        assertNull(state.copy(preset = null).selectedPresetView)
    }

    @Test
    fun `a preset that is not offered on this device selects nothing`() {
        // Defensive rather than reachable today: if a future build hides a preset, a stale selection must
        // resolve to null rather than to the wrong card's lines.
        val state = SetupWizardUiState(
            presetViews = listOf(PresetChoiceView(SetupPreset.BALANCED, emptyList())),
            preset = SetupPreset.MAX_PERFORMANCE,
        )
        assertNull(state.selectedPresetView)
    }

    // -------------------------------------------------------------------- the privacy note

    @Test
    fun `the privacy note names the one thing that connects out`() {
        // Ads were removed in 3.4, so the latency probe is the whole of GameCore's outbound traffic. §A2
        // step 1 requires the note to be accurate, which means naming the probe, its default host and the
        // switch that turns it off — a note that just said "nothing leaves the device" would be false.
        assertTrue(PRIVACY_NOTE_CONNECTS_OUT.contains("latency"))
        assertTrue(PRIVACY_NOTE_CONNECTS_OUT.contains("1.1.1.1"))
        assertTrue(PRIVACY_NOTE_CONNECTS_OUT.contains("Settings"))
    }

    @Test
    fun `the privacy note claims no account and no analytics`() {
        val note = PRIVACY_NOTE_STAYS_HERE.lowercase()
        assertTrue(note.contains("no account"))
        assertTrue(note.contains("analytics"))
        assertTrue(note.contains("encrypted"))
    }

    @Test
    fun `the privacy note does not mention advertising`() {
        // A regression guard with a specific history. The pre-3.4 wording described an ad SDK and a consent
        // form; both are gone from the app, so any reappearance of that vocabulary here is a stale claim
        // about what GameCore does, which is the worst kind of error to have in a privacy note.
        val note = (PRIVACY_NOTE_STAYS_HERE + " " + PRIVACY_NOTE_CONNECTS_OUT).lowercase()
        listOf("advert", "ad sdk", "banner", "consent", "admob", "personalis", "personaliz").forEach {
            assertFalse("the privacy note still says \"$it\"", note.contains(it))
        }
    }

    @Test
    fun `the restricted-settings help describes a position rather than inventing a label`() {
        // §A2 step 3 forbids naming a button GameCore cannot see. The three steps named here are Android's
        // own, and the overflow menu is described by where it is.
        assertTrue(RESTRICTED_SETTINGS_HELP.contains("Allow restricted settings"))
        assertTrue(RESTRICTED_SETTINGS_HELP.contains("three-dot menu"))
        // And it admits the case where the menu item is absent, rather than promising it will work.
        assertTrue(RESTRICTED_SETTINGS_HELP.contains("cannot be granted on this device"))
    }

    @Test
    fun `the Shizuku help names no button in another app`() {
        // Shizuku's own UI has been relabelled more than once and GameCore cannot read it, so the help text
        // describes the two routes and the reboot consequence and stops there.
        assertTrue(SHIZUKU_START_HELP.contains("wireless"))
        assertTrue(SHIZUKU_START_HELP.contains("reboot"))
        assertFalse(SHIZUKU_START_HELP.contains("http"))
    }

    // ---------------------------------------------------------------------------- helper

    private fun rowFor(need: PermissionNeed, isGranted: Boolean): PermissionRow =
        PermissionRow(need = need, catalogue = catalogueEntryFor(need), isGranted = isGranted)
}
