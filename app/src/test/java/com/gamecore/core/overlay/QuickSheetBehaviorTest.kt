package com.gamecore.core.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-close state machine of spec §4, exercised with a fake clock (spec §10).
 *
 * "Fake clock" here is just two `Long`s the test picks — a last-interaction instant and a now — because that
 * is the whole of [QuickSheetAutoClose]'s input. No `Handler`, no `Looper`, no `Robolectric`: if the machine
 * needed any of those it would not be pure, and §10 would not list it here.
 */
class QuickSheetBehaviorTest {

    private val autoClose = QuickSheetAutoClose(idleAfterMillis = 8_000L)

    @Test
    fun `default idle window is the spec's eight seconds`() {
        assertEquals(8_000L, QuickSheetAutoClose.DEFAULT_AUTO_CLOSE_MILLIS)
        assertEquals(8_000L, QuickSheetAutoClose().idleAfterMillis)
    }

    @Test
    fun `stays open before the idle window elapses`() {
        val opened = 1_000L
        assertFalse(autoClose.shouldCloseAt(lastInteractionMillis = opened, nowMillis = opened))
        assertFalse(autoClose.shouldCloseAt(lastInteractionMillis = opened, nowMillis = opened + 7_999L))
    }

    @Test
    fun `closes once the idle window has elapsed`() {
        val opened = 1_000L
        // The boundary counts as closed: >= idleAfterMillis, matching IdleDimmer's fade boundary.
        assertTrue(autoClose.shouldCloseAt(lastInteractionMillis = opened, nowMillis = opened + 8_000L))
        assertTrue(autoClose.shouldCloseAt(lastInteractionMillis = opened, nowMillis = opened + 20_000L))
    }

    @Test
    fun `an interaction resets the idle window`() {
        val opened = 1_000L
        val now = opened + 7_000L
        // 7 s in with no touch: not yet due to close.
        assertFalse(autoClose.shouldCloseAt(lastInteractionMillis = opened, nowMillis = now))
        // A touch at `now` moves the last-interaction instant forward; the same `now` is now fresh again.
        assertFalse(autoClose.shouldCloseAt(lastInteractionMillis = now, nowMillis = now))
        // And it takes a further full window from the touch to close.
        assertFalse(autoClose.shouldCloseAt(lastInteractionMillis = now, nowMillis = now + 7_999L))
        assertTrue(autoClose.shouldCloseAt(lastInteractionMillis = now, nowMillis = now + 8_000L))
    }

    @Test
    fun `nextChange schedules a single delay to the close instant`() {
        val opened = 1_000L
        // Freshly opened: the one scheduled repaint is a full window away.
        assertEquals(8_000L, autoClose.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = opened))
        // Part-way through: only the remainder is left.
        assertEquals(3_000L, autoClose.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = opened + 5_000L))
    }

    @Test
    fun `nextChange is null once the close instant is reached or past`() {
        val opened = 1_000L
        // Exactly at the close instant: nothing further to schedule.
        assertNull(autoClose.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = opened + 8_000L))
        // Well past it: still null, never a negative delay.
        assertNull(autoClose.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = opened + 50_000L))
    }

    @Test
    fun `a custom idle window is honoured by both queries`() {
        val quick = QuickSheetAutoClose(idleAfterMillis = 3_000L)
        val opened = 0L
        assertFalse(quick.shouldCloseAt(lastInteractionMillis = opened, nowMillis = 2_999L))
        assertTrue(quick.shouldCloseAt(lastInteractionMillis = opened, nowMillis = 3_000L))
        assertEquals(3_000L, quick.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = opened))
        assertNull(quick.nextChangeAfterMillis(lastInteractionMillis = opened, nowMillis = 3_000L))
    }
}
