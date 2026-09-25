package com.gamecore.core.overlay

import com.gamecore.core.model.CapabilityStatus
import com.gamecore.core.model.OptimizationAction
import com.gamecore.core.model.OptimizationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The apply-diff overlay's one line, asserted the same way [RefreshRateFeedbackTest] asserts its chip:
 * on the honest field, not on hand-written prose. Every label comparison reads through
 * [OptimizationAction.label] rather than a string literal, so a renamed action changes the test and the
 * overlay together and cannot drift into saying two different things for the same setting.
 *
 * The rule under test is the §24 honesty rule as a string: only a confirmed change reads as a plain
 * label; an unconfirmed one carries "(not confirmed)"; a change the profile never asked for is not a
 * change and does not appear; a profile that changed nothing produces no line at all rather than an
 * empty toast.
 */
class ApplyDiffTest {

    private val refresh = OptimizationAction.PIN_PEAK_REFRESH_RATE
    private val brightness = OptimizationAction.SET_BRIGHTNESS
    private val dnd = OptimizationAction.ENABLE_DO_NOT_DISTURB
    private val cores = OptimizationAction.SET_CPU_AFFINITY

    @Test
    fun `nothing at all produces no line`() {
        assertNull(ApplyDiff.summarize(emptyList()))
    }

    @Test
    fun `a profile that only skips produces no line, never an empty toast`() {
        val results = listOf(
            OptimizationResult.Skipped(brightness, reason = "You did not ask to change brightness."),
            OptimizationResult.Skipped(dnd, reason = "Already on."),
        )

        assertNull("Skips are not changes", ApplyDiff.summarize(results))
    }

    @Test
    fun `a setting this device cannot attempt is not a change and does not appear`() {
        val results = listOf(
            OptimizationResult.Blocked(refresh, CapabilityStatus.REQUIRES_SHIZUKU, detail = "Needs Shizuku."),
        )

        assertNull("Blocked is not a change", ApplyDiff.summarize(results))
    }

    @Test
    fun `a confirmed change reads as its plain label`() {
        val results = listOf(OptimizationResult.Applied(brightness, detail = "Brightness set to 50%."))

        assertEquals(brightness.label, ApplyDiff.summarize(results))
    }

    @Test
    fun `a skip alongside a real change is dropped from the line`() {
        val results = listOf(
            OptimizationResult.Applied(dnd, detail = "Do Not Disturb on."),
            OptimizationResult.Skipped(brightness, reason = "Not requested."),
        )

        assertEquals(dnd.label, ApplyDiff.summarize(results))
    }

    @Test
    fun `a written-but-unconfirmed change carries the applier's own not-confirmed wording`() {
        val results = listOf(OptimizationResult.Unverified(refresh, detail = "Refresh rate pinned."))

        assertEquals("${refresh.label} (not confirmed)", ApplyDiff.summarize(results))
    }

    @Test
    fun `a change the device ignored and a change that failed both read as didn't apply`() {
        val notHonoured = ApplyDiff.summarize(
            listOf(OptimizationResult.NotHonoured(refresh, detail = "Panel stayed at 60 Hz.")),
        )
        val failed = ApplyDiff.summarize(
            listOf(OptimizationResult.Failed(dnd, detail = "Policy write refused.")),
        )

        assertEquals("${refresh.label} didn't apply", notHonoured)
        assertEquals("${dnd.label} didn't apply", failed)
    }

    @Test
    fun `changes come first in plan order and failures follow, joined by the dot separator`() {
        val results = listOf(
            OptimizationResult.Applied(refresh, detail = "Pinned 120 Hz."),
            OptimizationResult.Failed(dnd, detail = "Policy write refused."),
            OptimizationResult.Unverified(brightness, detail = "Brightness written."),
            OptimizationResult.Skipped(cores, reason = "Not requested."),
        )

        val expected = listOf(
            refresh.label,
            "${brightness.label} (not confirmed)",
            "${dnd.label} didn't apply",
        ).joinToString(" · ")

        assertEquals(expected, ApplyDiff.summarize(results))
    }
}
