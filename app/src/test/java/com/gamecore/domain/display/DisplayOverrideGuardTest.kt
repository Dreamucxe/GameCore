package com.gamecore.domain.display

import com.gamecore.core.model.DisplaySize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dead-man's switch's rules, without a device, a display, or a shell.
 *
 * Every case is a rule from [DisplayOverrideGuard] expressed as inputs -> decision. Silence reverting, a
 * long-lived override being offered rather than reverted, and nothing being put back when nothing was
 * recorded are the three the feature rests on, so each has a case of its own.
 */
class DisplayOverrideGuardTest {

    private val applied = 1_000_000_000L

    private fun pending(
        appliedAtMillis: Long = applied,
        previousValue: String? = "1080x2400",
        requestedWidth: Int = 720,
        requestedHeight: Int = 1600,
    ) = PendingDisplayOverride(
        previousValue = previousValue,
        requestedWidth = requestedWidth,
        requestedHeight = requestedHeight,
        appliedAtMillis = appliedAtMillis,
        token = "tok",
    )

    // ──────────────────────────────────────────────────────────── the countdown

    @Test
    fun `a fresh countdown has the full window remaining`() {
        val guard = DisplayOverrideGuard()

        val remaining = guard.remainingMillis(pending(), applied)

        assertEquals(PendingDisplayOverride.CONFIRM_WINDOW_MILLIS, remaining)
        assertFalse(guard.hasExpired(pending(), applied))
    }

    @Test
    fun `the countdown expires at fifteen seconds`() {
        val guard = DisplayOverrideGuard()

        assertFalse(guard.hasExpired(pending(), applied + PendingDisplayOverride.CONFIRM_WINDOW_MILLIS - 1))
        assertTrue(guard.hasExpired(pending(), applied + PendingDisplayOverride.CONFIRM_WINDOW_MILLIS))
    }

    @Test
    fun `remaining seconds round up so the screen never shows zero while time is left`() {
        val guard = DisplayOverrideGuard()

        assertEquals(1L, guard.remainingSeconds(pending(), applied + 14_100L))
        assertEquals(0L, guard.remainingSeconds(pending(), applied + PendingDisplayOverride.CONFIRM_WINDOW_MILLIS))
    }

    @Test
    fun `remaining never goes negative`() {
        val guard = DisplayOverrideGuard()

        assertEquals(0L, guard.remainingMillis(pending(), applied + 60_000L))
    }

    // ────────────────────────────────────────────────────── silence means revert

    @Test
    fun `an expired countdown reverts`() {
        val guard = DisplayOverrideGuard()

        val decision = guard.decide(
            pending(appliedAtMillis = applied - PendingDisplayOverride.CONFIRM_WINDOW_MILLIS),
            applied,
        )

        assertEquals(DisplayOverrideDecision.REVERT, decision)
    }

    @Test
    fun `a process death immediately after the write reverts on launch`() {
        val guard = DisplayOverrideGuard()

        val decision = guard.decide(pending(appliedAtMillis = applied - 1_000L), applied)

        assertEquals(DisplayOverrideDecision.REVERT, decision)
        assertTrue(guard.shouldRevertOnLaunch(pending(appliedAtMillis = applied - 1_000L), applied))
    }

    @Test
    fun `a launch an hour later still reverts`() {
        val guard = DisplayOverrideGuard()

        // The confirmation never happened, so the answer is the one the countdown would have given. Only
        // an override old enough that the user has been living with it is spared.
        val decision = guard.decide(pending(appliedAtMillis = applied - 3_600_000L), applied)

        assertEquals(DisplayOverrideDecision.REVERT, decision)
    }

    @Test
    fun `the user saying keep is the one answer that clears the row`() {
        val guard = DisplayOverrideGuard()

        val decision = guard.decide(
            pending(appliedAtMillis = applied - 1_000L),
            applied,
            userConfirmed = true,
        )

        assertEquals(DisplayOverrideDecision.CLEAR, decision)
    }

