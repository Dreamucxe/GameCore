package com.gamecore.core.model

import com.gamecore.core.common.DataSource
import com.gamecore.core.common.Observed
import com.gamecore.core.common.Precision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence a user reads after a game launch closed some of their apps.
 *
 * It is the whole account they get of something that cannot be undone, so every branch of it is pinned
 * here rather than left to be noticed on a device. Three of those branches exist because the honest
 * answer is not a number: a pass whose freed figure could not be taken says so, a pass whose figure came
 * out zero or negative says that rather than reporting the absolute value, and a pass that closed
 * nothing offers no figure at all. An app that only ever prints a positive number is choosing which of
 * its own measurements to believe.
 */
class MemoryReclaimReportTest {

    @Test
    fun `a confirmed pass leads with what it closed and gives the measured figure`() {
        val report = completed(
            outcomes = List(4) { ReclaimOutcome.Closed("com.example.app$it", "App $it") },
            freedBytes = measured(340L * MB),
        )
        assertEquals("Closed 4 apps · freed ~340 MB", report.summary())
        assertEquals(4, report.closedCount)
        assertFalse(report.touchedNothing)
    }

    @Test
    fun `a figure that could not be taken is admitted rather than filled in`() {
        val report = completed(
            outcomes = listOf(ReclaimOutcome.Closed(APP, "Notes")),
            freedBytes = Observed.Failed("ActivityManager did not return a memory reading"),
        )
        assertEquals("Closed 1 app · GameCore could not measure what that freed", report.summary())
    }

    @Test
    fun `a figure that did not rise is reported as what it is`() {
        // The normal case on a phone that was not short of memory: the game claims pages faster than a
        // few cached apps release them. Reporting the absolute value would turn that loss into a gain.
        val outcomes = List(3) { ReclaimOutcome.Closed("com.example.app$it", "App $it") }
        val fell = completed(outcomes = outcomes, freedBytes = measured(-48L * MB))
        val flat = completed(outcomes = outcomes, freedBytes = measured(0L))
        val expected = "Closed 3 apps · available memory did not rise while the game was starting"
        assertEquals(expected, fell.summary())
        assertEquals(expected, flat.summary())
    }

    @Test
    fun `the unverified path never borrows the confirmed wording`() {
        // killBackgroundProcesses returns nothing, so there is no second look to be had and the count is
        // of what was asked for. A Requested is never counted as closed, whatever the sentence says.
        val report = completed(
            outcomes = listOf(
                ReclaimOutcome.Requested("com.example.one", "One"),
                ReclaimOutcome.Requested("com.example.two", "Two"),
            ),
            freedBytes = measured(1536L * MB),
            via = DataSource.ACTIVITY_MANAGER,
        )
        assertEquals("Asked Android to close 2 apps · freed ~1.5 GB", report.summary())
        assertEquals(0, report.closedCount)
        assertEquals(2, report.requestedCount)
    }

    @Test
    fun `a pass that closed nothing offers no figure, even when one was taken`() {
        // Two readings either side of a pass that touched nothing measure the game's own loading, and
        // handing that to the user as freed memory is the fabrication this type exists to prevent.
        val report = completed(
            outcomes = listOf(
                ReclaimOutcome.Protected(APP, "Notes", ProtectionReason.FOREGROUND_SERVICE),
                ReclaimOutcome.Protected("com.example.launcher", "Home", ProtectionReason.DEFAULT_LAUNCHER),
            ),
            freedBytes = measured(200L * MB),
        )
        assertEquals("Nothing to close — everything running was in use", report.summary())
        assertTrue(report.touchedNothing)
        assertEquals(2, report.protectedApps.size)
    }

    @Test
    fun `a pass where every close failed says so instead of naming a count`() {
        val report = completed(
            outcomes = listOf(ReclaimOutcome.Failed(APP, "Notes", "It was still running afterwards")),
            freedBytes = measured(0L),
        )
        assertEquals("Nothing could be closed", report.summary())
        assertEquals(1, report.failedCount)
        assertTrue(report.touchedNothing)
    }

    @Test
    fun `a partial pass leads with the part GameCore is sure of`() {
        val report = completed(
            outcomes = listOf(
                ReclaimOutcome.Closed(APP, "Notes"),
                ReclaimOutcome.Failed("com.example.mail", "Mail", "It was still running afterwards"),
                ReclaimOutcome.Protected("com.example.keyboard", "Keyboard", ProtectionReason.CURRENT_IME),
            ),
            freedBytes = measured(340L * MB),
        )
        assertEquals("Closed 1 app · freed ~340 MB", report.summary())
        assertEquals(1, report.failedCount)
        assertFalse(report.touchedNothing)
    }

    // ---- fixtures

    private fun completed(
        outcomes: List<ReclaimOutcome>,
        freedBytes: Observed<Long>,
        via: DataSource = DataSource.SHELL_SHIZUKU,
    ) = MemoryReclaimReport.Completed(outcomes = outcomes, freedBytes = freedBytes, via = via)

    /** Sampled, not exact: both readings are exact, but the interval between them is the device's. */
    private fun measured(bytes: Long): Observed<Long> =
        Observed.of(bytes, DataSource.ACTIVITY_MANAGER, Precision.SAMPLED)
}

private const val APP = "com.example.notes"
private const val MB = 1024L * 1024L