    // ────────────────────────────────────────── a long-lived override is left alone

    @Test
    fun `an override the user has lived with for a day is left alone`() {
        val guard = DisplayOverrideGuard()

        val decision = guard.decide(pending(appliedAtMillis = applied - 30L * 60 * 60 * 1000), applied)

        assertEquals(DisplayOverrideDecision.CLEAR, decision)
        assertFalse(guard.shouldRevertOnLaunch(pending(appliedAtMillis = applied - 30L * 60 * 60 * 1000), applied))
    }

    // ────────────────────────────────────────────────── nothing is invented

    @Test
    fun `no pending row decides nothing`() {
        val guard = DisplayOverrideGuard()

        assertEquals(DisplayOverrideDecision.NONE, guard.decide(null, applied))
    }

    @Test
    fun `a row with no recorded previous size still restores by clearing the override`() {
        val guard = DisplayOverrideGuard()

        val row = pending(previousValue = null)

        // Null means the display had nothing set, and the way back is `wm size reset` — the restore
        // path's own rule. It is not a missing recording and must not read as one.
        assertFalse(row.hasRecordedPrevious)
        assertFalse(guard.decide(row, applied) == DisplayOverrideDecision.NONE)
        assertNull(guard.previousSize(row))
    }

    @Test
    fun `a recorded previous size is returned for the revert`() {
        val guard = DisplayOverrideGuard()

        val size = guard.previousSize(pending())

        assertEquals(DisplaySize(1080, 2400), size)
    }

    @Test
    fun `a previous size that cannot be read back is not guessed at`() {
        val guard = DisplayOverrideGuard()

        val size = guard.previousSize(pending(previousValue = "not a size"))

        assertNull(size)
    }

    // ───────────────────────────────────────────────────────── copy is honest

    @Test
    fun `the confirm copy names the requested size and says it outlives a reboot`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.confirmCopy(pending())

        assertTrue(copy.contains("720 × 1600"))
        assertTrue(copy.contains("survives a reboot"))
    }

    @Test
    fun `the revert copy names the size going back`() {
        val guard = DisplayOverrideGuard()

        assertTrue(guard.revertCopy(pending()).contains("1080 × 2400"))
    }

    @Test
    fun `the revert copy says a reset when the display had no override before`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.revertCopy(pending(previousValue = null))

        // Null is not a missing recording: it means `wm size reset`, and the copy must say that rather
        // than implying a size was lost.
        assertTrue(copy.contains("no size override"))
        assertTrue(copy.contains("wm size reset"))
    }

    @Test
    fun `the reverted copy says it was put back, naming the size, when it was`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.revertedCopy(pending())

        assertTrue(copy.contains("720 × 1600"))
        assertTrue(copy.contains("put back"))
        assertTrue(copy.contains("1080 × 2400"))
    }

    @Test
    fun `the reverted copy says the override was cleared when there was none to begin with`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.revertedCopy(pending(previousValue = null))

        assertTrue(copy.contains("cleared"))
    }

    @Test
    fun `the still-set copy names both the reason and the way out`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.stillSetCopy(pending(), "the shell stopped answering")

        assertTrue(copy.contains("the shell stopped answering"))
        assertTrue(copy.contains("put 1080 × 2400 back"))
        assertTrue(copy.contains("recovery screen"))
    }

    @Test
    fun `the still-set copy says clearing the override when there was none to start with`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.stillSetCopy(pending(previousValue = null), "the shell stopped answering")

        assertTrue(copy.contains("the shell stopped answering"))
        assertTrue(copy.contains("clears the override"))
        // It must not claim a size was recorded and lost.
        assertFalse(copy.contains("was recorded"))
    }

    @Test
    fun `the offered copy states what is set and what the two answers are`() {
        val guard = DisplayOverrideGuard()

        val copy = guard.offeredCopy(pending())

        assertTrue(copy.contains("720 × 1600"))
        assertTrue(copy.contains("Keep it, or put it back"))
        assertTrue(copy.contains("1080 × 2400"))
    }
}
